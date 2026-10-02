# Numo Arkoor checkout and integration tests

Local checkouts offer Arkoor and Lightning quotes for the same amount in one
BIP321 QR. The first payment method to receive the full amount and issue ecash
completes checkout. Partial payments across the two methods are not combined.
The Cashu and Lightning tabs remain available; BTCPay is a separate payment mode.

The QR, share action, and NFC payload use the same URI. For example, on regtest:

```text
BITCOIN:?AMOUNT=0.0000033&ARK=TARK1...&LIGHTNING=LNBCRT...&CREQ=CREQB1...
```

The BIP321 key is **`ark`**; the CDK payment method is **`arkoor`**. Bark 0.6.2's
[payment request implementation](https://github.com/ark-bitcoin/bark/blob/09e5b81d220d16f1e876df4aa772185640301528/bark/src/payment_request.rs)
accepts and emits `ark`.
[BIP321](https://github.com/bitcoin/bips/blob/master/bip-0321.mediawiki) keys are
case insensitive. Uppercase Bech32 values make the QR denser. `AMOUNT` carries
the checkout value in decimal BTC, with exact integer conversion (330 sats is
`0.0000033` BTC).

The unified payload waits for both quote attempts to finish. If one fails, the
remaining methods stay usable and the failed method's error is shown. History
stores each method's quote ID, destination, and mint separately. Reopening a
checkout reuses those records. Older Arkoor-only entries migrate their legacy
Lightning fields into the Arkoor fields. Expired quotes remain available for
checking late payments.

## Docker regtest environment

Requirements: Docker Engine with Compose v2 on Linux amd64 and Python 3. Android
tests also need the repository's JDK and Android SDK build prerequisites. No
personal wallet, mainnet connection, credentials, or sibling checkout is needed.

```bash
python3 integration-tests/arkoor/environment.py start
python3 integration-tests/arkoor/environment.py status
python3 integration-tests/arkoor/provision.py
python3 integration-tests/arkoor/smoke.py
./integration-tests/arkoor/websocket-smoke.sh
```

The first start builds the CDK mint and Bark processor from pinned public
revisions; this can take several minutes. Later starts reuse Docker's build
cache. `start --no-build` uses an already built `numo-arkoor-services:local` image.
Startup waits for healthy services and fails if the mint does not advertise
both `arkoor` and `bolt11` in sats.

The Compose stack contains:

- Bitcoin Core 30.0 on regtest, with an initial 101 blocks and a miner wallet.
- Mempool electrs 3.3.0, providing the Esplora chain API.
- PostgreSQL 16.4, Core Lightning with its hold-invoice plugin, and Bark's Ark
  server (`captaind`) 0.6.2.
- A Bark 0.6.2 payment processor and CDK 0.18 mint, supporting both checkout methods.
- A separate Bark payer wallet used by the funded payment test.

CDK is pinned to `c29da8f4b2897a313ac480994fbfd46dc73e15f3`. The processor is
pinned to the public v0.1.0 release,
`c7940dd9add3f2ac399da596d253f582809230b1`, with the checked-in
[incoming Arkoor patch](patches/README.md). That patch supplies the quote/address
mapping and durable payment events required by the app, without depending on
an unpublished branch. Bark, captaind, CLN, and electrs images are pinned by digest.

Only the mint is published to the host, at `http://127.0.0.1:3339`. The backend
services communicate inside the Compose network. Test seeds, RPC credentials,
wallets, and coins belong to this regtest environment. State is kept in named
Docker volumes under the `numo-arkoor` Compose project.

View logs and stop while preserving saved quotes and wallets:

```bash
docker compose -f integration-tests/arkoor/docker-compose.yml logs -f
python3 integration-tests/arkoor/environment.py stop
```

Reset all regtest state:

```bash
docker compose -f integration-tests/arkoor/docker-compose.yml --profile tools down --volumes --remove-orphans
```

`provision.py` funds the Ark server and boards funds into the separate payer
wallet using locally mined regtest coins. It can be rerun. To pay a checkout
manually from that wallet:

```bash
python3 integration-tests/arkoor/pay.py --address tark1... --amount 330
```

The payer rejects mainnet Ark addresses.

## GitHub CI and validation

[Arkoor Integration Tests](../../.github/workflows/arkoor-integration.yml) runs
on pull requests to `main`/`master`, pushes to those branches, and manual dispatch.
It builds the pinned services image with a Docker cache, starts the same Compose
stack, provisions regtest funds, and runs the Android unit tests and debug build.
The job always uploads service logs and test reports and removes its containers
and volumes.

`smoke.py` creates two independent unpaid 2-sat Arkoor quotes and a 330-sat
Lightning quote, then checks their saved IDs and destinations through the mint
API. Lightning uses 330 sats to cover Bark's configured receive fee.
`websocket-smoke.sh` enables the Android integration tests:

- Both unpaid quote snapshots arrive over one physical WebSocket connection.
- Closing and resubscribing to a saved Arkoor quote keeps the Lightning
  subscription and the same connection active.
- A 330-sat payment from the Docker payer produces an Arkoor payment notification;
  the Lightning quote remains unpaid, and resubscribing recovers the paid state.

The funded socket test checks receipt notifications and quote recovery. Checkout
issuance and receiving-mint propagation are covered by the Android regression
tests. These tests cover initial status failures, partial payments, interrupted
issuance, rejected subscriptions, reconnection, and concurrent polling/socket
callbacks.

The integration tests are skipped during ordinary unit test runs. CI enables
them with `NUMO_ARKOOR_MINT_URL` and `NUMO_ARKOOR_PAY_SCRIPT`; the shell wrapper
sets both for the local Docker environment. Run the complete suite locally:

```bash
python3 -m unittest discover -s integration-tests/arkoor -p 'test_*.py' -v
NUMO_ARKOOR_MINT_URL=http://127.0.0.1:3339 \
NUMO_ARKOOR_PAY_SCRIPT="$PWD/integration-tests/arkoor/pay.py" \
./gradlew testDebugUnitTest assembleDebug
```

## Install and connect Android

The APK uses application ID `com.electricdreams.numo.arkoor`, display name
**Numo Ark**, and version suffix `-arkoor`. It keeps its own wallet/preferences
and can coexist with Numo. New-wallet onboarding selects the local mint.
Unknown-mint Lightning swaps are disabled by default. Select **sats** as the
base unit.

```bash
./gradlew assembleDebug
adb reverse tcp:3339 tcp:3339
adb install -r app/build/outputs/apk/debug/app-universal-debug.apk
```

For multiple devices, add `-s DEVICE_SERIAL`. Repeat `adb reverse` after
reconnecting. The default mint URL, `http://127.0.0.1:3339`, reaches Docker through
this reverse tunnel. The debug manifest allows cleartext only for loopback and
the emulator host alias `10.0.2.2`. For a hosted mint, build with
`-ParkMintUrl=https://your-mint.example`.

Launch Numo Ark, create its wallet, keep **Numo Arkoor regtest** selected, and
enter a checkout. The initially selected tab is **Unified**, displaying the
exact sats amount. Fiat input remains recorded in history. Success means the
mint confirmed payment and Numo obtained ecash. Closing the screen preserves
the pending checkout in history.

## Backend and monitoring behavior

The processor creates a fresh address per quote and matches successful Bark
movement destinations to it. Stable receipt IDs let CDK deduplicate polling and
streamed events. Polling replays receipts even after a restart or after an event
was delivered. Partial payments accumulate; expiry does not delete the address.
Numo checks received/issued funds before applying quote expiry.

The event loop advances pending Lightning receives until they park, then scans
Arkoor receipts. It keeps delivering Arkoor events while the alternative
Lightning invoice is unpaid.

CDK 0.18's incoming custom-payment protobuf omits the method name. Bark accepts
the reconstructed empty name as its sole custom method, Arkoor; explicit unknown
names remain rejected.

Checkouts share one WebSocket connection per mint, with separate
`arkoor_mint_quote` and `bolt11_mint_quote` subscriptions. Arkoor notifications
wake CDK status checks, which validate the full received amount before issuance.
Arkoor reconciles every 30 seconds while connected and every two seconds when
push is unavailable or issuance needs recovery. Lightning retains its
five-second polling fallback. Saved Arkoor quotes retry their first status check
without creating a replacement address.
