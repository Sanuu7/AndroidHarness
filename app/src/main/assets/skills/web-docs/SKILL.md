---
name: web-docs
description: Need current docs or an API. Search, fetch the page, cite. Do not invent APIs.
category: research
---

# Web docs

Look it up. Do not invent parameter names.

## When to use
- Current library / Android / Gradle / API docs
- "what is the latest" / "does this still work"

## When not to use
- The answer is in this repo (`grep` first)

## Procedure
1. `web_search` the exact API plus year or version if you know it.
2. `web_fetch` the official page (developer.android.com, docs.rs, etc.).
3. Quote the relevant bit and the URL. Then apply it with `edit_file`.
4. If fetch fails, try another result. Do not hallucinate the page.

## Pitfalls
- Blog spam over official docs.
- Mixing v1 and v2 APIs from memory.

## Verification
Every new API you used is backed by a fetched page or an in-repo example.
