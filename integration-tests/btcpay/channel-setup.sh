#!/bin/sh
set -e

CUSTOMER_LND_URL="${CUSTOMER_LND_URL:-http://customer_lnd:8080}"
MINT_LND_URL="${MINT_LND_URL:-http://mint_lnd:8080}"
BTCPAY_LND_URL="${BTCPAY_LND_URL:-http://lnd_bitcoin:8080}"
MINT_MACAROON_PATH="${MINT_MACAROON_PATH:-/mint_lnd_data/data/chain/bitcoin/regtest/admin.macaroon}"
BTC_RPC_URL="${BTC_RPC_URL:-http://bitcoind:18443}"
BTC_RPC_USER="${BTC_RPC_USER:-btcpay}"
BTC_RPC_PASS="${BTC_RPC_PASS:-btcpay}"

echo "=== Channel Setup ==="
echo "  customer_lnd : $CUSTOMER_LND_URL"
echo "  mint_lnd     : $MINT_LND_URL"
echo "  lnd_bitcoin  : $BTCPAY_LND_URL"

# ── helpers ───────────────────────────────────────────────────────────────────

btc_rpc() {
  curl -sf -u "$BTC_RPC_USER:$BTC_RPC_PASS" -X POST "$BTC_RPC_URL" \
    -H "Content-Type: application/json" -d "$1"
}

lnd_get() {
  local url="$1" path="$2" mac="$3"
  if [ -n "$mac" ]; then
    curl -sf -H "Grpc-Metadata-macaroon: $mac" "$url$path"
  else
    curl -sf "$url$path"
  fi
}

lnd_post() {
  local url="$1" path="$2" body="$3" mac="$4"
  if [ -n "$mac" ]; then
    curl -sf -X POST -H "Content-Type: application/json" \
      -H "Grpc-Metadata-macaroon: $mac" -d "$body" "$url$path"
  else
    curl -sf -X POST -H "Content-Type: application/json" -d "$body" "$url$path"
  fi
}

wait_for_lnd() {
  local url="$1" name="$2" mac="$3"
  echo "Waiting for $name..."
  for i in $(seq 1 60); do
    code=$(lnd_get "$url" "/v1/getinfo" "$mac" 2>/dev/null | jq -e '.identity_pubkey' >/dev/null 2>&1 && echo 200 || echo 0)
    [ "$code" = "200" ] && { echo "  $name ready."; return 0; }
    echo "  $name not ready ($i/60)..."
    sleep 3
  done
  echo "ERROR: $name did not become ready"; exit 1
}

# ── wait for macaroon ─────────────────────────────────────────────────────────

echo "Waiting for mint_lnd macaroon at $MINT_MACAROON_PATH..."
for i in $(seq 1 60); do
  [ -f "$MINT_MACAROON_PATH" ] && break
  echo "  waiting ($i/60)..."
  sleep 3
done
[ -f "$MINT_MACAROON_PATH" ] || { echo "ERROR: macaroon not found"; exit 1; }

MACAROON=$(xxd -p -c 1000 "$MINT_MACAROON_PATH" | tr -d '\n')

wait_for_lnd "$CUSTOMER_LND_URL" "customer_lnd" ""
wait_for_lnd "$MINT_LND_URL"     "mint_lnd"     "$MACAROON"
wait_for_lnd "$BTCPAY_LND_URL"   "lnd_bitcoin"  ""

# ── fund customer_lnd ─────────────────────────────────────────────────────────

echo "Getting customer_lnd funding address..."
CUSTOMER_ADDR=$(lnd_get "$CUSTOMER_LND_URL" "/v1/newaddress?type=0" "" | jq -r '.address')
echo "  address: $CUSTOMER_ADDR"

echo "Mining 110 blocks to customer_lnd..."
btc_rpc "{\"jsonrpc\":\"1.0\",\"id\":\"1\",\"method\":\"generatetoaddress\",\"params\":[110,\"$CUSTOMER_ADDR\"]}" > /dev/null

echo "Waiting for customer_lnd balance..."
for i in $(seq 1 30); do
  BAL=$(lnd_get "$CUSTOMER_LND_URL" "/v1/balance/blockchain" "" | jq -r '.confirmed_balance // "0"')
  echo "  balance: $BAL sats"
  [ "$BAL" -gt 0 ] 2>/dev/null && break
  sleep 3
done

# ── channel: customer_lnd → mint_lnd ─────────────────────────────────────────

echo "Getting mint_lnd pubkey..."
MINT_PUBKEY=$(lnd_get "$MINT_LND_URL" "/v1/getinfo" "$MACAROON" | jq -r '.identity_pubkey')
echo "  mint_lnd pubkey: $MINT_PUBKEY"

echo "Connecting customer_lnd → mint_lnd..."
lnd_post "$CUSTOMER_LND_URL" "/v1/peers" \
  "{\"addr\":{\"pubkey\":\"$MINT_PUBKEY\",\"host\":\"mint_lnd:9735\"},\"perm\":false}" "" || true

echo "Opening channel customer_lnd → mint_lnd (2 000 000 sats)..."
lnd_post "$CUSTOMER_LND_URL" "/v1/channels" \
  "{\"node_pubkey_string\":\"$MINT_PUBKEY\",\"local_funding_amount\":2000000}" "" | jq .

# ── channel: customer_lnd → lnd_bitcoin (BTCPay) ─────────────────────────────

echo "Getting lnd_bitcoin pubkey..."
BTCPAY_PUBKEY=$(lnd_get "$BTCPAY_LND_URL" "/v1/getinfo" "" | jq -r '.identity_pubkey')
echo "  lnd_bitcoin pubkey: $BTCPAY_PUBKEY"

echo "Connecting customer_lnd → lnd_bitcoin..."
lnd_post "$CUSTOMER_LND_URL" "/v1/peers" \
  "{\"addr\":{\"pubkey\":\"$BTCPAY_PUBKEY\",\"host\":\"lnd_bitcoin:9735\"},\"perm\":false}" "" || true

echo "Opening channel customer_lnd → lnd_bitcoin (2 000 000 sats)..."
lnd_post "$CUSTOMER_LND_URL" "/v1/channels" \
  "{\"node_pubkey_string\":\"$BTCPAY_PUBKEY\",\"local_funding_amount\":2000000}" "" | jq .

# ── confirm channels ──────────────────────────────────────────────────────────

echo "Mining 6 blocks to confirm channels..."
MINE_ADDR=$(btc_rpc '{"jsonrpc":"1.0","id":"1","method":"getnewaddress","params":[]}' | jq -r '.result')
btc_rpc "{\"jsonrpc\":\"1.0\",\"id\":\"1\",\"method\":\"generatetoaddress\",\"params\":[6,\"$MINE_ADDR\"]}" > /dev/null

echo "Waiting for channels to become active..."
for i in $(seq 1 60); do
  ACTIVE=$(lnd_get "$CUSTOMER_LND_URL" "/v1/channels" "" | jq '[.channels[]? | select(.active==true)] | length')
  echo "  active channels: $ACTIVE"
  [ "$ACTIVE" -ge 2 ] 2>/dev/null && break
  [ $((i % 5)) -eq 0 ] && \
    btc_rpc "{\"jsonrpc\":\"1.0\",\"id\":\"1\",\"method\":\"generatetoaddress\",\"params\":[1,\"$MINE_ADDR\"]}" > /dev/null
  sleep 3
done

echo "=== Channel setup complete ==="
echo "  customer_lnd → mint_lnd    : open"
echo "  customer_lnd → lnd_bitcoin : open"
