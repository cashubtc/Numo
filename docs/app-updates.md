# In-app updates

Numo checks for updates on the POS home screen and offers a manual check under
**Settings → About → Check for updates**. Downloads do not block payments. Installation
requires the user to finish active checkouts, NFC transfers, withdrawals, and wallet work.
Android displays the final installation confirmation. There is no forced update or offline
lockout.

## Distribution

| Build | Update source | Build command |
| --- | --- | --- |
| Debug | Direct updater; automatic checks disabled | `./gradlew assembleDebug` |
| Release | Latest stable GitHub release | `./gradlew assembleRelease` |
| Play | Google Play flexible in-app updates | `./gradlew bundlePlay` |

The Play build excludes the direct updater classes, receiver, and
`REQUEST_INSTALL_PACKAGES` permission. Upload the **Play** bundle to Google Play.
Play's signing certificate can differ from the direct APK certificate; each channel
updates through its own distribution mechanism.

The direct updater fetches
`https://github.com/cashubtc/Numo/releases/latest/download/update.json` at most once per
day automatically. Manual checks bypass this interval. GitHub's latest-release endpoint
excludes drafts and prereleases, and the client also rejects any channel other than
`stable`. Publishing a prerelease does not trigger an app update. Users jump directly to
the latest stable version when it is compatible, without installing intermediate versions.

## Publishing

Run **Manual Release** with:

1. A release tag such as `v1.10`.
2. An integer `version_code` higher than **all** previous Android releases, including
   prereleases and Google Play builds. For the first release using this workflow, check
   the previous release manually: older releases do not contain update metadata.
3. Optional release notes to display in the app.

The workflow embeds the supplied version into the APK and Play bundle, generates
`update.json` from the **signed universal APK**, and uploads them to a draft release.
It checks that the version code exceeds the latest published stable manifest, when one
exists. Maintainers must also keep codes increasing across prereleases and other stores.
Review the draft and publish it normally. Existing signing secrets are sufficient; no
additional signing key or update server is needed.

The first app version containing the updater must be installed through an existing
distribution method. It can then discover subsequent releases that include `update.json`.
Releases without signed metadata result in a non-blocking check failure.

For local builds with explicit versioning:

```sh
./gradlew assembleRelease -PnumoVersionCode=26 -PnumoVersionName=1.10
```

`scripts/create_update_manifest.py --help` describes the standalone publishing tool.
It requires Python 3.11+, JDK 17+, Android build tools, and the existing signing secrets
in `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD`.

## Verification and recovery

`update.json` is an envelope containing base64 `payload` and `signature` fields. The
signature covers the UTF-8 bytes `Numo update manifest v1\n` followed by the exact payload
bytes, using SHA256withRSA or SHA256withECDSA. The trust anchor is the current installed
APK's signing certificate, never a certificate supplied by the server. The release tool
checks that its signing key matches the APK certificate before creating the envelope.

The payload includes schema version, stable/beta channel, package name, numeric version
code, display version, minimum Android SDK, universal APK URL, size, SHA-256, and notes.
The app checks these fields, rejects non-HTTPS or foreign release URLs, and verifies the
download's length and hash. Immediately before installation it re-verifies persisted
metadata and the APK, then checks the package name, version, minimum SDK and signer.
Android performs its own package-signature validation during installation.

Signing-key rotation is deliberately unsupported in this first implementation. A direct
update must use the current installed signer. Plan an explicit trust migration before
rotating the release key. Returning to older code requires a new, higher-version release;
the updater never installs a lower or equal version code.

Artifacts live under the private, non-backed-up `updates` directory. Downloads continue
while the app process lives; after process death an accepted download resumes on the
next launch using HTTP ranges. A server that ignores ranges causes a fresh download.
No background service or persistent background polling is used. Successful upgrades
clean up old update artifacts without changing wallet preferences or databases.

An application-wide gate accounts for queued and running payment coroutines, Nostr relay
callbacks, and checkout screens. Starting installation and admitting another payment are
mutually exclusive.
New asynchronous operations wait while installation is pending. Cancellation or failure
releases the gate. Leaving the update screen cancels a pending direct installation;
configuration changes preserve it. An outstanding direct installer session from an old
process is abandoned before wallet work starts. Wallet crash recovery remains necessary
because Android, other installers, or the user can independently terminate the app.

## Validation

```sh
./gradlew testDebugUnitTest --tests 'com.electricdreams.numo.core.update.*'
./gradlew testDebugUnitTest --tests 'com.electricdreams.numo.nostr.NostrPaymentListenerUpdateTest'
./gradlew assembleDebug assemblePlay lintDebug
python3 -m unittest discover -s scripts/tests -p 'test_update_manifest.py'
```

The updater tests cover signatures, API 24/36 package metadata checks, partial downloads,
tampering, version eligibility, operation scheduling, installer cancellation, stale
callbacks, and process recreation. Signed fixtures use a disposable test key; only its
public certificate is committed. The fixture signature format is produced by the same
Java helper used in CI.

Before shipping, exercise two consecutively versioned, identically signed builds on a
device: deny/allow the install permission, cancel/retry installation, interrupt a download,
and upgrade with a wallet containing a pending payment. Confirm that balances, seed,
history, and pending-payment recovery survive the upgrade. Test the Play flow using a
Play test track and an eligible Play-installed build.
