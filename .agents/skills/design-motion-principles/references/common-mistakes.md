# Common Mistakes

---

## From Emil's Perspective (Purposeful Restraint)

- **Animating high-frequency interactions** — If users trigger this 100s of times daily, remove the animation
- **Animating keyboard-initiated actions** — Keyboard shortcuts should NEVER animate
- **Animations over 300ms** — UI animations should be under 300ms; 180ms feels more responsive than 400ms
- **Animating from scale(0)** — Start from `scale(0.9)` or higher for natural motion
- **Same tooltip behavior everywhere** — First tooltip: delayed + animated. Subsequent: instant
- **Using default CSS easing** — Built-in `ease` and `ease-in-out` lack strength; use custom curves
- **Ignoring transform-origin** — Dropdowns should expand from their trigger, not center
- **Expecting delight in productivity tools** — Users of high-frequency tools prioritize speed over delight
- **Using keyframes for interruptible animations** — Keyframes can't retarget mid-flight; use CSS transitions with state
- **CSS variables for frequent updates** — Causes expensive style recalculation; update styles directly on element
- **Distance thresholds for dismissal** — Use velocity (distance/time) instead; fast short gestures should work
- **Abrupt boundary stops** — Use damping; things slow down before stopping in real life

---

## From Jakub's Perspective (Production Polish)

- **Making enter and exit animations equally prominent** — Exits should be subtler
- **Using solid borders when shadows would adapt better** — Especially on varied backgrounds
- **Forgetting optical alignment** — Buttons with icons, play buttons, asymmetric shapes
- **Over-animating** — If users notice the animation itself, it's too much
- **Using the same animation everywhere** — Context should drive timing and easing choices
- **Ignoring hover state transitions** — Even small transitions (150-200ms) feel more polished than instant changes

---

## From Jhey's Perspective (Creative Learning)

- **Filtering ideas based on "usefulness" too early** — Make first, judge later
- **Not documenting random creative sparks** — Keep notebooks everywhere, including by your bed
- **Thinking CSS art is useless** — It teaches real skills (clip-path, layering, complex shapes)
- **Focusing on "How do I learn X?" instead of "How do I make Y?"** — Let ideas drive learning
- **Following tutorials without experimenting** — Tutorials teach techniques; experimentation teaches problem-solving
- **Giving up when something doesn't work** — The struggle is where learning happens

---

## General Motion Design Mistakes

- **Animating layout-triggering properties** (width, height, top, left) — Use transform instead
- **No animation at all** — Instant state changes feel broken to modern users
- **Same duration for all animations** — Smaller elements should animate faster
- **Forgetting `prefers-reduced-motion`** — Not optional

*Note: Duration is designer-dependent. Emil prefers under 300ms for productivity tools. Jakub and Jhey may use longer durations when polish or effect warrants it.*

---

## Red Flags in Code Review

Watch for these patterns:

```jsx
// BAD: Animating layout properties
animate={{ width: 200, height: 100 }}

// GOOD: Use transform
animate={{ scale: 1.2 }}
```

```jsx
// BAD: Same animation for enter and exit
initial={{ opacity: 0, y: 20 }}
exit={{ opacity: 0, y: 20 }}

// GOOD: Subtler exit
initial={{ opacity: 0, y: 20 }}
exit={{ opacity: 0, y: -8 }}
```

```css
/* BAD: No reduced motion support */
.animated { animation: bounce 1s infinite; }

/* GOOD: Respects user preference */
@media (prefers-reduced-motion: no-preference) {
  .animated { animation: bounce 1s infinite; }
}
```

```css
/* BAD: will-change everywhere */
* { will-change: transform; }

/* GOOD: Targeted will-change */
.animated-button { will-change: transform, opacity; }
```

```jsx
// BAD: Animating from scale(0) (Emil)
initial={{ scale: 0 }}
animate={{ scale: 1 }}

// GOOD: Start from higher scale
initial={{ scale: 0.9, opacity: 0 }}
animate={{ scale: 1, opacity: 1 }}
```

```jsx
// Per Emil: Too slow for productivity UI
transition={{ duration: 0.4 }}

// Per Emil: Fast, snappy (but Jakub/Jhey might use 0.4 for polish)
transition={{ duration: 0.18 }}
```

```css
/* BAD: Dropdown expanding from center (Emil) */
.dropdown {
  transform-origin: center;
}

/* GOOD: Origin-aware animation */
.dropdown {
  transform-origin: top center;
}
```

```css
/* BAD: Keyframes can't be interrupted (Emil) */
@keyframes slideIn {
  from { transform: translateY(100%); }
  to { transform: translateY(0); }
}
.toast { animation: slideIn 400ms ease; }

/* GOOD: Transitions can retarget mid-flight */
.toast {
  transform: translateY(100%);
  transition: transform 400ms ease;
}
.toast.mounted {
  transform: translateY(0);
}
```

```javascript
// BAD: CSS variables cause cascade recalc (Emil)
element.style.setProperty('--drag-y', `${y}px`);

// GOOD: Direct style update
element.style.transform = `translateY(${y}px)`;
```

```javascript
// BAD: Distance threshold for dismissal (Emil)
if (dragDistance > 100) dismiss();

// GOOD: Velocity-based (fast short gestures work)
const velocity = dragDistance / elapsedTime;
if (velocity > 0.11) dismiss();
```
