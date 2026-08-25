---
name: systematic-debugging
description: Something is broken and the cause is unknown. Reproduce, isolate, fix, prove.
category: software-development
---

# Systematic debugging

Find the actual root cause before changing code. Do not shotgun-fix.

## When to use
- A crash, wrong output, test failure, or "it used to work"
- The user cannot point at the exact line

## When not to use
- The user already named the file and the change
- You are designing something new (use plan or spike)

## Procedure
1. Reproduce. Run the failing path with `shell` or read the stack the user pasted. You must see the failure yourself. Check: one concrete failing command or stack.
2. Isolate. `grep` / `read_file` the stack frames and nearby callers. Form one hypothesis. Check: a single suspected function or state.
3. Confirm. Add a log, a failing test (`test-driven-development` if you will keep the test), or read the value that should be wrong. Check: evidence, not a guess.
4. Fix the cause with `edit_file` / `multi_edit`. Do not paper over it. Check: the original repro now passes.
5. Prove. Re-run the same command or test. Check: pass, plus no new obvious breakage nearby.

## Pitfalls
- Do not restart from a different theory every turn. Finish the current one.
- On Android, pull `logcat -d -t 200` via `shell` (see android-crash) instead of guessing.
- If a command is 126/127, call `env_status` once and stop inventing PATHs.

## Verification
The original failing repro now succeeds, and you can name the root cause in one sentence.
