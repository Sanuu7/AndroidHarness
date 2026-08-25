---
name: test-driven-development
description: New behavior or a bug with a repro. Test first, then the fix.
category: software-development
---

# Test-driven development

Write a failing test that pins the behavior, then the smallest code that makes it pass.

## When to use
- New behavior the user asked for
- A bug you can express as input -> expected output
- You are about to change logic that already has a test hook

## When not to use
- Pure visual / HTML mockups (use sketch or web-design)
- One-off scripts with no test runner

## Procedure
1. Find how this project runs tests (`grep` for test, `./gradlew test`, pytest). Check: one command.
2. Write the failing test with `write_file` or `edit_file`. Check: run it, it fails for the right reason.
3. Write the production change. Check: that test now passes.
4. Refactor only after green. Check: tests still pass.
5. If the suite is huge, run the new test plus the nearest related ones.

## Pitfalls
- A test that never failed proves nothing.
- Do not mock the unit under test.
- Android: prefer JVM unit tests under `app/src/test` when the logic does not need a device.

## Verification
The new test failed once, then passed, and you kept it.
