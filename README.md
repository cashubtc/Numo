# Shellshock

Shellshock is an Android Point-of-Sale application that enables merchants to receive Cashu ecash payments via smartcards. Using NFC technology, it provides a simple way to process transactions with physical cards that store Cashu tokens.

> ⚠️ **WARNING**: This application is **NOT** a wallet. It only acts as a terminal to receive payments and immediately generate redemption tokens. It does not store any tokens. tokens **MUST** be redeemed in a proper Cashu wallet immediately after receiving them, or you will lose your funds.

## Overview

The application acts as a simple point-of-sale terminal for Cashu smartcards. When a customer presents their card, the app receives the ecash payment and immediately generates a token that the merchant must redeem in their Cashu wallet. The app does not store or manage any funds - it only facilitates the immediate transfer from card to token.

## How It Works

Using Shellshock is straightforward:

The merchant enters the desired amount in satoshis using the application's keypad. When a customer presents their Cashu smartcard to the merchant's NFC-enabled Android device, Shellshock communicates with the card to process the payment.

If the card is PIN-protected, the customer enters their PIN through a secure input dialog. Once verified, the application checks the card's balance and receives the payment. A Cashu token is immediately generated and displayed, which the merchant must copy and redeem in their actual Cashu wallet right away.

The app also includes a balance checking feature to verify a card's available funds, and a top-up function that allows loading Cashu tokens onto cards.

## Features

- Simple amount entry with numeric keypad
- Secure NFC communication with Cashu smartcards
- PIN verification for protected cards
- Balance checking functionality
- Card top-up capability
- Immediate token display and copy functionality
- [Coming Soon] Direct "Open With" integration for Cashu wallets

## Requirements

- Android device with NFC capability
- Android 5.0 (API level 21) or higher
- Compatible Cashu smartcard
- Android Studio or Gradle for building

## Building

To build the debug version of the app:

```bash
# Clone the repository
git clone https://github.com/yourusername/shellshock2.git
cd shellshock2

# Build debug APK
./gradlew assembleDebug

# The APK will be available at:
# app/build/outputs/apk/debug/app-debug.apk
```

Alternatively, you can open the project in Android Studio and build it using the IDE's build tools.

## Smartcard Compatibility

Shellshock interfaces with the [Satocash-Applet](https://github.com/Toporin/Satocash-Applet), a JavaCard applet implementation that enables secure storage and transfer of Cashu tokens on smartcards. The applet must be installed on a compatible JavaCard for the app to function properly. For more information about the smartcard implementation and compatibility, please refer to the Satocash-Applet repository.

## Getting Started

After building Shellshock, simply install the APK on your device and open the app. You'll be presented with the amount entry screen. No additional setup is required - the app is ready to process payments immediately.

## Support

For bugs, feature requests, or general feedback, please open an issue in this repository. For security-related concerns, please refer to our security policy.

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.
