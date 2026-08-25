---
name: linux-env
description: Command 126/127, missing git/python/node, or env looks broken. Use env_status first.
category: environment
---

# Linux environment

The harness owns PATH. You do not.

## When to use
- Exit 126 / 127, "not found", "permission denied" on a basic tool
- Need git, python, node, or a compiler
- You are about to poke `/data/local/tmp` to "see if Shizuku works"

## When not to use
- The command failed with a normal non-zero and stderr from the program itself

## Procedure
1. Call `env_status` once. Check: you have the official state.
2. Tell the user what it reported (Shizuku, Linux prefix, storage, tier).
3. If the prefix is missing, they install from Settings → Terminal. Do not retry linker64 / toybox hacks.
4. If Shizuku is needed for the path, say so. Do not loop `pm` / system paths on the app tier.
5. After they install, one more `env_status`, then continue the original task.

## Pitfalls
- Never invoke `/system/bin/linker64` yourself.
- `/data/local/tmp` is not a status API.

## Verification
The next real command runs on the tier `env_status` described, or the user was asked to unlock it.
