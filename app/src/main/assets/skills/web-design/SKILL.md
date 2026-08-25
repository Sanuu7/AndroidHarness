---
name: web-design
description: Distinctive website or app UI. Agrees HTML or React with the user first, no template looks.
category: design
---

# Web design

Design like a studio lead whose clients reject anything templated. One deliberate direction, executed precisely.

## When to use
- New page, site, dashboard, component lab, or restyle

## When not to use
- They named a brand to copy (load popular-web-designs too)
- They want options first (sketch)
- They need a token spec file (design-tokens)

## Step 1: agree the stack with the user
- Quick mockup or static site -> one self-contained HTML file (default).
- Real app with components/state -> React or Next.js: load react-frontend instead.
- Needs a backend/API -> also load node-api.
If they already said which, skip the question.

## Design principles
1. Pin the brief: name the concrete subject, its audience, and the page's single job. Say your choice out loud.
2. Hero is a thesis: open with the most characteristic thing about the subject. A big-number-plus-gradient is the template answer; only use it if it truly wins.
3. Avoid the three AI default looks unless asked: cream #F4F1EA + serif + terracotta; near-black + one acid-green accent; broadsheet hairline columns. Where the brief is silent, spend that freedom elsewhere.
4. Typography carries personality: pair display and body faces deliberately per brief, set a real type scale.
5. Structure encodes meaning: numbering only where content is genuinely sequential.
6. Motion is orchestrated, not scattered: one load moment or hover language beats five effects.
7. Write real copy from the user's side of the screen: active verbs, sentence case, specific over clever. Errors explain the fix; empty states invite action.

## Build (HTML path)
- `write_file` one `.html`: CSS in `<style>`, JS only if needed, Google Fonts link allowed.
- Mobile-first, one palette, clear hierarchy on the first screen.

## Build (React path)
- Scaffold or reuse the existing app per react-frontend, then apply the same principles to components.

## Pitfalls
- Do not call browser_vision, terminal, or generative-widgets; this harness has shell, file tools, and web_fetch only.
- Inter-on-white with a purple blob is not a design decision.

## Verification
Open the result on the phone (Chrome for files, or the dev-server URL). First screen reads with a clear hierarchy and does not look like every other AI page.
