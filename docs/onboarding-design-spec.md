# Numo Onboarding Design Spec

## Color Palette

| Token | Value | Usage |
|-------|-------|-------|
| `numo_navy` | `#0A2540` | Cards, overlays, deepest surfaces |
| `numo_navy_light` | `#0E3050` | Primary screen background |
| `numo_fluorescent_green` | `#5EFFC2` | Headers, accent, CTAs, success states |
| White 100% | `#FFFFFF` | Primary body text, button text |
| White 60% | `#99FFFFFF` | Subtitles, secondary descriptions |
| White 45% | `#73FFFFFF` | Tertiary text, hints, placeholders |
| White 30% | `#4DFFFFFF` | Disabled text, chevrons, dividers |
| White 15% | `#26FFFFFF` | Subtle borders, input strokes |
| White 10% | `#1AFFFFFF` | Card/surface overlays, dividers |
| Green 20% | `#335EFFC2` | Success bg tints, icon containers |

## Typography

| Style | Size | Weight | Color | Usage |
|-------|------|--------|-------|-------|
| Hero | 42sp | Black (900) | Green, uppercase, letterSpacing -0.035 | Explainer slide titles |
| Title | 28sp | Medium (500) | White | Screen titles |
| Heading | 17sp | Medium (500) | White | Toolbar titles, section headers |
| Body | 16sp | Regular (400) | White 60% | Descriptions, instructions |
| Row Title | 16sp | Medium (500) | White | List item titles |
| Row Subtitle | 14sp | Regular (400) | White 45% | List item descriptions |
| Caption | 13sp | Regular (400) | White 45% | Hints, terms |
| Overline | 12sp | Medium (500) | White 45%, uppercase | Section headers |

## Surfaces

- **Screen background**: `numo_navy_light` (`#0E3050`)
- **Card/elevated**: `numo_navy` (`#0A2540`) or White 10% overlay
- **Input fields**: White 8% bg, White 12% stroke, white text, green focus border
- **System bars**: Match screen background, light icons OFF

## Buttons

- **Primary**: Green bg (`#5EFFC2`), navy text (`#0A2540`), 56dp height, 28dp radius
- **Secondary/Outlined**: Transparent bg, White 15% stroke, white text, 44dp height, 22dp radius
- **Disabled**: Same bg at 50% opacity

## Icons

- **Navigation** (back arrows): White 60%
- **Row icons**: White 50% (stroke style)
- **Decorative**: White 30% or Green 30%
- **Success/status**: Green (`#5EFFC2`)

## Animations

- **Screen transitions**: Fade + slide up (300ms, decelerate)
- **Explainer open**: Sheet slides up (450ms, cubic-bezier(0.32, 0.72, 0, 1))
- **Teaser bounce**: OvershootInterpolator on translationY (500ms)
- **Chevron pulse**: Alpha 0.12 to 0.7, 1.8s, staggered 200ms
