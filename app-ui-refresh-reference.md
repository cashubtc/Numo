# App-Wide UI Refresh — Design Reference

This document contains the complete design specification and reference code for the Numo app UI refresh.
All values should be adapted to Kotlin/Android native equivalents (dp, sp, Compose, Material theme).

---

## DESIGN TOKENS — Map these to the app's existing theme

```
/* Colors (conceptual — use app's actual theme values) */
--green:            app's primary green (#22c55e or equivalent)
--green-soft:       green at 8% opacity   (for hover/press backgrounds)
--green-glow:       green at 15% opacity  (for radial gradient accents)
--green-border:     green at 20% opacity  (for hero card borders)
--text-primary:     app's primary text color
--text-secondary:   app's secondary text color
--text-tertiary:    app's most muted text color
--surface:          app's default background
--surface-raised:   app's elevated surface (cards, nav elements)
--border:           white at 6% opacity (light dividers)

/* Spacing */
Screen horizontal padding:    20dp
Row vertical padding:         14dp
Row horizontal padding:       16dp
Section header margin bottom: 14dp
Space between sections:       24-32dp
List item gap:                2dp
Hero card padding:            24dp
Hero card corner radius:      16dp
General card corner radius:   12dp

/* Typography */
Nav title:          17sp, SemiBold (600), letterSpacing -0.2sp
Section header:     13sp, SemiBold (600), letterSpacing 0.8sp, ALL CAPS, secondary color
Row title:          15sp, Medium (500), letterSpacing -0.1sp, primary color
Row subtitle:       12sp, Regular (400), secondary or tertiary color
Hero card title:    17sp, SemiBold (600), letterSpacing -0.2sp
Hero card subtitle: 13sp, Regular (400), secondary color, lineHeight 1.4
Helper text:        13sp, Regular (400), tertiary color, lineHeight 1.5
Badge text:         11sp, Bold (700), letterSpacing 0.3sp
Button text:        16sp, SemiBold (600), letterSpacing -0.2sp
Secondary btn text: 14sp, Medium (500)
Hint text:          12sp, Regular (400), tertiary color, centered

/* Animation */
Entrance stagger delay:  50ms between elements
Entrance duration:       400ms per element
Entrance easing:         ease-out (DecelerateInterpolator / EaseOut)
Entrance transform:      translateY(16dp → 0dp) + alpha(0 → 1)
Checkbox transition:     200ms
Button press:            scale(0.99) on press, scale(1.01) on hover
```

---

## COMPONENT SPECIFICATIONS

### 1. Section Header

```
┌─────────────────────────────────────┐
│ SECTION TITLE              2 items  │
└─────────────────────────────────────┘

- Row layout: title left, optional count right
- Title: 13sp, SemiBold, 0.8sp letter spacing, uppercase, secondary color
- Count (optional): 12sp, Regular, tertiary color
- Margin bottom: 14dp
```

### 2. List Row

```
┌─────────────────────────────────────────┐
│ [icon 40dp]  Row Title           [>]    │
│              Row subtitle               │
└─────────────────────────────────────────┘

- Horizontal layout: icon → column(title, subtitle) → spacer → trailing element
- Icon: 40dp circle (in list rows), 44dp circle (in hero cards)
- Gap between icon and text: 14dp
- Title: 15sp, Medium (500), letterSpacing -0.1sp, primary color
- Subtitle: 12sp, Regular, secondary/tertiary color
- Row padding: 14dp vertical, 16dp horizontal
- Corner radius: 12dp
- Background: transparent default, surface-raised on press/hover
- Trailing: chevron, checkbox, toggle, badge, or nothing
```

### 3. Checkbox

```
┌──────┐        ┌──────┐
│      │   →    │  ✓   │
│      │        │      │
└──────┘        └──────┘
unchecked        checked

- Size: 22dp x 22dp
- Corner radius: 6dp
- Unchecked: 2dp border, white at 15% opacity, transparent fill
- Checked: solid green background, green border, dark checkmark icon (14dp)
- Checkmark: stroke width 2.5, dark color (black or dark surface)
- Transition: 200ms for background, border, and checkmark opacity
```

### 4. Primary Button

```
┌─────────────────────────────────────────┐
│              Button Label               │
└─────────────────────────────────────────┘

- Full width of content area
- Padding: 16dp vertical
- Corner radius: fully rounded (100dp or 50% height)
- Background: primary text color (light text in dark mode, dark in light mode)
- Text: 16sp, SemiBold (600), letterSpacing -0.2sp
- Text color: background color (inverted)
- Press effect: slight scale down (0.99)
- Disabled: clearly visible but muted, reduced opacity (~0.5) or desaturated
```

### 5. Secondary / Dashed Button

```
┌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌┐
╎          + Action Label               ╎
└╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌┘

- Full width of content area
- Border: 1dp dashed, white at 12% opacity
- Corner radius: 12dp
- Padding: 14dp vertical
- Content: centered row with icon (18dp) + text
- Text: 14sp, Medium (500), secondary color
- On press: border → green, text → green, background → green at 8% opacity
- Margin bottom: 20dp (when above primary button)
```

### 6. Nav Bar / Top App Bar

```
┌─────────────────────────────────────────┐
│  (←)          Screen Title              │
└─────────────────────────────────────────┘

- Title: centered, 17sp, SemiBold (600), letterSpacing -0.2sp
- Back button: 36dp circle, surface-raised background, 18dp chevron icon
- No divider/line underneath
- Seamless background match with screen content (no color mismatch)
```

### 7. Hero Card (used for Default Mint, Auto-Withdraw)

```
┌──────────────────────────────────────┐
│ LABEL (green, uppercase, small)      │
│                                      │
│ [icon] Title              [Badge]    │
│         Subtitle                     │
│                                      │
│ ──────────────────────────────────── │
│ Helper text description that can     │
│ wrap to multiple lines.              │
│                              ⚡(bg)  │
└──────────────────────────────────────┘

Container:
- Corner radius: 16dp
- Padding: 24dp
- Overflow: clip (for lightning bolt)
- Border: 1dp solid green at 20% opacity
- Background: linear gradient 135deg from surface → green at 6% opacity
- Optional radial glow: green at 15% opacity, positioned top-right

Label:
- 11sp, SemiBold (600), letterSpacing 1.2sp, uppercase, green color
- Margin bottom: 16dp

Content row:
- Icon: 44dp, fetched mint profile icon or feature icon
- Title: 17sp, SemiBold, primary color
- Subtitle: 13sp, Regular, secondary color
- Badge: green pill, 11sp Bold, 4dp/10dp padding, fully rounded

Helper section:
- Separated by 1dp divider (white at 6% opacity)
- 16dp margin/padding top
- Text: 13sp, Regular, tertiary color, lineHeight 1.5

Lightning bolt watermark:
- Positioned absolute bottom-right, anchored to edges
- Size: ~120dp x 120dp (overflows and clips)
- Opacity: 6%
- Color: green (matches gradient theme)
- Vertical fade: opacity decreases toward bottom edge
- pointer-events: none equivalent (not interactive)
```

### 8. Entrance Animation Utility

```
For Jetpack Compose:

@Composable
fun StaggeredReveal(
    index: Int,          // position in sequence (0, 1, 2, ...)
    baseDelay: Int = 50, // ms between each element
    content: @Composable () -> Unit
) {
    // Each element:
    // - delay = index * baseDelay + 100ms (initial offset)
    // - duration = 400ms
    // - easing = EaseOut
    // - translateY: 16dp → 0dp
    // - alpha: 0f → 1f
}

For XML views:
- Use LayoutAnimation with stagger on RecyclerView/LinearLayout
- Or individual ObjectAnimator per view with calculated delay
- translationY: 16dp → 0dp
- alpha: 0f → 1f
- duration: 400ms
- interpolator: DecelerateInterpolator
- startDelay: (index * 50) + 100ms
```

---

## FULL HTML MOCKUP — Visual Reference

This is the approved mockup for the Your Mints screen. It represents the target look and feel
for the entire app. Every component above was extracted from this mockup.

```html
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Numo — Your Mints</title>
<style>
  @import url('https://fonts.googleapis.com/css2?family=DM+Sans:ital,opsz,wght@0,9..40,300;0,9..40,400;0,9..40,500;0,9..40,600;0,9..40,700&display=swap');

  * { margin: 0; padding: 0; box-sizing: border-box; }

  :root {
    --bg: #0a0f0a;
    --surface: #141a14;
    --surface-raised: #1a211a;
    --surface-hover: #1f271f;
    --text-primary: #f0f4f0;
    --text-secondary: #8a9a8a;
    --text-tertiary: #5a6a5a;
    --green: #22c55e;
    --green-soft: rgba(34, 197, 94, 0.08);
    --green-glow: rgba(34, 197, 94, 0.15);
    --green-border: rgba(34, 197, 94, 0.2);
    --orange: #f59e0b;
    --orange-soft: rgba(245, 158, 11, 0.08);
    --orange-border: rgba(245, 158, 11, 0.15);
    --border: rgba(255, 255, 255, 0.06);
    --radius: 16px;
    --radius-sm: 12px;
  }

  body {
    font-family: 'DM Sans', -apple-system, sans-serif;
    background: var(--bg);
    color: var(--text-primary);
    min-height: 100vh;
    display: flex;
    justify-content: center;
    padding: 20px;
  }

  .phone-frame {
    width: 390px;
    min-height: 844px;
    background: var(--bg);
    border-radius: 40px;
    border: 2px solid rgba(255,255,255,0.08);
    overflow: hidden;
    position: relative;
  }

  .nav {
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 60px 20px 16px;
    position: relative;
  }
  .nav-back {
    position: absolute;
    left: 20px;
    top: 60px;
    width: 36px;
    height: 36px;
    border-radius: 50%;
    background: var(--surface-raised);
    display: flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    transition: background 0.2s;
  }
  .nav-back:hover { background: var(--surface-hover); }
  .nav-back svg { width: 18px; height: 18px; stroke: var(--text-secondary); }
  .nav-title {
    font-size: 17px;
    font-weight: 600;
    letter-spacing: -0.2px;
  }

  .content {
    padding: 8px 20px 40px;
  }

  .default-hero {
    position: relative;
    border-radius: var(--radius);
    padding: 24px;
    margin-bottom: 32px;
    overflow: hidden;
    background: linear-gradient(135deg, var(--surface) 0%, rgba(34, 197, 94, 0.06) 100%);
    border: 1px solid var(--green-border);
    animation: heroIn 0.6s ease-out;
  }
  @keyframes heroIn {
    from { opacity: 0; transform: translateY(12px); }
    to { opacity: 1; transform: translateY(0); }
  }
  .default-hero::before {
    content: '';
    position: absolute;
    top: -40%;
    right: -20%;
    width: 200px;
    height: 200px;
    background: radial-gradient(circle, var(--green-glow) 0%, transparent 70%);
    pointer-events: none;
  }

  .hero-bolt {
    position: absolute;
    bottom: -20px;
    right: -10px;
    width: 120px;
    height: 120px;
    opacity: 0.06;
    pointer-events: none;
  }
  .hero-bolt svg { width: 100%; height: 100%; fill: var(--green); }

  .default-label {
    font-size: 11px;
    font-weight: 600;
    letter-spacing: 1.2px;
    text-transform: uppercase;
    color: var(--green);
    margin-bottom: 16px;
  }

  .default-mint-row {
    display: flex;
    align-items: center;
    gap: 14px;
  }
  .mint-avatar {
    width: 44px;
    height: 44px;
    border-radius: 50%;
    display: flex;
    align-items: center;
    justify-content: center;
    font-weight: 700;
    font-size: 18px;
    flex-shrink: 0;
  }
  .mint-avatar.chorus { background: #fff; color: #dc2626; font-size: 22px; }
  .mint-avatar.coinos { background: #000; border: 2px solid #333; color: #fff; font-size: 14px; }
  .mint-avatar.minibits { background: linear-gradient(135deg, #38bdf8, #6366f1); color: #fff; }
  .mint-avatar.cuba { background: #f97316; color: #fff; font-size: 12px; }

  .default-mint-info { flex: 1; }
  .default-mint-name {
    font-size: 17px;
    font-weight: 600;
    letter-spacing: -0.2px;
    margin-bottom: 2px;
  }
  .default-mint-desc {
    font-size: 13px;
    color: var(--text-secondary);
    line-height: 1.4;
  }

  .default-badge {
    background: var(--green);
    color: #000;
    font-size: 11px;
    font-weight: 700;
    padding: 4px 10px;
    border-radius: 100px;
    letter-spacing: 0.3px;
  }

  .hero-helper {
    margin-top: 16px;
    padding-top: 16px;
    border-top: 1px solid rgba(255,255,255,0.06);
    font-size: 13px;
    color: var(--text-tertiary);
    line-height: 1.5;
  }

  .section-header {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    margin-bottom: 14dp;
  }
  .section-title {
    font-size: 13px;
    font-weight: 600;
    letter-spacing: 0.8px;
    text-transform: uppercase;
    color: var(--text-secondary);
  }
  .section-count {
    font-size: 12px;
    color: var(--text-tertiary);
  }

  .mint-list {
    display: flex;
    flex-direction: column;
    gap: 2px;
    margin-bottom: 24px;
  }
  .mint-row {
    display: flex;
    align-items: center;
    gap: 14px;
    padding: 14px 16px;
    border-radius: var(--radius-sm);
    cursor: pointer;
    transition: all 0.2s;
    position: relative;
  }
  .mint-row:hover { background: var(--surface-raised); }
  .mint-row .mint-avatar { width: 40px; height: 40px; font-size: 16px; }
  .mint-row-info { flex: 1; }
  .mint-row-name {
    font-size: 15px;
    font-weight: 500;
    letter-spacing: -0.1px;
    margin-bottom: 1px;
  }
  .mint-row-status { font-size: 12px; color: var(--text-tertiary); }

  .mint-check {
    width: 22px;
    height: 22px;
    border-radius: 6px;
    border: 2px solid rgba(255,255,255,0.15);
    display: flex;
    align-items: center;
    justify-content: center;
    transition: all 0.2s;
    flex-shrink: 0;
  }
  .mint-check.checked { background: var(--green); border-color: var(--green); }
  .mint-check svg {
    width: 14px;
    height: 14px;
    stroke: #000;
    stroke-width: 2.5;
    opacity: 0;
    transition: opacity 0.2s;
  }
  .mint-check.checked svg { opacity: 1; }

  .hint {
    text-align: center;
    font-size: 12px;
    color: var(--text-tertiary);
    margin-bottom: 24px;
  }

  .add-mint {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 8px;
    padding: 14px;
    border-radius: var(--radius-sm);
    border: 1px dashed rgba(255,255,255,0.12);
    color: var(--text-secondary);
    font-size: 14px;
    font-weight: 500;
    cursor: pointer;
    transition: all 0.2s;
    margin-bottom: 20px;
  }
  .add-mint:hover {
    border-color: var(--green);
    color: var(--green);
    background: var(--green-soft);
  }

  .continue-btn {
    width: 100%;
    padding: 16px;
    border-radius: 100px;
    background: var(--text-primary);
    color: var(--bg);
    font-size: 16px;
    font-weight: 600;
    border: none;
    letter-spacing: -0.2px;
  }

  .stagger-1 { animation: slideUp 0.4s ease-out 0.1s both; }
  .stagger-2 { animation: slideUp 0.4s ease-out 0.15s both; }
  .stagger-3 { animation: slideUp 0.4s ease-out 0.2s both; }
  .stagger-4 { animation: slideUp 0.4s ease-out 0.25s both; }
  .stagger-5 { animation: slideUp 0.4s ease-out 0.3s both; }
  .stagger-6 { animation: slideUp 0.4s ease-out 0.35s both; }
  .stagger-7 { animation: slideUp 0.4s ease-out 0.4s both; }
  @keyframes slideUp {
    from { opacity: 0; transform: translateY(16px); }
    to { opacity: 1; transform: translateY(0); }
  }
</style>
</head>
<body>
<div class="phone-frame">
  <div class="nav">
    <div class="nav-back">
      <svg fill="none" viewBox="0 0 24 24" stroke-width="2" stroke="currentColor">
        <path stroke-linecap="round" stroke-linejoin="round" d="M15.75 19.5L8.25 12l7.5-7.5" />
      </svg>
    </div>
    <span class="nav-title">Your Mints</span>
  </div>

  <div class="content">
    <div class="default-hero stagger-1">
      <div class="hero-bolt">
        <svg viewBox="0 0 24 24"><path d="M13 2L3 14h9l-1 10 10-12h-9l1-10z"/></svg>
      </div>
      <div class="default-label">Default Mint</div>
      <div class="default-mint-row">
        <div class="mint-avatar chorus">+</div>
        <div class="default-mint-info">
          <div class="default-mint-name">Chorus OFF Mint</div>
          <div class="default-mint-desc">Holds your bitcoin</div>
        </div>
        <div class="default-badge">Default</div>
      </div>
      <div class="hero-helper">
        Withdraw to your own wallet anytime, or set a payout threshold to do it automatically.
      </div>
    </div>

    <div class="stagger-3">
      <div class="section-header">
        <span class="section-title">Accept From</span>
        <span class="section-count">2 mints</span>
      </div>
    </div>

    <div class="mint-list">
      <div class="mint-row stagger-4">
        <div class="mint-avatar coinos">&#9673;</div>
        <div class="mint-row-info">
          <div class="mint-row-name">Coinos</div>
          <div class="mint-row-status">Accepting payments</div>
        </div>
        <div class="mint-check checked">
          <svg fill="none" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" d="M5 13l4 4L19 7" /></svg>
        </div>
      </div>

      <div class="mint-row stagger-5">
        <div class="mint-avatar minibits">M</div>
        <div class="mint-row-info">
          <div class="mint-row-name">Minibits mint</div>
          <div class="mint-row-status">Accepting payments</div>
        </div>
        <div class="mint-check checked">
          <svg fill="none" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" d="M5 13l4 4L19 7" /></svg>
        </div>
      </div>

      <div class="mint-row stagger-6">
        <div class="mint-avatar cuba">CB</div>
        <div class="mint-row-info">
          <div class="mint-row-name">Mint Cuba Bitcoin</div>
          <div class="mint-row-status" style="color: var(--text-tertiary);">Not accepting</div>
        </div>
        <div class="mint-check">
          <svg fill="none" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" d="M5 13l4 4L19 7" /></svg>
        </div>
      </div>
    </div>

    <div class="hint stagger-7">Long press a mint to set as default</div>

    <div class="add-mint stagger-7">
      <svg fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
        <path stroke-linecap="round" stroke-linejoin="round" d="M12 4.5v15m7.5-7.5h-15" />
      </svg>
      Add New Mint
    </div>

    <button class="continue-btn stagger-7">Continue</button>
  </div>
</div>
</body>
</html>
```

---

## WHAT TO REMOVE APP-WIDE

- Star icons for setting default mint
- Divider lines under nav bar titles
- Extra/applied background colors on screen content areas
- Inconsistent inline/custom text styles that should use shared components
- One-off button styles that don't match the primary/secondary button specs
- Centered multi-line text in cards (left-align instead)

## WHAT TO ADD/ENSURE APP-WIDE

- Shared section header component/style used everywhere
- Shared row component with consistent title/subtitle typography
- Shared primary button (pill-shaped, full width)
- Shared secondary button (dashed border, green press state)
- Shared checkbox component (22dp, 6dp radius, green checked)
- Shared nav bar (centered title, circular back button, no divider)
- Staggered entrance animations on every screen
- Consistent spacing values across all screens
- Both light mode and dark mode verified on every screen
