# Philosophy & Approach

Understanding the "why" behind these designers' techniques is as important as the techniques themselves. Their approaches differ significantly, and knowing when to apply each mindset is crucial.

---

## Emil's Approach: Purposeful Restraint

Emil Kowalski's defining contribution is knowing **when NOT to animate**.

> "The goal is not to animate for animation's sake, it's to build great user interfaces."

**Core Philosophy**: Animation decisions should be driven by **interaction frequency**. The more often users perform an action, the less animation it should have—or none at all.

**The Frequency Framework**:
- **Rare interactions** (monthly): Delightful, expressive animations welcome
- **Occasional interactions** (daily): Subtle, fast animations
- **Frequent interactions** (100s/day): Minimal or no animation
- **Keyboard-initiated actions**: Never animate

**Emil's Speed Rule**: For high-frequency UI, animations should stay under 300ms (180ms feels ideal). When in doubt, go faster. *Note: This is Emil's guideline for productivity tools—Jakub and Jhey may use longer durations for polish or experimentation.*

**When to apply Emil's mindset**:
- High-frequency productivity tools (like Raycast, Linear)
- Keyboard-heavy workflows
- When users have clear goals and don't expect delight
- When questioning whether to animate at all
- When existing animations feel slow or tiresome

---

## Jakub's Approach: Subtle Production Polish

Jakub's work embodies **refinement for production use**. His animations are:

- **Barely noticeable** — If users consciously notice the animation, it's probably too much
- **Production-ready** — Designed for real client work, not demos
- **Contextually appropriate** — Adapts to light mode, varied backgrounds, real content
- **Subtle over flashy** — The goal is to make interfaces feel smooth and responsive, not impressive

**Core Philosophy**: Animation should **enhance the experience invisibly**. Users should feel that an interface is polished and responsive without being able to point to specific animations. The best compliment is "this feels really nice" not "cool animation!"

**When to apply Jakub's mindset**:
- Production applications and client work
- Professional/enterprise interfaces
- When users will interact repeatedly (animations must not get tiresome)
- When accessibility and performance are critical

---

## Jhey's Approach: Learn Through Playful Creation

Jhey's work embodies **experimental joy**. His philosophy:

> "I went from 'I want to learn X, so how do I fit it into Y' to 'I want to make Y, can I learn X to do it?'"

**The motivation should be making something cool—learning is a happy side effect.**

**Core Beliefs**:
- **No idea is a bad idea** — Document every spark, however weird (toadstools, Peter Griffin blinds, bread array slice/splice cartoon)
- **Don't ask "Why?" or "Is this practical?"** — Make what brings you joy first
- **"Useless" demos teach real skills** — CSS art teaches clip-path mastery, border-radius tricks, stacking contexts
- **Lateral learning** — Building diverse demos trains you to switch contexts and rise to challenges
- **You'll never have time to make everything** — And that's okay. The act of documenting ideas matters.

**Core Philosophy**: Playfulness in code supercharges learning. The fact you're learning new skills is a bonus, not the goal. Work on ideas that spark joy for you.

**When to apply Jhey's mindset**:
- Learning new techniques
- Personal projects and experiments
- When you're stuck in a creative rut
- Building your portfolio or demos
- Exploring what's possible with new CSS features

---

## The Golden Rule of Animation

> "The best animation is that which goes unnoticed."

*(Note: Jhey references this as a saying that has always stuck with him, not as his original quote. The principle itself is widely shared in motion design.)*

This captures the essence of production-quality motion design. Effective motion:
- Enhances the experience without demanding attention
- Feels natural and expected
- Serves a functional purpose (orientation, feedback, continuity)
- Doesn't fatigue users on repeated interactions

**The test**: If you remove the animation, do users feel something is missing? Good. If users comment "nice animation!" every time they see it? Probably too prominent.

---

## Synthesizing All Three Approaches

| Question | Emil | Jakub | Jhey |
|----------|------|-------|------|
| **Primary concern** | "Should this animate at all?" | "Is this subtle enough?" | "What could this become?" |
| **Success metric** | Frictionless workflow | Invisible polish | Joy and learning |
| **Duration preference** | Under 300ms (180ms ideal) | "Whatever feels right" (often 200-400ms) | Varies widely by effect |
| **Signature technique** | Frequency-based decisions | Blur + opacity + translateY | CSS custom properties |
| **Ideal context** | High-frequency tools | Production polish | Learning & exploration |

**Decision Framework**:
1. **First, apply Emil's lens**: Should this animate at all? Check frequency and purpose.
2. **If yes, apply Jakub's lens**: How do we make this subtle and production-ready?
3. **For learning/exploration, apply Jhey's lens**: What techniques can we discover through play?

The three approaches aren't competing—they're complementary filters for different stages and contexts of motion design work.
