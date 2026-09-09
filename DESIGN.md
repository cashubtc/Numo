---
name: Numo
description: Android point-of-sale for bitcoin — tap-to-pay Cashu ecash, calm as a card terminal.
colors:
  terminal-navy: "#0A2540"
  settled-green: "#00C244"
  settled-green-pressed: "#00A03A"
  fluorescent-signal: "#5EFFC2"
  brand-ink: "#0B1215"
  ink: "#111111"
  ink-secondary: "#6F6F73"
  ink-tertiary: "#9B9B9F"
  surface: "#FFFFFF"
  surface-sunken: "#F6F7F8"
  surface-secondary: "#F1F2F6"
  divider: "#E5E5EA"
  chip-border: "#E1E1E5"
  link-purple: "#8420F4"
  alert-red: "#FF4B4B"
  warning-orange: "#FF9500"
  bitcoin-orange: "#F7931A"
typography:
  display-hero:
    fontFamily: "sans-serif-medium (Roboto Medium)"
    fontSize: "64sp"
    fontWeight: 500
    lineHeight: "72sp"
    letterSpacing: "-0.02em"
  display-amount:
    fontFamily: "sans-serif-medium (Roboto Medium)"
    fontSize: "48sp"
    fontWeight: 500
    lineHeight: "56sp"
    letterSpacing: "-0.02em"
  headline:
    fontFamily: "sans-serif-medium (Roboto Medium)"
    fontSize: "24sp"
    fontWeight: 500
    lineHeight: "30sp"
  title:
    fontFamily: "sans-serif-medium (Roboto Medium)"
    fontSize: "17sp"
    fontWeight: 500
    lineHeight: "22sp"
    letterSpacing: "-0.012em"
  body:
    fontFamily: "sans-serif (Roboto)"
    fontSize: "15sp"
    fontWeight: 400
    lineHeight: "20sp"
  label:
    fontFamily: "sans-serif-medium (Roboto Medium)"
    fontSize: "16sp"
    fontWeight: 500
    lineHeight: "22sp"
    letterSpacing: "-0.012em"
  overline:
    fontFamily: "sans-serif-medium (Roboto Medium)"
    fontSize: "11sp"
    fontWeight: 500
    lineHeight: "14sp"
    letterSpacing: "0.06em"
rounded:
  small: "8dp"
  input: "12dp"
  card: "16dp"
  chip: "18dp"
  pill: "26dp"
  sheet: "28dp"
  round: "100dp"
spacing:
  xs: "4dp"
  s: "8dp"
  m: "12dp"
  l: "16dp"
  xl: "24dp"
  2xl: "32dp"
  3xl: "48dp"
components:
  button-primary:
    backgroundColor: "{colors.brand-ink}"
    textColor: "#FFFFFF"
    rounded: "{rounded.round}"
    height: "52dp"
  button-primary-pressed:
    backgroundColor: "#1A2529"
  button-pill-dark:
    backgroundColor: "#000000"
    textColor: "#FFFFFF"
    rounded: "{rounded.pill}"
    height: "52dp"
  button-secondary:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink-secondary}"
    rounded: "{rounded.pill}"
    height: "48dp"
  card:
    backgroundColor: "{colors.surface}"
    rounded: "{rounded.card}"
  input:
    backgroundColor: "{colors.surface}"
    rounded: "{rounded.input}"
---

# Design System: Numo

## Overview

**Creative North Star: "The Navy Terminal"**

Numo is a payment terminal that happens to be a phone. The system is calm, confident, and exact: deep Terminal Navy is the machine's housing — the splash, the onboarding backdrop, the brand's ground — while the working screens are clean white counters where amounts, not decoration, carry the drama. Green is not a theme color; it is a signal that money moved. The interface never fidgets, never celebrates itself, and spends its one flourish (a fluorescent first-run accent) exactly once.

Density is merchant-paced: large tap targets (52dp buttons, 72dp keypad keys), generous 4dp-grid spacing, and numerals scaled to be read at arm's length across a counter. Confirmed rejections: no crypto-flash (neon gradients, coin or rocket imagery, trading-app darkness as decoration) and no fintech-generic (interchangeable gradient-blob SaaS branding). Dark theme is real — roughly 120 hand-tuned night tokens — not an inversion.

**Key Characteristics:**
- Navy housing, white working surfaces, green only when value moves
- Flat by conviction: strokes and tint steps, not shadows
- One family (system Roboto), structure in medium, reading in regular
- Pill-shaped actions, 16dp cards, exact numerals
- Precise and unhurried motion; the payment moment owns the emphasis

## Colors

A navy-anchored neutral system where green is earned, not ambient.

### Primary
- **Terminal Navy** (#0A2540): the brand ground — splash screen, onboarding backdrop, section headers, the hero gradient (135°, #122E48 → navy). It frames the product; it is not a content surface.
- **Settled Green** (#00C244): the color of money confirmed — success states, sat amounts, "Sent" labels, the payment checkmark. Pressed variant #00A03A.
- **Brand Ink** (#0B1215): near-black used as the fill of primary action buttons. Beware the historical token name: `color_primary_green` resolves to this ink, not to green — prefer `color_brand_foreground` in new code.

### Secondary
- **Fluorescent Signal** (#5EFFC2): the rare first-run accent — onboarding highlights, seed-input focus, mint-discovery hero strokes (1dp at 10% alpha). It appears nowhere in daily operation.

### Tertiary
- **Link Purple** (#8420F4): inline links. **Alert Red** (#FF4B4B): errors and destructive actions. **Warning Orange** (#FF9500): cautions. **Bitcoin Orange** (#F7931A): the protocol's own color, used sparingly for bitcoin identity.

### Neutral
- **Ink** (#111111): primary text and the compact black button fill. Night: #FFFFFF.
- **Ink Secondary** (#6F6F73) / **Ink Tertiary** (#9B9B9F): supporting text ramp. Night: #B0B0B3 / #808084.
- **Surface** (#FFFFFF): cards, sheets, the working screens. Night: #0B1215 window, #1A1F23 cards.
- **Surface Sunken** (#F6F7F8) and **Surface Secondary** (#F1F2F6): recessed groupings and secondary fills.
- **Divider** (#E5E5EA) at 0.5–1dp and **Chip Border** (#E1E1E5) at 1dp: the system's depth vocabulary.

### Named Rules
**The Green Is Money Rule.** Settled Green appears only where value moves or settles — amounts, confirmations, success. It is never a decorative theme color, never a background wash.
**The Fluorescent Once Rule.** Fluorescent Signal (#5EFFC2) belongs to first-run moments only. If a daily-use screen wants it, the answer is no.

## Typography

**Display Font:** Roboto Medium (`sans-serif-medium`)
**Body Font:** Roboto (`sans-serif`)
**Label/Mono Font:** platform `monospace`, 14sp, for addresses, tokens, and technical strings

**Character:** One system family doing every job — calm, native, exact. Structure and numbers sit in medium; reading text in regular; the keypad alone uses `sans-serif-light` at display size for its airy, hardware-key feel.

### Hierarchy
The scale runs ~1.33: 11 · 13 · 14 · 15 · 16 · 17 · 18 · 24 · 32 · 48 · 64.

- **Display Hero** (medium, 64sp/72, −0.02em): the single largest statement, reserved for hero amounts.
- **Display Amount** (medium, 48sp/56, −0.02em): the POS amount — the biggest thing on the screen because it is the point of the screen.
- **Headline** (medium, 24sp/30): screen titles. Onboarding display runs 32sp/38 at −0.02em.
- **Title** (medium, 17sp/22, −0.012em): dialog and section titles.
- **Body** (regular, 15sp/20): reading text, always in Ink Secondary.
- **Label/Button** (medium, 16sp/22, −0.012em): actions, never all-caps.
- **Overline/Section Header** (medium, 11–13sp, +0.06em, all-caps): the one sanctioned caps role, tinted Terminal Navy.

### Named Rules
**The Tracking Slope Rule.** Tracking tightens as size grows (−0.02em at display) and loosens only on small all-caps labels (+0.06em). Body sits at 0; on dark grounds it may take +0.01em.
**The Amount Is the Headline Rule.** On any payment surface the amount outranks every word. Labels around it are quiet, secondary, and smaller by at least two scale steps.

## Layout

A 4dp base grid (`space_xs 4` through `space_5xl 96`), 20dp horizontal / 16dp vertical screen margins, 64dp list rows with 12dp-radius ripple, 56dp bars, and 28dp section gaps. Content is edge-to-edge behind system bars with insets applied as padding; onboarding constrains content to 600dp on wide windows. Everything is sized for one-handed counter use: primary actions live at the bottom within thumb reach, and touch targets never drop below 44dp.

## Elevation & Depth

**Flat by conviction.** Surfaces are separated by 1dp strokes (Chip Border, Divider), 0.5dp hairline dividers, and surface-tint steps (Surface → Sunken → Secondary) — not shadows. Buttons explicitly zero their elevation and state-list animators. The only surfaces that float are the ones Android says must: bottom sheets (16dp) and snackbars (8dp).

### Named Rules
**The Flat Counter Rule.** No new shadows. If a surface needs separation, reach for a 1dp stroke or a tint step; if it needs to float, it should probably be a bottom sheet.

## Shapes

A pill-and-card language: fully rounded pills (26dp on 52dp heights; 100dp forced-round on the green-named primary) for actions, 16dp corners for cards and content containers, 12dp for inputs and row ripples, 28dp for dialogs, onboarding buttons, and bottom-sheet tops. PIN keypad keys are true 80dp ovals. Strokes are always 1dp or hairline. The rounder the corner, the more pressable the element: pills act, cards hold, inputs receive.

## Components

Component character: **precise and unhurried** — generous targets, exact type, presses acknowledged with a 0.97 scale or a tint shift, never a bounce.

### Buttons
- **Shape:** pill (26dp radius at 52dp height; 100dp on the primary), text 16sp medium, never all-caps, insets zeroed so heights are honest.
- **Primary:** Brand Ink fill (#0B1215), white text, pressed #1A2529.
- **Compact Primary:** Ink fill at 14dp radius with an on-dark ripple, for dense contexts.
- **Secondary:** white pill with 1dp Chip Border stroke, Ink Secondary text.
- **Charge (POS):** translucent white (#56FFFFFF) over the themed POS screen, 18sp bold — the one oversized label in the app.
- **Onboarding:** white pill on navy, 28dp radius, 56dp height, navy text; tertiary is transparent with a white-10 press.
- **Dialog set:** confirm = Ink fill; cancel = white with border; destructive = Alert Red fill; all 28dp.

### Chips
- **Style:** 36–40dp height, 18dp radius, 14sp medium, 1dp Chip Border on white.
- **Status pills:** tinted grounds — success #E8F5E9, pending #FFF3E0, error #FFEBEE — with matching text.

### Cards / Containers
- **Corner Style:** 16dp (12dp for compact variants).
- **Background:** Surface; recessed groupings use Surface Sunken/Secondary.
- **Shadow Strategy:** none — flat with strokes (see Elevation).
- **Border:** 1dp Divider on QR and outlined cards; semantic cards tint their ground (info/warning/success/error).
- **Internal Padding:** 16dp, on the 4dp grid.

### Inputs / Fields
- **Style:** 12dp radius, Surface ground, 1dp stroke; seed inputs on navy use dark fills.
- **Focus:** stroke swaps to the accent (Fluorescent Signal during first-run); no glow.

### Navigation
- **Top bar:** 56dp, title in Title role. Top-level screens (settings, history) close with an X icon; drill-in screens use the back chevron. System Back always works.
- **Bottom sheets:** 28dp top corners, 36×4dp handle, edge-to-edge.

### The Keypad (signature)
72dp-minimum borderless keys — no fills, no borders, just a bare ripple — with 28sp `sans-serif-light` digits in Ink Secondary (white on themed POS screens). It reads as engraved hardware, not buttons on glass.

### The Hero Gradient (signature)
135° navy gradient (#122E48 → #0A2540) at 20dp radius with a 1dp Fluorescent Signal stroke at 10% alpha — the one place the brand glows, used for first-run hero panels.

### Rolling Amounts (signature)
Amounts animate by place-aligned digit rolls (`RollingAmountPainter`): unchanged digits stay put, changed columns roll through the baseline rightmost-first like an odometer, with tabular numerals throughout.

## Do's and Don'ts

### Do:
- **Do** reserve Settled Green (#00C244) for money movement and confirmation; the amount and its sat line are the only routinely green text.
- **Do** keep every action a pill (26–28dp radius, 52dp+ height) with 16sp medium sentence-case labels.
- **Do** separate surfaces with 1dp strokes and tint steps on the 4dp grid; keep elevation at 0.
- **Do** let the amount be the largest element on any payment surface (48sp+, medium, −0.02em).
- **Do** use system Roboto through the `Text.*` role scale; new text picks a role, never a hand-tuned size.
- **Do** design both themes — night has hand-tuned values for every token; check `values-night` before adding a color.

### Don't:
- **Don't** add shadows, glows, or elevation to buttons, cards, or rows; sheets and snackbars are the only floating surfaces.
- **Don't** use Fluorescent Signal (#5EFFC2) outside first-run/onboarding moments.
- **Don't** ship crypto-flash (neon gradients, coins, rockets) or fintech-generic gradient-blob styling; Numo looks like a merchant tool.
- **Don't** frame funds as resting in Numo — visuals always show money on its way to the merchant's wallet (terminal, never custodian).
- **Don't** trust `color_primary_green` to be green — it resolves to Brand Ink (#0B1215); use `color_brand_foreground` and the semantic tokens.
- **Don't** use all-caps outside the Overline/Section Header role, and never on buttons.
