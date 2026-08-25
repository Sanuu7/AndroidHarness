---
name: review
description: User asked for a review, or you are about to commit. Diff, risks, tests.
category: software-development
---

# Review

Read the actual diff. Report risks, missing tests, and what looks fine. Do not rewrite unless asked.

## When to use
- The user asked for a review
- You are about to `git_commit`
- A large turn just landed

## When not to use
- There are no changes
- The user asked you to implement, not comment

## Procedure
1. `git_status` then `git_diff` (or read the files you just edited). Check: you saw the full diff.
2. Group findings: correctness, security, tests, nits. Check: each finding cites a path.
3. Say what you would test. Check: at least one concrete case.
4. If you are also the author, fix must-fix items with `edit_file` after the review, not instead of it.

## Pitfalls
- Do not invent files you did not read.
- Style-only nits go last, or skip them.

## Verification
The user can act on every finding without opening a second investigation.
