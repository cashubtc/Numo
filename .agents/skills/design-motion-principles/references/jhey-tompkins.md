# Jhey Tompkins' Animation Principles

Jhey Tompkins (@jh3yy) is a design engineer known for pushing the boundaries of CSS and creative coding. His approach emphasizes **playful experimentation**—learning through building whimsical projects where the joy of creation drives skill development.

---

## Core Philosophy: Learn Through Play

> "I went from 'I want to learn X, so how do I fit it into Y' to 'I want to make Y, can I learn X to do it?'"

**The motivation should be making something cool—learning is a happy side effect.**

### Core Beliefs

- **No idea is a bad idea** — Document every spark, however weird (toadstools, Peter Griffin blinds, bread array slice/splice cartoon)
- **Don't ask "Why?" or "Is this practical?"** — Make what brings you joy first
- **"Useless" demos teach real skills** — CSS art teaches clip-path mastery, border-radius tricks, stacking contexts
- **Lateral learning** — Building diverse demos trains you to switch contexts and rise to challenges
- **You'll never have time to make everything** — And that's okay. The act of documenting ideas matters.

### The Playfulness Philosophy

Playfulness in code supercharges learning. The fact you're learning new skills is a bonus, not the goal. Work on ideas that spark joy for you.

**Keep notebooks everywhere** — including by your bed. Creative sparks happen at random times.

---

## When to Apply Jhey's Mindset

- Learning new techniques
- Personal projects and experiments
- When you're stuck in a creative rut
- Building your portfolio or demos
- Exploring what's possible with new CSS features
- When tutorials aren't clicking—try building something weird instead

---

## The Golden Rule

> "The best animation is that which goes unnoticed."

Jhey references this as a saying that has always stuck with him. Even in playful contexts, effective motion:
- Enhances the experience without demanding attention
- Feels natural and expected
- Serves a functional purpose
- Doesn't fatigue users on repeated interactions

---

## Easing & Timing

### Duration Impacts Naturalness

> "Duration is all about timing, and timing has a big impact on the movement's naturalness."

### Easing Selection Guidelines

Each easing curve communicates something to the viewer. **Context matters more than rules.**

| Easing | Feel | Good For |
|--------|------|----------|
| `ease-out` | Fast start, gentle stop | Elements entering view (arriving) |
| `ease-in` | Gentle start, fast exit | Elements leaving view (departing) |
| `ease-in-out` | Gentle both ends | Elements changing state while visible |
| `linear` | Constant speed | Continuous loops, progress indicators |
| `spring` | Natural deceleration | Interactive elements, professional UI |

### The Context Rule

> "You wouldn't use 'Elastic' for a bank's website, but it might work perfectly for an energetic site for children."

Brand personality should drive easing choices:
- Playful brand → bouncy, elastic easing
- Professional brand → subtle springs or ease-out

**When NOT to use bouncy/elastic easing**:
- Professional/enterprise applications
- Frequently repeated interactions (gets tiresome)
- Error states or serious UI
- When users need to complete tasks quickly

---

## The linear() Function

CSS `linear()` enables bounce, elastic, and spring effects in pure CSS:

```css
:root {
  --bounce-easing: linear(
    0, 0.004, 0.016, 0.035, 0.063, 0.098, 0.141 13.6%, 0.25, 0.391, 0.563, 0.765,
    1, 0.891 40.9%, 0.848, 0.813, 0.785, 0.766, 0.754, 0.75, 0.754, 0.766, 0.785,
    0.813, 0.848, 0.891 68.2%, 1 72.7%, 0.973, 0.953, 0.941, 0.938, 0.941, 0.953,
    0.973, 1, 0.988, 0.984, 0.988, 1
  );
}
```

**Tool**: Use Jake Archibald's linear() generator: https://linear-easing-generator.netlify.app/

---

## Animation Fill Mode

Use `animation-fill-mode` to prevent jarring visual resets:

| Mode | Behavior |
|------|----------|
| `forwards` | Retains animation styling after completion |
| `backwards` | Retains style from first keyframe before animation starts |
| `both` | Retains styling in both directions |

**Critical for**: Fade-in sequences with delays. Without `backwards`, elements flash at full opacity before their delayed animation starts, then pop to invisible, then fade in.

---

## Stagger Techniques

`animation-delay` only applies once (not per iteration). Approaches:

### 1. Different delays with finite iterations
Works for one-time sequences.

### 2. Pad keyframes to create stagger
```css
@keyframes spin {
  0%, 50% { transform: rotate(0deg); }
  100% { transform: rotate(360deg); }
}
```

### 3. Negative delays for "already in progress" effects
```css
.element {
  animation-delay: calc(var(--index) * -0.2s);
}
```

This makes animations appear mid-flight from the start—useful for staggered continuous animations.

---

## CSS Custom Properties & @property

### Type Specification Unlocks Animation

The `@property` rule lets you declare types for CSS variables, enabling smooth interpolation:

```css
@property --hue {
  initial-value: 0;
  inherits: false;
  syntax: '<number>';
}

@keyframes rainbow {
  to { --hue: 360; }
}
```

**Available types**: length, number, percentage, color, angle, time, integer, transform-list

**Why this matters**: Without `@property`, CSS sees custom properties as strings. Strings can't interpolate—they just swap. With a declared type, the browser knows how to smoothly transition between values.

### Decompose Complex Transforms

Instead of animating a monolithic transform, split into typed properties for curved paths:

```css
@property --x { syntax: '<percentage>'; initial-value: 0%; inherits: false; }
@property --y { syntax: '<percentage>'; initial-value: 0%; inherits: false; }

.ball {
  transform: translateX(var(--x)) translateY(var(--y));
  animation: throw 1s;
}

@keyframes throw {
  0% { --x: -500%; }
  50% { --y: -250%; }
  100% { --x: 500%; }
}
```

This creates curved motion paths impossible with standard transform animation—the ball arcs through space rather than moving in straight lines.

### Scoped Variables for Dynamic Behavior

CSS custom properties respect scope:

```css
.item { --delay: 0; animation-delay: calc(var(--delay) * 100ms); }
.item:nth-child(1) { --delay: 0; }
.item:nth-child(2) { --delay: 1; }
.item:nth-child(3) { --delay: 2; }
```

Use scoped variables to create varied behavior from a single animation definition.

---

## 3D CSS

### Think in Cuboids

> "Think in cubes instead of boxes"

Complex 3D scenes are assemblies of cube-shaped elements (like LEGO). Decompose any 3D object into cuboids.

### Essential Setup

```css
.scene {
  transform-style: preserve-3d;
  perspective: 1000px;
}
```

### Responsive 3D

Use CSS variables for dimensions and `vmin` units:

```css
.cube {
  --size: 10vmin;
  width: var(--size);
  height: var(--size);
}
```

---

## Scroll-Driven Animations

### The Core Problem

Scroll-driven animations are tied to scroll **speed**. If users scroll slowly, animations play slowly. This feels wrong for most UI—you want animations to trigger at a scroll position, not be controlled by scroll speed.

### Duration Control Pattern

Use two coordinated animations:

1. **Trigger animation**: Scroll-driven, toggles a custom property when element enters view
2. **Main animation**: Traditional duration-based, activated via Style Query

This severs the connection between scroll speed and animation timing—the animation runs over a fixed duration once triggered.

### Progressive Enhancement

Always provide fallbacks:

```javascript
if (!CSS.supports('animation-timeline', 'scroll()')) {
  // Use IntersectionObserver instead
}
```

---

## CSS Art & Illustrations

### Why CSS Art Matters

CSS art teaches real skills that transfer to production work:
- clip-path mastery
- Border-radius tricks
- Stacking contexts
- Complex gradients
- Pseudo-element layering

### Advice for Complex Illustrations

- Break down into simple shapes
- Use pseudo-elements liberally (`:before`, `:after`)
- Layer with z-index carefully
- Use CSS variables for repeated values
- Don't be afraid to use many elements

---

## Motion Paths

### Responsive Motion Paths

```css
.element {
  offset-path: path('M 0 0 Q 50 100 100 0');
  animation: move 2s ease-in-out infinite alternate;
}

@keyframes move {
  to { offset-distance: 100%; }
}
```

For responsive paths, calculate path coordinates based on container size using CSS variables or JavaScript.

---

## Common Mistakes (Jhey's Perspective)

- **Filtering ideas based on "usefulness" too early** — Make first, judge later
- **Not documenting random creative sparks** — Keep notebooks everywhere, including by your bed
- **Thinking CSS art is useless** — It teaches real skills (clip-path, layering, complex shapes)
- **Focusing on "How do I learn X?" instead of "How do I make Y?"** — Let ideas drive learning
- **Following tutorials without experimenting** — Tutorials teach techniques; experimentation teaches problem-solving
- **Giving up when something doesn't work** — The struggle is where learning happens

---

## When to Experiment vs. Ship

| Situation | Approach |
|-----------|----------|
| Learning new CSS feature | Build something weird and fun |
| Portfolio piece | Push boundaries, show creativity |
| Personal project | Follow your joy |
| Client work | Apply Jakub's production polish instead |
| High-frequency tool | Apply Emil's restraint instead |

The playful approach is for **learning and exploration**. For production, switch to Jakub or Emil's mindset.

---

## Jhey's Technique Index

| Technique | Key Insight |
|-----------|-------------|
| linear() function | Pure CSS bounce/elastic/spring effects |
| @property | Type specification enables CSS variable animation |
| animation-fill-mode | Prevent flash before delayed animations |
| Negative delays | "Already in progress" stagger effects |
| 3D CSS | Think in cubes, use preserve-3d |
| Scroll-driven | Decouple scroll speed from animation duration |
| CSS art | "Useless" demos teach real skills |
| Scoped variables | Varied behavior from single animation |

---

## Jhey's Article Index

From Smashing Magazine, CSS-Tricks, and more:

| Article | Topic |
|---------|-------|
| Playfulness In Code | Philosophy of learning through fun |
| The Path To Awesome CSS Easing With linear() | Custom easing in pure CSS |
| Exploring @property | Animating CSS custom properties |
| CSS in 3D: Thinking in Cubes | 3D CSS fundamentals |
| Advice for Complex CSS Illustrations | CSS art techniques |
| Scroll-Driven Animation with Duration | Decoupling scroll from timing |
| The Power of Scope with CSS Custom Properties | Dynamic behavior patterns |

---

## Jhey vs. Emil vs. Jakub

| Aspect | Jhey | Emil | Jakub |
|--------|------|------|-------|
| **Focus** | Playful experimentation | Restraint & speed | Subtle polish |
| **Key question** | "What could this become?" | "Should this animate?" | "Is this subtle enough?" |
| **Signature technique** | CSS custom properties | Frequency-based decisions | Blur + opacity + translateY |
| **Ideal context** | Learning & exploration | High-frequency tools | Production polish |

**When to use Jhey**: You're learning something new, exploring what's possible, or building something for the joy of it. The skills transfer to production work later.
