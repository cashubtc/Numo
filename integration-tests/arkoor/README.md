# Numo Arkoor experiment

This branch adds Arkoor to the **unified BIP321 QR** alongside Cashu and
Lightning. Every new local checkout immediately requests two independent mint
quotes for the same amount: `PaymentMethod.Bolt11` and
`PaymentMethod.Custom("arkoor")`. Both are monitored, and the method that first
completes payment and ecash issuance completes the checkout. They are alternative
ways to pay the full amount; partial payments across different methods are not
combined. The original Cashu and Lightning tabs remain available. BTCPay remains
a separate payment mode.

The unified QR, share action, and NFC payload all use the same URI:

```text
BITCOIN:?AMOUNT=0.0000033&ARK=ARK1...&LIGHTNING=LNBC...&CREQ=CREQB1...
```

**The correct URI key is `ark`, while the CDK method name is `arkoor`.**
Bark 0.6.2's `BarkExtension::handle_param` explicitly accepts `ark`, and
`serialize_params` emits `ark`. Its round-trip test also checks `&ARK=ARK1`.
See [Bark's payment request implementation](https://raw.githubusercontent.com/ark-bitcoin/bark/master/bark/src/payment_request.rs)
and [BIP321](https://github.com/bitcoin/bips/blob/master/bip-0321.mediawiki).
BIP321 keys are case insensitive; uppercase Bech32 values make the QR denser.
The Ark address has no amount, so `AMOUNT` carries the checkout value in decimal
BTC (330 sats = `0.0000033` BTC), using exact integer-to-decimal conversion.
Wallets need Ark support to use that option; Lightning wallets can use the
`LIGHTNING` alternative.

The unified payload waits for both quote attempts to finish. If one method fails,
the other available methods remain usable and the user sees the method error.
Quote IDs are stored independently; reopening a pending checkout reuses both.
Previously saved Arkoor-only experimental entries migrate their legacy Lightning
fields into the dedicated Arkoor fields before requesting the missing Lightning
quote. An expired Arkoor quote remains in history so late funds can be checked.

The APK uses application ID `com.electricdreams.numo.arkoor` and version suffix
`-arkoor-experimental`, with its own wallet and preferences. It can coexist with
normal Numo. New-wallet onboarding selects the local mint. Unknown-mint Lightning
swaps are disabled by default. Select **sats** as the base unit.

## Mainnet environment

The setup uses the existing configuration and wallet in
`~/cdk-payment-processors/crates/bark/config.toml` and its `bark.data_dir`.
It connects to the mainnet Ark server specified there. It creates a **separate
CDK mint**, rather than opening or migrating `~/.cdk-mintd`.

Repositories used:

- Numo: branch `experiment/arkoor-payments`.
- `~/cdk-payment-processors`: branch `experiment/numo-arkoor-receive`, based on
  `fix/bark-unattempted-arkoor-status`. Its incoming Arkoor support is required.
- `~/cdk`: existing checkout at `c29da8f4` (CDK 0.18). No CDK source changes.
- Android bindings: existing `org.cashudevkit:cdk-android:0.18.0-rc.0`.

Build and start:

```bash
(cd ~/cdk && cargo build --locked -p cdk-mintd --no-default-features --features grpc-processor,sqlite)
(cd ~/cdk-payment-processors/crates/bark && cargo build --locked --release)
cd ~/numo
python3 integration-tests/arkoor/environment.py start
python3 integration-tests/arkoor/environment.py status
python3 integration-tests/arkoor/smoke.py
./integration-tests/arkoor/websocket-smoke.sh
```

The mint listens on `127.0.0.1:3339`; its Bark processor listens on
`127.0.0.1:50059`. Both bind only to loopback. The processor advertises **`bolt11` and
`arkoor`** for this experiment. When upgrading from the Arkoor-only setup,
run `environment.py stop` and then `environment.py start` to apply the new
processor method list. Do not run another processor over the same Bark
wallet directory at the same time.

State, logs, the mint seed, and a backup of Bark's data before this experiment
are stored in `~/.local/share/numo-arkoor/`. Keep the complete mint directory and
`mint.seed`; changing or losing the seed prevents redemption of issued ecash.
The existing Bark mnemonic stays in its existing configuration and is never
copied into this repository. `NUMO_CDK_DIR`, `NUMO_BARK_DIR`, and `NUMO_ARK_STATE`
override the default paths.

Follow both service logs while testing (`Ctrl-C` stops viewing the logs only):

```bash
tail -F ~/.local/share/numo-arkoor/mint.log \
        ~/.local/share/numo-arkoor/processor.log
```

For detailed mint request and quote logs:

```bash
tail -F ~/.local/share/numo-arkoor/mint/logs/cdk-mintd.log
```

CDK stores configuration in its database. `environment.py` imports `mint.toml`
once with `config init --new-mint`. To change the mint config later, stop the
services, edit that file, explicitly apply it, then start again:

```bash
python3 integration-tests/arkoor/environment.py stop
~/cdk/target/debug/cdk-mintd --work-dir ~/.local/share/numo-arkoor/mint \
  config apply --file ~/.local/share/numo-arkoor/mint.toml
python3 integration-tests/arkoor/environment.py start
```

## Install and connect Android

```bash
./gradlew assembleDebug
adb reverse tcp:3339 tcp:3339
adb install -r app/build/outputs/apk/debug/app-universal-debug.apk
```

For multiple devices, add `-s DEVICE_SERIAL` to both adb commands. Repeat
`adb reverse` after reconnecting the device. The default mint URL in the APK is
`http://127.0.0.1:3339`, which reaches the host through this reverse tunnel.
The debug manifest allows cleartext only for loopback and the emulator host
alias `10.0.2.2`. A remotely hosted mint should use HTTPS; override the build
URL with `./gradlew assembleDebug -ParkMintUrl=https://your-mint.example`.

Launch **Numo Ark**, create its wallet, keep **Numo Arkoor experiment** as the
selected mint, and enter a checkout of at least **2 sats** (maximum 1,000,000).
The initially selected tab is **Unified**, with the exact quoted amount in sats
as the main amount. Fiat input remains recorded in history. Scan with a compatible
Bark wallet on the same Ark server to pay using Arkoor, or use the Lightning
invoice. A successful checkout means the mint has confirmed payment and Numo has
obtained ecash. Closing the screen keeps the pending checkout in history.

History stores `lightningInvoice`, `lightningQuoteId`, and `lightningMintUrl`
separately from `arkoorAddress`, `arkoorQuoteId`, and `arkoorMintUrl`.
`paymentType` records the method that actually paid, rather than the first quote
that happened to become ready. Both quote records survive completion.

## Backend behavior and compatibility

The processor creates a fresh address per quote, persists its mapping, and
matches successful Bark movement destinations to that address. Stable receipt
IDs let CDK deduplicate polling and streamed events. Status polling replays all
receipts even after a processor restart or an event was already delivered.
Partial payments accumulate. Expiry does not delete the address or discard
late payments; Numo checks received/issued funds before its expiry check.

CDK 0.18's incoming custom-payment protobuf omits the method name and its
server adapter reconstructs it as an empty string. Bark accepts that empty
name as its sole custom method, Arkoor. Explicit unknown names remain rejected.
This compatibility path is unnecessary once both CDK adapters transmit the
method name.

## Validation

```bash
./gradlew testDebugUnitTest \
  --tests 'com.electricdreams.numo.payment.ArkoorMintSessionTest' \
  --tests 'com.electricdreams.numo.payment.UnifiedPaymentRequestTest' \
  --tests 'com.electricdreams.numo.payment.LightningMintHandlerTest' \
  --tests 'com.electricdreams.numo.payment.LightningMintHandlerCoreTest' \
  --tests 'com.electricdreams.numo.feature.history.PaymentsHistoryActivityTest' \
  --tests 'com.electricdreams.numo.feature.history.ActivityCsvExportHelperTest' \
  --tests 'com.electricdreams.numo.core.util.MintManagerTest' \
  --tests 'com.electricdreams.numo.ui.components.AmountDisplayManagerTest'
./gradlew assembleDebug lintDebug
(cd ~/cdk-payment-processors/crates/bark && cargo test --locked --lib)
(cd ~/cdk-payment-processors/crates/bark && cargo clippy --locked -- -D warnings)
python3 integration-tests/arkoor/smoke.py
```

The smoke test uses the real mainnet mint/processor to create two distinct
unpaid 2-sat Ark requests and one 2-sat Lightning invoice, and poll their saved
quotes. It sends no funds. Unit tests cover partial-payment accounting, cancellation, retries,
resume behavior, history persistence, receive attribution and durable event
deduplication. A funded 2-sat mainnet payment remains a separate manual validation step.

To check a decoded URI with Bark's actual parser without opening a wallet or
sending funds, write the URI to a text file and run:

```bash
cd ~/cdk-payment-processors/crates/bark
cargo run --locked --example inspect_bip321 -- /path/to/unified-request.txt
```

The result contains the parsed sats amount, Ark addresses, and Lightning invoices.
Compare them with the two mint quote responses. Android Lint completes with the
repository's existing findings (`abortOnError = false`); it is not a clean lint
baseline.

On the development host, the Android 34 emulator generated a real 330-sat checkout
with distinct Lightning and Arkoor quote IDs. The rendered QR decoded successfully;
Bark 0.6.2 parsed that exact URI and recovered both mint-provided destinations and
330 sats. Android's share preview matched the same URI. Reopening the checkout
reused both quote IDs and preserved both destinations and the amount in the QR.

On 2026-10-01, a manual 330-sat Ark payment from Noah Android reached Bark at
15:52:56 UTC. Numo displayed success at 15:52:58 UTC, and both the mint quote
(`amount_paid = amount_issued = 330`) and Numo's completed history entry confirmed
issuance. That build polled its saved Arkoor quote every two seconds.

Current checkouts share one WebSocket connection per mint, with separate
`arkoor_mint_quote` and `bolt11_mint_quote` subscriptions. Arkoor notifications
wake CDK status checks, which validate the full received amount before issuance.
Arkoor reconciles status every 30 seconds while connected, and every two seconds
when push updates are unavailable or issuance needs recovery. Lightning retains
its five-second polling fallback. Saved Arkoor quotes also retry their initial
status check without creating a replacement quote.

`websocket-smoke.sh` runs the Android shared-connection code against this real
mint, checking both quote snapshots and resubscription on a single connection.
It creates unpaid 2-sat quotes and sends no funds. Start the environment first;
set `NUMO_ARKOOR_MINT_URL` to test a different mint.
