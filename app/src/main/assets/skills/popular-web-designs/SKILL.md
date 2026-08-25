---
name: popular-web-designs
description: Make it look like Stripe, Linear, Vercel, Notion, or another known product. Load the catalog first.
category: design
---

# Popular web designs

Use a real product's tokens. Do not approximate from memory.

## When to use
- "make it look like X"
- They want a known brand as the starting point

## When not to use
- Original look (web-design only)
- Formal token spec file (design-tokens)

## Procedure
1. `skill_view` this skill's catalog: file_path `references/catalog.md`.
2. Pick the closest brand. If none match, say so and fall back to web-design.
3. Apply palette, type, radius, and one do/don't from that entry.
4. `write_file` the HTML (or restyle the existing one).
5. Pair with web-design when the page type is marketing.

## Pitfalls
- Mixing Stripe purple with Linear beige.
- Shipping all 54 brand files. The catalog is the source.

## Verification
Someone who knows the brand would recognize the page without the logo.
