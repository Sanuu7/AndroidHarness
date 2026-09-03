---
name: android-crash
description: App crashed or ANR. Pull logcat, find the stack, fix.
category: android
---

# Android crash

Get the stack, then fix the cause. Do not guess the Activity.

## When to use
- Crash, ANR, "it died", red screen, process died
- The user pasted a stack or said it failed after install

## When not to use
- Build failed before install (android-build)
- Compose preview looks wrong (android-compose)

## Procedure
1. `env_status`. If the Linux prefix or Shizuku is missing and you need device-wide logcat, tell the user once.
2. `read_logcat` with `level="E"` and `package_name` (or `tag="AndroidRuntime"`). Check: a stack or FATAL EXCEPTION.
3. `grep` the top frames in the workspace. `read_file` the file. Check: the crashing line.
4. Fix with `edit_file`. Check: the NPE / race / bad cast is actually gone.
5. Tell the user how to relaunch. If they can, pull logcat again.

## Pitfalls
- Toybox may not have logcat flags you expect. Use `logcat -d`.
- `/data/local/tmp` is not evidence of Shizuku. Use `env_status`.
- Do not invent linker64 workarounds.

## Verification
The same launch path no longer throws that stack.
