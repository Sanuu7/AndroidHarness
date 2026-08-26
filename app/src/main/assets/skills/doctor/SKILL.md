---
name: doctor
description: Run a self-test battery of every harness tool family and report pass/fail.
category: software-development
---

# Harness Doctor

Self-test battery verifying every tool family, sandbox bounds, regressions, and subagents.

## When to use
- After any change to the harness core or tools
- When verifying device environment, shell, git, or network capabilities
- Triggered directly with `/doctor` in chat or via skill invocation

## Procedure
Run the self-test battery checks in order:

1. FILE CRUD & UNICODE:
   - write_file "doctor/unicode.txt" containing: "café 中文 ✅\nwith tabs\there\n" (no trailing newline after 'here')
   - read_file it back and confirm the 3 lines round-trip byte-identically
   - move_file to "doctor/moved.txt", then delete_file it
   - create_dir "doctor/nested/deep" then delete_file "doctor" (recursive)

2. SANDBOX BOUNDARIES (all must be BLOCKED):
   - write_file "../escape.txt"       → expect "outside the workspace" error
   - read_file "/etc/hostname"        → expect "outside the workspace" error
   - write_file "doctor/../../esc.txt"→ expect "outside the workspace" error

3. ROOT-DELETE GUARD (Bug #1 regression):
   - write_file "marker.txt" (content: "x")
   - delete_file "."                  → expect REFUSAL, workspace intact
   - verify marker.txt still exists, then delete it

4. NEWLINE-LESS PATCH (Bug #2 regression):
   - write_file "nl.txt" (via shell): printf 'a\na\nunique\na' > nl.txt (must NOT end in a newline; verify with `tail -c 3 nl.txt | xxd`)
   - apply_patch replacing "unique" → "CHANGED" must SUCCEED on the first try
   - the wrong-hunk error must MENTION the newline condition

5. CREATE_DIR OVER FILE (Bug #3 regression):
   - write_file "occ.txt" (content: "x")
   - create_dir "occ.txt"            → expect "already exists and is a file" error

6. PATCH ATOMICITY:
   - write_file "m.txt" with a duplicated line + one unique line
   - edit_file on the duplicated line → expect "appears N times" ambiguity error
   - multi_edit with [valid edit, missing edit] → expect failure AND rollback (verify valid edit was NOT applied)

7. SEARCH:
   - grep an alternation/anchored pattern over doctor fixtures → matches
   - search_files "*.txt" lists expected files
   - grep "[" (unclosed class) → expect "Invalid regex" error

8. SHELL:
   - echo to stdout AND stderr → expect SEPARATE --- stdout --- / --- stderr --- sections
   - `false` → expect non-zero exit code surfaced
   - `sleep 8` with timeout_seconds=3 → expect "killed (timeout)" + elapsed time
   - `which bash git python3 node` → all resolve in the toolchain

9. BACKGROUND PROCS:
   - shell_background: `for i in 1 2 3; do echo tick$i; sleep 1; done`
   - bg_list must show it running, log must contain ONLY tick1/2/3 (no heartbeat)
   - bg_kill it, then bg_list must NOT retain it (or show it pruned)

10. GIT:
    - git init -q → must produce NO template warning (GIT_TEMPLATE_DIR set)
    - git_commit one fixture → succeeds
    - git_status / git_diff return clean output

11. WEB:
    - web_search (any query) returns results
    - web_fetch https://example.com returns text
    - http_request GET https://httpbin.org/status/404 → 404 handled cleanly

12. SUBAGENTS:
    - two task() calls in ONE block → both complete, consistent workspace view

13. MEMORY & SKILLS:
    - memory_write (append) a test line, read back .harness/memory.md
    - skills_list returns the catalog; skill_view a known skill succeeds

CLEANUP: delete doctor/ and every fixture created above; leave the workspace exactly as you found it.

OUTPUT: a table of PASS/FAIL per check. For any FAIL, quote the exact error message and note which regression it maps to (Bug #1/#2/#3 or a new one).
