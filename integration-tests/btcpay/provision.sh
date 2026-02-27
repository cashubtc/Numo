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

# Output to properties file
OUTPUT_FILE="btcpay_env.properties"

cat > "$OUTPUT_FILE" <<EOF
BTCPAY_SERVER_URL=$BASE_URL
BTCPAY_API_KEY=$API_KEY
BTCPAY_STORE_ID=$STORE_ID
EOF

echo ""
echo "=== Done ==="
echo "Credentials saved to $OUTPUT_FILE"
echo "  URL:      $BASE_URL"
echo "  API Key:  $API_KEY"
echo "  Store ID: $STORE_ID"