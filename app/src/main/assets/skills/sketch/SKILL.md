---
name: sketch
description: Compare 2-3 visual directions before committing. Throwaway HTML mockups, not shippable code.
category: design
---

# Sketch

Two or three complete HTML variants with different stances. Then the user picks.

## When to use
- "show me options", "what could this look like", "compare A vs B"

## When not to use
- They already locked a look (just build it)
- They want production components

## Procedure
1. If feel / reference / core action are missing, `ask_user` once.
2. Pick one axis (density, emphasis, or aesthetic) and pull two poles plus an optional third.
3. `write_file` each as `sketches/<stance>/index.html`, self-contained.
4. Name folders by stance (`calm-editorial`, not `v1`).
5. Ask which one to take to web-design or into the real app.

## Pitfalls
- Three files that only change accent color waste the turn.
- Do not start a design system. These are disposable.

## Verification
The user can open two files and tell them apart in two seconds.
