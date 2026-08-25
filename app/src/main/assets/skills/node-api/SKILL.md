---
name: node-api
description: Node.js or Express backend, REST APIs, npm scripts. Async-safe and testable on-device.
category: web-dev
---

# Node API

Correct async, validated inputs, and an endpoint you actually curl before claiming done.

## When to use
- REST API, Express/Fastify server, webhook receiver, cron job
- User says backend, API, server, endpoints, Node

## When not to use
- Frontend-only work (react-frontend or web-design)

## Procedure
1. `env_status` once. If node/npm are missing, the user installs the Linux environment from Settings -> Terminal; stop after saying so.
2. Scaffold: `npm init -y` then only the packages the task needs (`npm i express`). List what you installed.
3. Structure small: `server.js` entry, `routes/`, one module per resource. No frameworks beyond what was asked.
4. Async rules: wrap awaits in try/catch or an Express error middleware (async throws do not reach the default handler). Never use sync fs calls inside request handlers. Handle unhandledRejection explicitly.
5. Validate inputs at the boundary; return 400 with a short JSON error shape the client can render.
6. Run: start with the harness background shell (`shell_background` or `node server.js &`), bind 0.0.0.0 so the phone's own Chrome can open http://localhost:PORT.
7. Verify with `curl -s localhost:PORT/path` for one happy case and one bad input. Paste both outputs.

## Pitfalls
- Claiming the API works without curling it.
- Port 3000 already taken by a previous bg process; kill it first (bg_list/bg_kill).
- Secrets in code; read them from env vars.

## Verification
Two curls shown (success + failure case), server still running, no unhandled rejection warnings.
