# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

Small in-person merchants — coffee shops, market stalls, event vendors — using an ordinary Android phone as their bitcoin payment terminal instead of dedicated hardware. Their job: charge a customer, get paid in seconds, keep the line moving. Customers interact with the merchant's device only for the tap or QR scan; the merchant is the operator of every screen.

## Product Purpose

Numo is an Android point-of-sale application that lets merchants receive bitcoin payments — Cashu ecash via NFC tap-to-pay, Lightning BOLT11 invoices, and Nostr payments. Success is a payment that completes as fast and confidently as a card tap, with funds landing in the merchant's own wallet.

## Positioning

True tap-to-pay for bitcoin: the customer taps their phone and pays like a card. Numo emulates an NFC type-4 tag carrying a Cashu PaymentRequest; the customer's Cashu wallet reads it and writes back an ecash token over NDEF (payer-side spec: docs/NDEF_Payer_Side_Spec.md). No hardware terminal, no custodian, no node — a claim neighboring bitcoin POS products cannot truthfully copy.

## Operating Context

Counter-top and street commerce: noisy, bright, hurried, one-handed. The merchant enters an amount on a keypad, presses Charge, and turns the phone toward the customer for a tap or QR scan. Requirements: Android 7.0+ (minSdk 24), NFC with Host Card Emulation for tap-to-pay, rear camera (CameraX) for QR scanning, and internet connectivity to reach mints and Lightning endpoints.

## Capabilities and Constraints

- Payment methods: Cashu ecash tap-to-pay (NFC/NDEF), Lightning BOLT11 (QR + tag rewrite as `lightning:<bolt11>`), Nostr; a Unified request mode presents them together.
- Withdrawal: manual withdraw to self-custody or a lightning address; automatic withdrawal once a configured threshold is reached.
- Merchant tools: item catalogs with pre-selectable items, persistable baskets, payment history with "Open With" Cashu-wallet integration, sales insights, animated QR for large withdrawal tokens.
- Wallet plumbing: BIP39 12-word seed, Cashu Dev Kit (`org.cashudevkit:cdk-kotlin`) plus a custom Cashu Java SDK, mint management with Nostr mint backup/restore.
- **Numo is NOT a wallet.** It is a receive terminal that generates redemption tokens; it stores no long-term funds, and received tokens must be redeemed to a proper Cashu wallet or the funds are lost. This is a hard product truth, not marketing copy.
- Tech constraints: native Android Views + ViewBinding (no Jetpack Compose), Kotlin-first with legacy Java, MVVM/MVC mix, Robolectric-heavy unit testing. Localized in English, German, Spanish, Portuguese, Japanese, and Korean.
- Open source under the cashubtc GitHub organization.

## Brand Commitments

Binding, per the maintainer: never present Numo as a wallet or imply custody, in copy or in visual framing; keep the brand kit — navy `#0A2540` ground, green accents, the condensed Numo wordmark, and native Android sans-serif typography. The app's type system is the `Text.*` role scale in `app/src/main/res/values/styles.xml` (~1.33 ratio); onboarding display roles live in `onboarding_tour.xml`.

## Evidence on Hand

- Working payment flows in the shipping app (POS keypad, payment request, payment received screens).
- Payer-side NFC spec: `docs/NDEF_Payer_Side_Spec.md`; webhook payload reference: `docs/webhook-payload-v2.ts`.
- Design records: `docs/design/onboarding-motion.md`, `docs/design/onboarding-phone-frame.md` (asset provenance for the photographic handset composites).
- Marketing banner: `docs/marketing/readme_banner.png`. CI build and coverage badges on the README.
- No testimonials, case studies, or usage metrics are on hand; future work must not fabricate them.

## Product Principles

1. **As fast as a card tap.** Every flow is judged against contactless card checkout; anything slower or less certain loses the counter.
2. **Terminal, never custodian.** Funds pass through; the design must always show money on its way to the merchant's own wallet, never resting in Numo.
3. **A spare phone is the hardware.** Everything must work on an ordinary mid-range Android device — NFC, camera, one hand, bright daylight.
4. **Bitcoin-grade honesty.** Amounts, fees, and states are shown exactly; illustrative numbers (as in onboarding) stay internally consistent and clearly decorative.
5. **Native Android, no costume.** Platform conventions, system text scaling, and TalkBack behavior outrank visual novelty.

## Accessibility & Inclusion

Onboarding and payment surfaces are verified at 200% system text scale and with animations disabled; decorative illustrations are hidden from accessibility services while headings, descriptions, and actions carry the meaning; TalkBack retains native pager actions. This bar applies to future surfaces.
