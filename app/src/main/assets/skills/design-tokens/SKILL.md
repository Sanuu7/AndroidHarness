---
name: design-tokens
description: Author a DESIGN.md token spec (colors, type, spacing) the agent can reuse.
category: design
---

# Design tokens

A DESIGN.md the agent can keep using: YAML-ish tokens plus short rationale.

## When to use
- They asked for a design system, tokens, or DESIGN.md
- Several screens must stay consistent

## When not to use
- A one-off HTML artifact (web-design)
- They only wanted a Stripe clone (popular-web-designs)

## Procedure
1. If a brand was named, load popular-web-designs first and translate into tokens.
2. `write_file` `DESIGN.md` with: colors (bg, text, accent, danger), type (display, body, mono), space scale, radius, one contrast note.
3. Put hex values, not "a nice blue".
4. Add a tiny table: pair, ratio guess, pass/fail for body text on bg. No CLI required.
5. Later screens should `read_file` this DESIGN.md before inventing colors.

## Pitfalls
- Tokens nobody uses.
- Missing dark/light if they asked for both.

## Verification
Another turn can style a page using only this file.
