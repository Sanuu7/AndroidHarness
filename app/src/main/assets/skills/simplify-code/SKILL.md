---
name: simplify-code
description: Working code that is too big. Same behavior, less surface.
category: software-development
---

# Simplify code

Same behavior, fewer moving parts. Do not add features while simplifying.

## When to use
- The user said simplify, cleanup, or "this is too much"
- A function you just wrote grew past what the task needed

## When not to use
- Behavior is still wrong (fix first)
- A rewrite would change public API without asking

## Procedure
1. `read_file` the target. Check: you know current behavior.
2. List deletions: unused params, duplicate branches, dead abstractions. Check: each item is unused or equivalent.
3. Apply with `edit_file` / `multi_edit`. Prefer delete over move.
4. Run the nearest tests or the previous repro. Check: same results.
5. Stop when the next cut would change behavior.

## Pitfalls
- "Cleaner" that adds a framework is not simpler.
- Do not rename widely just to look neat.

## Verification
Diff is net-negative or clearly simpler, and tests / repro still pass.
