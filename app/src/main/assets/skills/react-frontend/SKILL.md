---
name: react-frontend
description: React, Next.js or Vite apps. Performance-first patterns, correct data fetching, clean state.
category: web-dev
---

# React frontend

Performance rules before polish. Most React slowness is waterfalls, barrel imports, and effect-driven state.

## When to use
- Building or refactoring React / Next.js / Vite components
- Slow pages, big bundles, unnecessary re-renders
- User says React, Next, JSX, hooks, Tailwind app

## When not to use
- Single static HTML mockup (web-design)
- Backend/API work only (node-api)

## Procedure
1. `env_status` once. Node lives in the Linux environment; if missing, tell the user to install it from Settings -> Terminal, then stop.
2. Existing app? `shell`: `cat package.json` to detect the framework and scripts. Empty workspace? Ask which: Vite (`npm create vite@latest app -- --template react`) or Next (`npx create-next-app@latest`). Do not pick for them.
3. Data fetching: parallelize independent requests with `Promise.all`. Never chain awaits that could run together. In Next, prefer server components fetching directly; pass only minimal props to client components.
4. Imports: import directly from module files, not barrel `index.js` files. Load heavy widgets with `next/dynamic` / `React.lazy`.
5. State: derive during render instead of syncing with useEffect. Functional setState for stable callbacks. Pass a function to useState for expensive initial values. Hoist default object props out of the component.
6. Styling: reuse the project's tokens; Tailwind if present. No inline hex soup.
7. Verify: `npm run build` must pass. Then `npm run dev -- --host` and give the user the localhost URL; on this device the server and Chrome share the phone, so http://localhost:PORT opens directly.

## Pitfalls
- Sequential awaits that form a waterfall.
- Effects that mirror props into state.
- Installing packages without telling the user what was added.

## Verification
Build passes, the first meaningful paint needs one round trip, and the URL you gave opens in the phone's browser.
