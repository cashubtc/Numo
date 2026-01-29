# Emil Kowalski's Animation Principles

Emil Kowalski is a Design Engineer at Linear (previously Vercel). Creator of Sonner, Vaul, and the "Animations on the Web" course. His approach emphasizes **restraint, speed, and purposeful motion**.

---

## Core Philosophy: Restraint & Purpose

Emil's defining contribution to motion design thinking is knowing **when NOT to animate**.

> "The goal is not to animate for animation's sake, it's to build great user interfaces."

### The Frequency Rule

**Animation appropriateness depends on interaction frequency:**

| Frequency | Recommendation |
|-----------|----------------|
| Rare (monthly) | Delightful, morphing animations welcome |
| Occasional (daily) | Subtle, fast animations |
| Frequent (100s/day) | No animation or instant transitions |
| Keyboard-initiated | Never animate |

**The Raycast example**: A tool used constantly throughout the day benefits from zero animation. Users with clear goals "don't expect to be delighted" and prioritize frictionless workflow.

### Speed is Non-Negotiable

> "UI animations should generally stay under 300ms."

A 180ms animation feels more responsive than 400ms. Speed creates perceived performance. When in doubt, go faster.

---

## The 7 Practical Animation Tips

### 1. Scale Your Buttons
Add subtle scale-down on press for immediate feedback:
```css
button:active {
  transform: scale(0.97);
}
```

### 2. Don't Animate from scale(0)
Animating from `scale(0)` creates unnatural motion. Start from `scale(0.9)` or higher for elegant, gentle movement:
```jsx
// BAD
initial={{ scale: 0 }}

// GOOD
initial={{ scale: 0.9, opacity: 0 }}
```

### 3. Tooltip Delay Patterns
First tooltip: delay + animation. Subsequent tooltips in same group: instant (no delay, no animation):
```css
[data-instant] {
  transition-duration: 0ms;
}
```

### 4. Custom Easing is Essential
> "Easing is the most important part of any animation. It can make a bad animation feel great."

Built-in CSS easing (`ease`, `ease-in-out`) lacks strength. Use custom Bézier curves:
- Resources: easing.dev, easings.co
- `ease-out` for entering elements
- `ease-in-out` for moving existing elements
- Springs for interactive elements

### 5. Origin-Aware Animations
Animations should originate from their logical source:
```css
/* Dropdown from button should expand from button, not center */
.dropdown {
  transform-origin: top center; /* or use CSS variables from Radix/Base UI */
}
```

Base UI: `--transform-origin`
Radix UI: `--radix-dropdown-menu-content-transform-origin`

### 6. Keep Animations Fast
- Under 300ms for UI interactions
- 180ms feels more responsive than 400ms
- Remove animations entirely for high-frequency interactions

### 7. Use Blur When Nothing Else Works
Add `filter: blur(2px)` to mask imperfections during state transitions:
```css
.transitioning {
  filter: blur(2px);
}
```
Blur bridges the gap between old and new states, creating smoother perceived motion.

---

## Clip-Path Mastery

Emil advocates for `clip-path` as a powerful animation primitive.

### Why clip-path?
- Hardware-accelerated
- No layout shifts
- No additional DOM elements needed
- Smoother than width/height animations

### Image Reveal Effect
```css
.reveal {
  clip-path: inset(0 0 100% 0);
  animation: reveal 1s forwards cubic-bezier(0.77, 0, 0.175, 1);
}

@keyframes reveal {
  to { clip-path: inset(0 0 0 0); }
}
```

### Tab Transitions with clip-path
Duplicate tab lists with different styling. Animate overlay's clip-path to reveal only active tab—creates smooth color transitions that blend naturally with movement.

### Scroll-Driven with clip-path
```javascript
const clipPathY = useTransform(scrollYProgress, [0, 1], ["100%", "0%"]);
const motionClipPath = useMotionTemplate`inset(0 0 ${clipPathY} 0)`;
```

---

## Spring Physics

Emil uses spring animations extensively with three key parameters:

| Parameter | Effect |
|-----------|--------|
| **Stiffness** | How quickly the spring reaches target (higher = faster) |
| **Damping** | How quickly oscillations settle (higher = less bounce) |
| **Mass** | Weight of the object (higher = more momentum) |

### Spring for Mouse Position
Use `useSpring` for mouse-position-driven components:
```javascript
const springConfig = { stiffness: 300, damping: 30 };
const x = useSpring(mouseX, springConfig);
const y = useSpring(mouseY, springConfig);
```

This interpolates value changes with spring behavior rather than instant updates—nothing in the real world changes instantly.

---

## Interruptibility

Great animations can be interrupted mid-play and respond naturally:

- Framer Motion supports interruption natively
- CSS transitions allow smooth interruption before completion
- Enable mid-animation state changes for responsive feel

Test by clicking rapidly—animations should blend smoothly, not queue up.

---

## Performance Principles

### Hardware Acceleration
- Animate `transform` and `opacity` only (composite rendering)
- Avoid `padding`/`margin` changes (trigger layout, paint, composite)
- CSS and WAAPI animations remain smooth regardless of main thread load

### The 60fps Target
- Minimum 60 frames per second
- Test with DevTools Performance panel
- Watch for dropped frames during scroll or interaction

### Shared Layout Animation Gotcha
Framer Motion's shared layout animations can drop frames when browser is busy. For critical animations, CSS solutions move computation off CPU.

---

## When to Use Each Approach

| Context | Approach |
|---------|----------|
| Keyboard shortcuts | No animation |
| High-frequency tool | Minimal or no animation |
| Daily-use feature | Fast, subtle animation (180-250ms) |
| Onboarding/first-time | Delightful animations welcome |
| Marketing/landing page | Full creative expression |
| Banking/serious UI | Minimal, functional motion |
| Playful brand | Bouncy, elastic easing appropriate |

---

## Implementation Patterns from Sonner & Vaul

Emil's open-source component libraries (Sonner for toasts, Vaul for drawers) reveal his philosophy in actual code.

### CSS Transitions Over Keyframes

Keyframes can't be interrupted mid-animation. When users rapidly trigger actions, elements "jump" rather than smoothly retargeting. Use CSS transitions with state-driven classes:

```jsx
// After mount, set state to trigger transition
useEffect(() => {
  setMounted(true);
}, []);

// CSS handles the actual animation
.toast {
  transform: translateY(100%);
  transition: transform 400ms ease;
}
.toast.mounted {
  transform: translateY(0);
}
```

### Direct Style Updates for Performance

CSS variables cause style recalculation across all child elements. For frequent updates (drag operations), update styles directly on the element:

```javascript
// BAD: CSS variable (expensive recalculation)
element.style.setProperty('--drag-y', `${y}px`);

// GOOD: Direct style (no cascade)
element.style.transform = `translateY(${y}px)`;
```

### Match Native Motion Curves

Vaul uses `cubic-bezier(0.32, 0.72, 0, 1)` derived from Ionic Framework to match iOS sheet animations. Duration: 500ms. Familiarity creates perceived quality.

### Momentum-Based Dismissal

Don't require distance thresholds—use velocity (distance / time):

```javascript
const velocity = dragDistance / elapsedTime;
if (velocity > 0.11) {
  dismiss(); // Fast, short gestures work
}
```

Threshold of `0.11` was found through iteration, not calculation.

### Damping for Natural Motion

> "Things in real life don't suddenly stop, they slow down first."

When dragging past boundaries, reduce movement progressively rather than stopping abruptly.

### Multi-Touch Protection

Ignore additional touches after the first until release. Prevents position jumps that feel broken.

### Pointer Capture for Drag UX

Call `setPointerCapture()` during drags so tracking continues even when pointer leaves the element boundary.

### Invisible Quality Details

- **Document visibility**: Pause timers when tab is inactive
- **Hover gap-filling**: `:after` pseudo-elements bridge spacing between stacked elements
- **Scroll-to-drag timeout**: 100ms delay prevents accidental dismissal from momentum

### Sonner Defaults

| Setting | Value | Rationale |
|---------|-------|-----------|
| Duration | 4000ms | Long enough to read, short enough to not annoy |
| Animation | 400ms ease | Smooth but snappy |
| Position | bottom-right | Convention, out of primary content |
| Dismissible | true | User control by default |

### Vaul Defaults

| Setting | Value | Rationale |
|---------|-------|-----------|
| Duration | 500ms | Match iOS sheet feel |
| Easing | cubic-bezier(0.32, 0.72, 0, 1) | iOS-native curve |
| Modal | true | Focus management, overlay |
| Direction | bottom | Convention for mobile sheets |

---

## The Core Philosophy

> "When a feature functions as you assume it should, you proceed without giving it a second thought, which is our goal."

Every detail—from multi-touch handling to damping to pointer capture—serves **invisible quality**. Users shouldn't notice polished interactions; they should just feel right.

---

## Emil vs. Jakub vs. Jhey

| Aspect | Emil | Jakub | Jhey |
|--------|------|-------|------|
| **Focus** | Restraint & speed | Subtle polish | Playful experimentation |
| **Key question** | "Should this animate?" | "Is this subtle enough?" | "What could this become?" |
| **Signature technique** | Frequency-based decisions | Blur + opacity + translateY | CSS custom properties |
| **Ideal context** | High-frequency tools | Production polish | Learning & exploration |

**Synthesis**: Use Emil's framework to decide IF you should animate. Use Jakub's techniques for HOW to animate in production. Use Jhey's approach for learning and experimentation.
