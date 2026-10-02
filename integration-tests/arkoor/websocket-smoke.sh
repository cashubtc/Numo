#!/usr/bin/env bash
# Start the existing environment first: python3 integration-tests/arkoor/environment.py start
# Provision the regtest payer first: python3 integration-tests/arkoor/provision.py
# Exercises quote snapshots, recovery, and a funded regtest payment on one socket.
set -euo pipefail
cd "$(dirname "$0")/../.."
export NUMO_ARKOOR_MINT_URL="${NUMO_ARKOOR_MINT_URL:-http://127.0.0.1:3339}"
export NUMO_ARKOOR_PAY_SCRIPT="${NUMO_ARKOOR_PAY_SCRIPT:-$PWD/integration-tests/arkoor/pay.py}"
./gradlew testDebugUnitTest --rerun \
    --tests 'com.electricdreams.numo.payment.MintQuoteWebSocketIntegrationTest'
