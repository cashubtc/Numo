#!/bin/bash
set -e

BASE_URL="http://localhost:49392"
EMAIL="admin@example.com"
PASSWORD="Password123!"

echo "Waiting for BTCPay Server to be ready..."
for i in {1..30}; do
    if curl -s "$BASE_URL/health" > /dev/null; then
        echo "Server is ready."
        break
    fi
    sleep 2
done

# Create user
echo "Creating user..."
RESPONSE=$(curl -s -X POST "$BASE_URL/api/v1/users" \
    -H "Content-Type: application/json" \
    -d "{\"email\": \"$EMAIL\", \"password\": \"$PASSWORD\", \"isAdministrator\": true}")

# Generate API Key
echo "Generating API Key..."
# Note: Basic Auth with curl -u
RESPONSE=$(curl -s -u "$EMAIL:$PASSWORD" -X POST "$BASE_URL/api/v1/api-keys" \
    -H "Content-Type: application/json" \
    -d '{
        "label": "IntegrationTestKey",
        "permissions": [
            "btcpay.store.canmodifystoresettings",
            "btcpay.store.cancreateinvoice",
            "btcpay.store.canviewinvoices",
            "btcpay.user.canviewprofile"
        ]
    }')

API_KEY=$(echo $RESPONSE | jq -r '.apiKey')

if [ "$API_KEY" == "null" ]; then
    echo "Failed to generate API Key: $RESPONSE"
    exit 1
fi
echo "API Key: $API_KEY"

# Create Store
echo "Creating Store..."
RESPONSE=$(curl -s -X POST "$BASE_URL/api/v1/stores" \
    -H "Authorization: token $API_KEY" \
    -H "Content-Type: application/json" \
    -d '{"name": "IntegrationTestStore", "defaultCurrency": "SATS"}')

STORE_ID=$(echo $RESPONSE | jq -r '.id')
echo "Store ID: $STORE_ID"

# Enable Lightning Network (Internal Node)
echo "Enabling Lightning Network..."
curl -s -X PUT "$BASE_URL/api/v1/stores/$STORE_ID/payment-methods/LightningNetwork/BTC" \
    -H "Authorization: token $API_KEY" \
    -H "Content-Type: application/json" \
    -d '{"connectionString": "Internal Node", "enabled": true}' > /dev/null

# Generate On-Chain Wallet
echo "Generating On-Chain Wallet..."
curl -s -X POST "$BASE_URL/api/v1/stores/$STORE_ID/payment-methods/OnChain/BTC/generate" \
    -H "Authorization: token $API_KEY" \
    -H "Content-Type: application/json" \
    -d '{"savePrivateKeys": true, "importKeysToRPC": true}' > /dev/null

# Output to properties file (project root)
OUTPUT_FILE="../../btcpay_env.properties"

echo "BTCPAY_SERVER_URL=$BASE_URL" > $OUTPUT_FILE
echo "BTCPAY_API_KEY=$API_KEY" >> $OUTPUT_FILE
echo "BTCPAY_STORE_ID=$STORE_ID" >> $OUTPUT_FILE

echo ""
echo "Credentials saved to $OUTPUT_FILE"
