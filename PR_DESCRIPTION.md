# NFC Payment Animation

## Summary
Adds a full-screen animated loading experience during NFC payment processing, replacing the previous static overlay.

## Changes
- **New animation**: HTML/WebView-based blob morph animation displayed during NFC reading
- **Full-screen mode**: Animation covers entire screen with close button
- **Integration**: Connected to payment success/error callbacks
- **Auto-withdrawal**: Extracted `triggerPostPaymentOperations()` for consistent behavior
- **Dark mode**: Added button styling for dark theme support
- **Sunmi POS fix**: Resolved full-screen issue where hardware buttons were hidden

## Files Changed
- `PaymentRequestActivity.kt` - Animation lifecycle and payment callback integration
- `activity_payment_request.xml` - Replaced overlay with animation container
- `nfc_animation.html` - New animation asset
- Added fade animations and button drawables

## Testing
- Verified animation displays during NFC payment flow
- Confirmed close button works in both light and dark modes
- Fixed Sunmi POS device button visibility issue




