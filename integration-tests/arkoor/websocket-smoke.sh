#!/usr/bin/env bash
# Start the existing environment first: python3 integration-tests/arkoor/environment.py start
# This exercises the Android quote socket against the real mint without sending funds.
set -euo pipefail
cd "$(dirname "$0")/../.."
export NUMO_ARKOOR_MINT_URL="${NUMO_ARKOOR_MINT_URL:-http://127.0.0.1:3339}"
./gradlew testDebugUnitTest --rerun \
    --tests 'com.electricdreams.numo.payment.MintQuoteWebSocketIntegrationTest'
