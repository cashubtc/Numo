# Native merchant onboarding

The first welcome screen is now a four-scene introduction, followed by the existing create/restore flow. It loops automatically through all four scenes. Get started remains available throughout, with manual swiping and no visible playback controls or progress indicators. The centered wordmark anchors the top; the illustration and copy form one centered group above the fixed footer.

## First-run navigation

Get started on any scene opens the existing **Set Up Your Wallet** screen, keeping both **Create New Wallet** and **Restore From Backup** before mint selection. Watching the tour or tapping Get started does not complete onboarding. Closing the app before setup is complete returns to the welcome on the next launch. Once setup is complete, normal launches skip the welcome and open checkout directly.

## Story and motion

| Scene | Copy | Illustration | Duration |
| --- | --- | --- | --- |
| Welcome to Numo | Accept bitcoin for everything you sell. | Coffee, T-shirt, and tote cards rise, fade, and grow from 96.5% at their own centers; fiat and sat amounts roll at uneven authored offsets. A labeled Add item tile completes the grid. | 5.6 s |
| A tap. A payment. | Let customers pay with a tap from a compatible bitcoin wallet. | A stationary photographic handset shows the shipping white-theme POS entering $3.50, the payment request, and payment received. An iPhone showing the user-supplied wallet scan sheet glides in at a fixed shallow angle along a placing arc, lands almost vertically with a brief press-in dab, pauses, and withdraws as confirmation appears. | 8.8 s |
| Your sales. Your wallet. | Set up auto-withdraw to send your sales to your own self-custodial wallet. | Seven outgoing payment notifications arrive in an uneven burst, with alternating entry tilt, a softly damped ~6% spring overshoot, and a three-card trailing stack. | 6.4 s |
| See your day add up. | Follow your sales, spot your busiest hours, and see what sells. | An irregular merchant sales line draws itself along its own length with the endpoint dot riding the pen tip — a quiet opening, lunch burst, lull, and late pickup as the total rolls upward. | 6.2 s |

The supplied X Money recording informed the composition and pacing. [Emil Kowalski's animation guidance](https://github.com/emilkowalski/skills/tree/main/skills/animate) informed restrained entrances and purposeful, interruptible motion. The interface keeps Numo's navy backdrop, wordmark, and native Android type. Product cards, notifications, and the chart use white surfaces, dark text, and small green accents. Page changes use an asymmetric crossfade, including the final-to-first wrap: a brisk 220 ms linear exit and a 260 ms strongly eased entrance. Get started acknowledges presses with a quick 0.97 scale and an unhurried release.

The tap sequence keeps the merchant phone fixed and uses 220 ms screen crossfades. Keypad presses darken their key briefly; the black charge button lightens instead, so both reads survive the white POS theme. The customer phone holds a constant -5° angle and approaches over 720 ms with differently eased axes — a fast horizontal glide whose descent arrives late — so the straight segment becomes a placing arc with a nearly vertical landing. A 480 ms, 2.6-point press-in dab marks contact; as it bottoms out the terminal fades over 240 ms to a white processing state where a green arc orbits a light track (900 ms per revolution, on the shared deterministic clock) for roughly a second. The phone rests, then withdraws over 520 ms while lifting slightly sooner than it slides, mirroring the arc. Confirmation crossfades in during withdrawal, replacing the spinner, so success reads as the outcome of processing and becomes visible as the foreground phone clears the terminal. The supplied wallet screenshot remains intact. The phones are still photographic 2D composites; this pass refines their choreography without introducing simulated 3D rotation. A contact ring was prototyped and removed: centered at the seam it hides under the customer phone, and anywhere visible it lands on the QR-busy screen as noise.

[Torph](https://github.com/lochie/torph) informed place-value matching for numbers. `RollingAmountPainter` is an independent Kotlin/Canvas implementation, with no web runtime or added dependency. Integer columns match from the decimal point, fractional digits from the left, and currency/grouping symbols travel with their columns. Unchanged digits remain stationary. Changed glyphs roll through a clipped baseline with opacity falloff, cascading rightmost-column-first like an odometer carrying upward (at most 90 ms of skew per column, capped at 32% of the roll); layout slides stay in lockstep so widths never shear. The caller owns the animation clock.

## Typography

The Impeccable typeset pass preserves Android sans-serif and the existing condensed wordmark. Scoped text roles use 28/34sp medium headlines at -0.01em tracking, 16/24sp regular descriptions, 17/22sp medium actions, and 12/17sp regular legal text. MaterialTextView supplies compatible line height on older Android versions. Headings and descriptions balance wrapping without hyphenation, descriptions are bounded to 360dp, and the 120×54dp wordmark stays centered in its header with an additional 24dp of top breathing room below the safe area. The small green panel captions use #008935 on white, above 4.5:1 contrast. The type detector returns no findings for these targets; it is a web detector, so native source and physical render review provide the actual assessment.

## Implementation boundaries

- `OnboardingTourView` uses ViewBinding, ViewPager2, and one ValueAnimator for the visible scene and its page crossfade. Hidden scenes have no clocks. Playback stops on backgrounding, lost window focus, detachment, leaving the welcome step, and while a touch is held.
- `OnboardingSceneView` draws the four illustrations with native Canvas. The handset combines a generated photographic frame with cached snapshots of the actual POS, payment-request, and receipt ViewBinding layouts. `CheckoutPreviewScreens` creates these without starting Activities or reading merchant preferences; its QR contains non-payable plain text. The received snapshot restyles Close from the shipping gray secondary to the black primary — at illustration scale the gray reads as a disabled button — without touching the live payment screen. See [handset asset provenance](onboarding-phone-frame.md). It does not read balances, contact wallets, request NFC access, or configure auto-withdraw.
- Monetary values are fixed onboarding illustrations; the user requested no visible Demo label. Fiat examples use USD at a fixed illustrative rate of $78,544/BTC, rounded to the nearest satoshi; the associated sat values are consistent with that rate. The chart is illustrative sales activity, not actual history or a replica of the existing Insights bar-chart control.
- Disabled system animations and TalkBack touch exploration show completed illustrations and leave navigation manual. The current page and elapsed time survive recreation. Illustrations are decorative to accessibility services; headings, descriptions, native pager actions, and Get started carry the meaning. There is no visible Replay, Pause, Next, or progress chrome.
- Copy uses Android string resources, including German, Spanish, Portuguese, Japanese, and Korean. Quantity labels use plurals. The tour is centered and limited to 600dp on wider windows, with scrollable page content for limited height and larger type.
- Number motion is reusable but currently confined to onboarding. Checkout and real payment totals keep their existing behavior.

## Preview on a development device

Build and install the debug APK, then open a preview that does not change onboarding completion or enter wallet setup:

```sh
adb shell am start -n com.electricdreams.numo/.feature.onboarding.OnboardingActivity \
  --ez preview_onboarding true
```

The extra is honored only in debug builds with completed onboarding. Get started exits this preview to protect the existing wallet. If setup is incomplete, the extra has no effect: Get started opens the existing create/restore step. To verify normal launch behavior on a configured device, omit the extra; do not clear its wallet data or onboarding preference.

## Verification

Run the focused regression suite and build:

```sh
./gradlew testDebugUnitTest \
  --tests 'com.electricdreams.numo.feature.onboarding.*' \
  --tests 'com.electricdreams.numo.ui.animation.RollingAmountPainterTest' \
  assembleDebug lintDebug
```

The revised tour has native Skia render fixtures for each scene, the keypad/waiting/received stages, and 160% system text. These are Robolectric renders, not emulator or physical-device captures. The focused suite checks cyclic playback, inactivity, reduced motion, navigation, saved state, preview isolation, and place-aligned numbers.

The final revision was installed and recorded on the USB-connected Pixel 7a. Hardware captures verify visible keypad entry, waiting, settled handset contact, received payment, all four scenes, and automatic return to the first scene. A separate hardware pass covered all four pages at 200% system text with animations disabled, including vertical scrolling on the longer tap page; original font and animation settings were restored, leaving the normal preview loop open. No emulator was used.

The delivery build’s 32-second debug recording reported 6 missed frame deadlines across 2,917 rendered frames (0.21%). This is one screen-recorded debug run, not a release performance guarantee. Manual TalkBack speech and wider-device coverage remain unchecked. All 25 focused tests and `assembleDebug` pass. The native scene render test also passes after enlarging the wordmark; the final contrast and axis-label corrections compile in the delivery build. Android Lint completes but the repository still reports 209 existing errors; none are in the new tour implementation. Local native render artifacts are generated under `app/build/onboarding-previews/`; final USB captures and the video are in `/private/tmp/numo-onboarding-qa/delivery/`. The independent review confirmed the supplied wallet screenshot is pixel-identical to its lossless WebP asset.

The subsequent first-run routing check adds coverage for Get started from every scene, reopening unfinished setup, completed setup bypassing the welcome, and the preview extra on a fresh install. All 21 tests in `OnboardingActivityTest` and `OnboardingTourTest`, plus `assembleDebug`, pass. The update is installed on the USB Pixel 7a; its existing completed setup opens checkout on normal launch. The phone is left in normal mode with its wallet and onboarding preferences preserved. Fresh-install and interrupted-setup routes are verified with Robolectric rather than resetting that wallet.

The larger wordmark and restrained tap revision pass all 11 tests in `OnboardingRenderTest` and `OnboardingTourTest`, plus `assembleDebug`. Native fixtures include approach, hold, withdrawal, and confirmation. The USB Pixel 7a captures cover normal playback and 200% text with animations disabled; display settings are restored afterward. The 32-second debug capture reports 7 missed frame deadlines across 2,923 frames. This revision's recording and hardware stills are in `/private/tmp/numo-onboarding-qa/quiet-tap/`. Normal launch is restored after the visual preview. The phone currently has no completed-onboarding preference, and Get started is verified on hardware to open the existing Create / Restore choice. No wallet is created or restored during this check; Back returns to the welcome.
