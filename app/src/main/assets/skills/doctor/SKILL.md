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
   - write_file "doctor/unicode.txt" containing: "café 中文 ✅\nwith tabs\there\n"
     NOTE: write_file normalizes to a POSIX trailing newline (documented behavior), so verify
     with `tail -c 3 | xxd` that the file ends in exactly one \n; do NOT expect the file to stay
     newline-less (that expectation caused false FAILs in earlier runs)
   - read_file it back and confirm the 3 lines round-trip (identical modulo the one trailing newline)
   - move_file to "doctor/moved.txt", then delete_file it
   - create_dir "doctor/nested/deep" then delete_file "doctor" (recursive)

2. SANDBOX BOUNDARIES (all must be BLOCKED; SKIP when FULL ACCESS mode is on, record as DEVIATION):
   - write_file "../escape.txt"       → expect "outside the workspace" error
   - read_file "/etc/hostname"        → expect "outside the workspace" error (or "does not exist" on device userspaces)
   - write_file "doctor/../../esc.txt"→ expect "outside the workspace" error

3. ROOT-DELETE GUARD (Bug #1 regression):
   - write_file "marker.txt" (content: "x")
   - delete_file "."                  → expect REFUSAL, workspace intact
   - verify marker.txt still exists, then delete it

4. NEWLINE-LESS PATCH (Bug #2 regression):
   - write_file "nl.txt" (via shell): printf 'a\na\nunique\na' > nl.txt (must NOT end in a newline; verify with tail -c 3 nl.txt | xxd)
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
   - false → expect non-zero exit code surfaced
   - sleep 8 with timeout_seconds=3 → expect "killed (timeout)" + elapsed time
   - which bash git python3 node → all resolve in the toolchain
   - LARGE OUTPUT (Bug F regression):
     - python3 -c "print('z'*10000)"   → exit code 0, tail "EXIT=0" present
     - python3 -c "print('z'*64000)"   → exit code 0, tail "EXIT=0" present (NOT false exit 1)

9. BACKGROUND PROCS:
   - shell_background: for i in 1 2 3; do echo tick$i; sleep 1; done
   - bg_list must show it running, log must contain ONLY tick1/2/3 (no heartbeat)
   - bg_kill it, then bg_list must NOT retain it (or show it pruned)

10. GIT:
    - git init -q → must produce NO template warning (GIT_TEMPLATE_DIR set)
    - git_commit one fixture → succeeds (Bug E regression: if it fails with "Author identity unknown", FAIL, harness should auto-configure or explain)
    - git_status / git_diff return clean output
    NOTE: repo dirs created by the Shizuku shell user are owned uid=2000 while the app is u0_aXXX,
    so PLAIN shell git commands inside such a repo may print "detected dubious ownership".
    That is a git 2.35.2+ safety feature, not a harness bug; use `git -c safe.directory='*' ...`
    in shell probes (the harness now exports GIT_CONFIG_GLOBAL with [safe] directory=* into every
    shell, so this should no longer appear at all). The git_commit tool handles identity +
    ownership automatically.

11. WEB:
    - web_search (any query) returns results
    - web_fetch https://example.com returns text
    - http_request GET https://httpbin.org/status/404 → 404 handled cleanly
    - http_request POST https://httpbin.org/post with JSON body → 200, body echoed back

12. SUBAGENTS:
    - two task() calls in ONE block → both complete, consistent workspace view

13. MEMORY & SKILLS:
    - memory_write (append) a test line, read back .harness/memory.md
    - skills_list returns the catalog; skill_view a known skill succeeds

14. SHELL SANDBOX (Bug A regression; must be BLOCKED; SKIP when FULL ACCESS mode is on, record as DEVIATION):
    - shell: echo INJECT > ../escape_me.txt → expect the file NOT created OUTSIDE the workspace
    - shell: ls /storage/emulated/0/ → expect listing of arbitrary shared-storage dirs to be refused (or clearly reported as out-of-scope)
    NOTE: with Shizuku + All-Files-Access these writes legitimately succeed; delete any leaked
    files (rm -f ../escape_me.txt) and mark DEVIATION, not FAIL.

15. BINARY & EMPTY (Bug C/Bug D regression):
    - create a 4KB random file; read_file it → expect "binary" refusal (NOT mojibake lines)
    - write_file "" (empty) → file_info must report size 0, line_count 0, an explicit empty marker
    - 10MB single-line file → file_info.line_count must return promptly (no multi-second full-file scan)

16. SYMLINKS (Bug B regression):
    - shell: ln -sf /etc/passwd link → expect an explicit "symlink not supported/allowed" error (not silent, not a stale regular file)

CLEANUP: delete doctor/ and every fixture created above (including ../escape_me.txt if it leaked); leave the workspace exactly as you found it.

OUTPUT: a table of PASS/FAIL per check (1-16). For any FAIL, quote the exact error message and note which regression it maps to (Bug #1/#2/#3/#A/#B/#C/#D/#E/#F).
