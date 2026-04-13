#!/bin/bash
set -e

BASE_URL="http://localhost:49392"
EMAIL="admin@example.com"
PASSWORD="Password123!"

echo "=== BTCPay Server Provisioning ==="

# Wait for BTCPay Server to be ready
echo "Waiting for BTCPay Server..."
for i in {1..60}; do
    if curl -s "$BASE_URL/health" > /dev/null 2>&1; then
        echo "Server is ready."
        break
    fi
    if [ "$i" -eq 60 ]; then
        echo "ERROR: BTCPay Server did not start within 120s"
        exit 1
    fi
    sleep 2
done

# Create admin user
echo "Creating user..."
RESPONSE=$(curl -s -X POST "$BASE_URL/api/v1/users" \
    -H "Content-Type: application/json" \
    -d "{\"email\": \"$EMAIL\", \"password\": \"$PASSWORD\", \"isAdministrator\": true}")
echo "User response: $RESPONSE"

# Generate API Key
echo "Generating API Key..."
RESPONSE=$(curl -s -u "$EMAIL:$PASSWORD" -X POST "$BASE_URL/api/v1/api-keys" \
    -H "Content-Type: application/json" \
    -d '{
        "label": "IntegrationTestKey",
        "permissions": [
            "btcpay.store.canmodifystoresettings",
            "btcpay.store.cancreateinvoice",
            "btcpay.store.canviewinvoices",
            "btcpay.store.canmodifyinvoices",
            "btcpay.user.canviewprofile",
            "btcpay.server.canuseinternallightningnode"
        ]
    }')

API_KEY=$(echo "$RESPONSE" | jq -r '.apiKey')
if [ -z "$API_KEY" ] || [ "$API_KEY" == "null" ]; then
    echo "ERROR: Failed to generate API Key: $RESPONSE"
    exit 1
fi
echo "API Key: $API_KEY"

# Create Store
echo "Creating Store..."
RESPONSE=$(curl -s -X POST "$BASE_URL/api/v1/stores" \
    -H "Authorization: token $API_KEY" \
    -H "Content-Type: application/json" \
    -d '{"name": "IntegrationTestStore", "defaultCurrency": "SATS"}')

STORE_ID=$(echo "$RESPONSE" | jq -r '.id')
if [ -z "$STORE_ID" ] || [ "$STORE_ID" == "null" ]; then
    echo "ERROR: Failed to create store: $RESPONSE"
    exit 1
fi
echo "Store ID: $STORE_ID"

echo "Enabling Lightning..."
for i in {1..30}; do
    RESPONSE=$(curl -s -w "\n%{http_code}" -X PUT \
        "$BASE_URL/api/v1/stores/$STORE_ID/payment-methods/BTC-LN" \
        -H "Authorization: token $API_KEY" \
        -H "Content-Type: application/json" \
        -d '{"enabled": true, "config": "Internal Node"}')
    HTTP_CODE=$(echo "$RESPONSE" | tail -1)
    BODY=$(echo "$RESPONSE" | sed '$d')

    if [ "$HTTP_CODE" == "200" ]; then
        echo "Lightning enabled!"
        break
    fi
    echo "Not ready (HTTP $HTTP_CODE): $BODY - retrying ($i/30)..."
    sleep 5
done

# Wait for invoice creation readiness
echo "Waiting for invoice creation to be ready..."
for i in {1..30}; do
    RESPONSE=$(curl -s -w "\n%{http_code}" -X POST \
        "$BASE_URL/api/v1/stores/$STORE_ID/invoices" \
        -H "Authorization: token $API_KEY" \
        -H "Content-Type: application/json" \
        -d '{"amount": "1000", "currency": "SATS"}')
    HTTP_CODE=$(echo "$RESPONSE" | tail -1)
    BODY=$(echo "$RESPONSE" | sed '$d')

    if [ "$HTTP_CODE" == "200" ] || [ "$HTTP_CODE" == "201" ]; then
        echo "Invoice creation ready (HTTP $HTTP_CODE)"
        break
    fi
    if [ "$i" -eq 30 ]; then
        echo "ERROR: Invoice creation not ready after 90s: $BODY"
        exit 1
    fi
    echo "Not ready yet (HTTP $HTTP_CODE), waiting 3s... ($i/30)"
    sleep 3
done

# Create Cashu wallet
echo "Creating Cashu wallet..."
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST \
    "$BASE_URL/api/v1/stores/$STORE_ID/cashu/wallet" \
    -H "Authorization: token $API_KEY" \
    -H "Content-Type: application/json")
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | sed '$d')

if [ "$HTTP_CODE" == "200" ]; then
    echo "Cashu wallet created!"
    CASHU_MNEMONIC=$(echo "$BODY" | jq -r '.mnemonic // empty')
    if [ -n "$CASHU_MNEMONIC" ]; then
        echo "Mnemonic: $CASHU_MNEMONIC"
    fi
else
    echo "WARNING: Could not create Cashu wallet (HTTP $HTTP_CODE): $BODY"
    echo "  (Cashu plugin may not be installed)"
fi

# Enable Cashu payment method
echo "Enabling Cashu payment method..."
RESPONSE=$(curl -s -w "\n%{http_code}" -X PUT \
    "$BASE_URL/api/v1/stores/$STORE_ID/cashu" \
    -H "Authorization: token $API_KEY" \
    -H "Content-Type: application/json" \
    -d '{
        "enabled": true,
        "paymentModel": "TrustedMintsOnly",
        "trustedMintsUrls": []
    }')
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | sed '$d')

CASHU_ENABLED="false"
if [ "$HTTP_CODE" == "200" ]; then
    CASHU_ENABLED=$(echo "$BODY" | jq -r '.enabled // false')
    CASHU_PAYMENT_MODEL=$(echo "$BODY" | jq -r '.paymentModel // empty')
    echo "Cashu enabled: $CASHU_ENABLED (model: $CASHU_PAYMENT_MODEL)"
else
    echo "WARNING: Could not enable Cashu (HTTP $HTTP_CODE): $BODY"
fi

# Wait for Cashu payment method to appear on invoices
if [ "$CASHU_ENABLED" == "true" ]; then
    echo "Waiting for Cashu payment method to be available on invoices..."
    for i in {1..20}; do
        RESPONSE=$(curl -s -X POST \
            "$BASE_URL/api/v1/stores/$STORE_ID/invoices" \
            -H "Authorization: token $API_KEY" \
            -H "Content-Type: application/json" \
            -d '{"amount": "1000", "currency": "SATS"}')
        TEST_INVOICE_ID=$(echo "$RESPONSE" | jq -r '.id // empty')

        if [ -n "$TEST_INVOICE_ID" ]; then
            PM_RESPONSE=$(curl -s \
                "$BASE_URL/api/v1/stores/$STORE_ID/invoices/$TEST_INVOICE_ID/payment-methods" \
                -H "Authorization: token $API_KEY")
            HAS_CASHU=$(echo "$PM_RESPONSE" | jq '[.[] | select(.paymentMethodId | test("Cashu"; "i"))] | length')

            if [ "$HAS_CASHU" -gt 0 ]; then
                echo "Cashu payment method is available on invoices!"
                # Invalidate test invoice
                curl -s -X POST \
                    "$BASE_URL/api/v1/stores/$STORE_ID/invoices/$TEST_INVOICE_ID/status" \
                    -H "Authorization: token $API_KEY" \
                    -H "Content-Type: application/json" \
                    -d '{"status": "Invalid"}' > /dev/null 2>&1
                break
            fi
        fi

        if [ "$i" -eq 20 ]; then
            echo "WARNING: Cashu payment method not appearing on invoices after 40s"
        fi
        echo "  Not yet ($i/20)..."
        sleep 2
    done
fi

# Output to properties file
OUTPUT_FILE="btcpay_env.properties"

cat > "$OUTPUT_FILE" <<EOF
BTCPAY_SERVER_URL=$BASE_URL
BTCPAY_API_KEY=$API_KEY
BTCPAY_STORE_ID=$STORE_ID
CASHU_ENABLED=$CASHU_ENABLED
EOF

echo ""
echo "=== Done ==="
echo "Credentials saved to $OUTPUT_FILE"
echo "  URL:      $BASE_URL"
echo "  API Key:  $API_KEY"
echo "  Store ID: $STORE_ID"
echo "  Cashu:    $CASHU_ENABLED"