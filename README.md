# Numo 🥜⚡

Numo is an Android Point-of-Sale application that enables merchants to receive Cashu ecash payments via tap-2-pay.

> [!WARNING]
> This application is **NOT** a wallet. It only acts as a terminal to receive payments and immediately generate redemption tokens. It does not store any tokens. tokens **MUST** be redeemed in a proper Cashu wallet after receiving them, or the funds will be lost.

## Overview

The application acts as a simple point-of-sale terminal for receiving Bitcoin payments. When a customer approaches their phone with a supporting wallet, the app receives the ecash payment.

## How It Works

Using Numo is straightforward:

The merchant enters the desired amount in satoshis using the application's keypad. When they press "Charge", a payment screen appears and a type-4 forum tag is emulated with a cashu PaymentRequest string as its content. When the payer approaches their phone, their Cashu wallet will interact with the emulated tag using the NDEF protocol, reading the contents and decoding the payment request. Immediately after, the paying wallet will write a cashu token with the requested amount to the emulated tag and Numo will process it.
Details of the payer-side spec are available **[here](docs/NDEF_Payer_Side_Spec.md)**.

### Numo also supports direct lightning payments
When the merchant switches to the "lightning" tab in the payment screen, a fresh BOLT11 invoice is generated and displayed as a QR code. The contents of the emulated tag are also overwritten with "lightning:<bolt11>", so compatible wallets are be able to scan the BOLT11 invoice from the tag.

## Features

- Tap-2-pay with cashu ecash and NDEF protocol
- Payment through Nostr
- Lightning bolt11 invoices
- Withdraw to self-custody / withdraw to lightning address.
- Automatic withdrawal after threshold is reached
- Merchant item catalogs, with pre-selectable items
- Merchant item baskets, which can be persisted and updated at different times
- Payment history and direct "Open With" integration for Cashu wallets

## Requirements

- Android device running **Android 7.0 (API level 24) or higher**
- **NFC** hardware with **Host Card Emulation (HCE)** support (required for tap‑to‑pay over NDEF)
- A **rear camera** compatible with CameraX (for QR / barcode scanning)
- Internet connectivity (Wi‑Fi or mobile data) for contacting mints / Lightning endpoints
- A compatible **Cashu wallet / smartcard setup** for actual ecash payment flows

> Numo will install on any device with Android 7.0+ but core features like tap‑to‑pay and QR scanning require NFC and a camera.


## Debug Build

To build the debug version of the app:

```bash
# Clone the repository
git clone https://github.com/lollerfirst/numo.git
cd numo

# Build debug APK
./gradlew assembleDebug

# The APK will be available at:
# app/build/outputs/apk/debug/app-debug.apk
```

## Debug Installation

To install the debug version of the app:
```bash
# Make sure your device is connected and available to adb
adb devices

## Install the APK you've previously built
cd numo
./gradlew installDebug
```

Alternatively, you can open the project in Android Studio and build and install it using the IDE's build tools.

<!--
## Smartcard Compatibility

Numo interfaces with the [Satocash-Applet](https://github.com/Toporin/Satocash-Applet), a JavaCard applet implementation that enables secure storage and transfer of Cashu tokens on smartcards. The applet must be installed on a compatible JavaCard for the app to function properly. For more information about the smartcard implementation and compatibility, please refer to the Satocash-Applet repository.

## NDEF Compatibility

Numo now simulates a Type 4 Forum tag with payment requests encoded using NDEF (NFC Data Exchange Format). This feature enables:

- Reading/writing NDEF messages from web applications that use the WebNFC API
- Direct compatibility with web-based Cashu applications like cashu.me
- Seamless integration between physical NFC devices and web-based payment systems
- Cross-platform payment processing without requiring specialized hardware

The NDEF implementation follows the NFC Forum specifications, ensuring broad compatibility with various NFC-enabled applications and devices.
-->


## Getting Started: Onboarding

After building Numo, simply install the APK on your device and open the app. You'll be presented with an onboarding screen, where you can choose to create a new wallet or restore an old one with your seed-phrase. A further step will prompt you to select the mints you'll be using to receive payments and a few reputable default ones. No additional setup is required - the app is ready to process payments immediately.

## Support

For bugs, feature requests, or general feedback, please open an issue in this repository or submit an email to numopay@proton.me. For security-related concerns, please refer to our security policy.

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.
