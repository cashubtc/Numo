# Numo Arkoor experiment

This branch replaces the **local CDK checkout's Lightning tab** with **Arkoor**.
It requests `PaymentMethod.Custom("arkoor")`, displays the returned Ark address
unchanged as a QR code, shares that address, and polls the saved quote until the
full checkout amount is paid and ecash has been issued. The address does not
encode an amount: the payer must enter the amount shown by Numo. The Cashu tab
remains available. The combined Cashu/Lightning QR is hidden for this checkout;
Ark addresses must not go in a BIP321 `lightning=` parameter. BTCPay remains a
separate, unchanged payment mode.

The APK uses application ID `com.electricdreams.numo.arkoor` and version suffix
`-arkoor-experimental`, with its own wallet and preferences. It can coexist with
normal Numo. New-wallet onboarding selects the local Arkoor mint. Unknown-mint
Lightning swaps are disabled by default. Select **sats** as the base unit.
The Arkoor-only mint does not offer Lightning withdrawals; ecash can be exported
as a Cashu token.

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
```

The mint listens on `127.0.0.1:3339`; its Bark processor listens on
`127.0.0.1:50059`. Both bind only to loopback. The processor advertises **only
`arkoor`** for this experiment. Do not run another processor over the same Bark
wallet directory at the same time.

State, logs, the mint seed, and a backup of Bark's data before this experiment
are stored in `~/.local/share/numo-arkoor/`. Keep the complete mint directory and
`mint.seed`; changing or losing the seed prevents redemption of issued ecash.
The existing Bark mnemonic stays in its existing configuration and is never
copied into this repository. `NUMO_CDK_DIR`, `NUMO_BARK_DIR`, and `NUMO_ARK_STATE`
override the default paths.

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
selected mint, and enter a checkout of at least **330 sats** (maximum 1,000,000).
The initial selected tab is **Arkoor**, with the exact quoted amount in sats as
the main amount. Fiat input remains recorded in history. Scan with a compatible Bark wallet on
the same Ark server, enter the displayed sats amount, and pay. A successful
checkout means the mint has confirmed payment and Numo has obtained ecash.

Closing the screen leaves the quote in payment history. Reopen the pending
entry to reuse the address and quote; no replacement quote is created.
The existing serialized history fields `lightningInvoice`, `lightningQuoteId`,
and `lightningMintUrl` hold the request, quote, and mint for this branch;
`paymentType` distinguishes `arkoor` from `lightning`. Transaction details and
CSV exports label these payments Arkoor.

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
  --tests 'com.electricdreams.numo.feature.history.PaymentsHistoryActivityTest' \
  --tests 'com.electricdreams.numo.core.util.MintManagerTest' \
  --tests 'com.electricdreams.numo.ui.components.AmountDisplayManagerTest'
./gradlew assembleDebug lintDebug
(cd ~/cdk-payment-processors/crates/bark && cargo test --locked --lib)
(cd ~/cdk-payment-processors/crates/bark && cargo clippy --locked -- -D warnings)
python3 integration-tests/arkoor/smoke.py
```

The smoke test uses the real mainnet mint/processor to create two distinct
unpaid Ark requests, poll them, and check that BOLT11 is unavailable. It sends
no funds. Unit tests cover partial-payment accounting, cancellation, retries,
resume behavior, history persistence, receive attribution and durable event
deduplication. A funded mainnet payment is a separate manual validation step.

Validation on the development host also covered onboarding against the real
mint, opening a 330-sat request in an Android 34 emulator, decoding the rendered
QR back to the exact mint-provided address, sharing that same address, and
reopening the saved quote. No mainnet payment was sent. Android Lint completes
with the repository's existing findings (`abortOnError = false`); it is not a
clean lint baseline.
