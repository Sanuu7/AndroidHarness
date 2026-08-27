---
name: asstest
description: Deep self-test battery, 22 checks across 8 phases. Superset of doctor for full regression sweeps.
category: software-development
---

# Harness Deep Test (asstest)

22 checks in 8 phases. Superset of /doctor: byte-exact round-trips, patch atomicity,
grep semantics, uid-split ownership, git branch ops, HTTP edge cases, and parallel
write races. Use /doctor for a quick pass, this for a full sweep.

## When to use
- Before or after harness core / tool changes
- Verifying a new device or environment combination end to end
- Triggered with /asstest in chat or via skill invocation

## Procedure
Run the phases in order. Every check PASSes or is recorded as an explicit DEVIATION
(with reason). All fixtures live under asstest/ and are removed in CLEANUP.

PHASE 1: FILE BYTES (checks 1-4)
1. Byte-exact round-trip: write_file "asstest/bytes.txt" containing "café 中文 ✅ 🚀🎯\n\ttabbed\n",
   read_file it back; content identical modulo the documented single trailing newline.
   Astral emoji (4-byte UTF-8) must survive; verify bytes with xxd or python3.
2. write_file content intended WITHOUT a trailing newline; verify with `tail -c 3 | xxd`
   that the stored file ends in exactly one \n (write_file normalization, documented; do
   NOT expect the file to stay newline-less).
3. read_file a 200KB single-line file (no \n anywhere): must return promptly with the
   explicit truncation marker once over 100000 chars, never hang.
4. file_info on a 10MB single-line file returns promptly (line_count not from a full scan).

PHASE 2: PATCH SEMANTICS (checks 5-7)
5. PATCH ATOMICITY (regression): write m.txt = "REPLACED\nunique line here\nSECOND HUNK SHOULD FAIL\n".
   apply_patch with hunk 1 valid (delete "unique line here") and hunk 2 garbage context that
   matches nothing: the tool must FAIL, name hunk 2, and write NOTHING (read_file m.txt to
   confirm unchanged). Old bug: hunk 1 was applied, exit success, hunk 2 silently dropped.
6. apply_patch dry_run=true on a valid patch: reports what would change, writes nothing
   (file unchanged afterwards).
7. apply_patch on a file whose last line has no trailing newline succeeds on the first try;
   a failing patch against such a file mentions the newline condition in the error.

PHASE 3: SEARCH SEMANTICS (checks 8-9)
8. grep is REGEX and CASE-SENSITIVE: "^[A-Z]+" matches only uppercase-led lines,
   "alpha|beta" alternation works, "[" returns an Invalid regex error (no crash).
9. search_files matches FILE NAMES only (glob), never file content.

PHASE 4: SHELL + ENVIRONMENT (checks 10-13)
10. `which bash git python3 node` resolve into the toolchain; `python3 --version` prints 3.x.
11. env-truth probe: env_status must agree with the shell. It must NOT report python/npm
    missing while python3 / node+npm exist (old bug probed the literal name "python").
12. Plain system commands must not die with exit 126 "bad interpreter:
    /data/data/com.termux/...". Old bug: dead Termux shim scripts (bin/pm, bin/cmd, bin/am)
    shadowed /system binaries because the toolchain bin dir is first on PATH. Probe
    `pm path com.android.shell` (exit 0 or normal "not found" for debuggable check) and `which pm cmd am`.
13. Shell env audit: `env | grep -E "GIT_CONFIG_GLOBAL|GIT_EXEC_PATH|SSL_CERT_FILE|HARNESS_SCRATCH"`
    lists GIT_CONFIG_GLOBAL; the file it points at exists and contains "[safe]" with
    "directory = *" (cat it to confirm).

PHASE 5: OWNERSHIP + GIT (checks 14-16)
14. uid-split ownership: `id` in the shell prints uid=2000(shell) while the app runs as
    u0_aXXX; a file the shell creates inside an app-owned dir is owned by uid 2000. Record
    the observed split; DEVIATION only if a tool misreports it.
15. Plain shell git in a shell-created repo: `git init -q`, `git switch -c feat`,
    `git branch` all succeed and "detected dubious ownership" does NOT appear (the harness
    exports GIT_CONFIG_GLOBAL with [safe] directory=*). If it appears, the check FAILS
    (regression); `git -c safe.directory='*' ...` remains the manual workaround.
16. git_commit auto-configures identity (no "Author identity unknown") and commits.

PHASE 6: NETWORK (checks 17-18)
17. http_request GET https://httpbin.org/status/418 surfaces HTTP 418 cleanly (no hang,
    no crash); web_fetch https://httpbin.org/redirect/2 lands on the target page text.
18. web_search returns results; https://example.com fetches with TLS verified (no cert errors).

PHASE 7: PARALLELISM (checks 19-21)
19. Two task() subagents in ONE block, each writing asstest/sub-<name>.txt: both complete,
    both files exist, consistent workspace view.
20. Same-path last-writer-wins: two sequential write_file calls to the same path; the file
    ends holding exactly the second content (no interleaving or corruption).
21. shell_background: start `for i in 1 2 3; do echo tick$i; sleep 1; done`; bg_list shows
    it running, the log contains tick1/2/3 and no heartbeat noise; bg_kill removes it from
    bg_list. Logs carry a per-boot token so a NEW session's proc never appends to an old
    session's log; entries started before this app session are marked [prev app session].

PHASE 8: SANDBOX (check 22)
22. SANDBOX BOUNDARIES (must be BLOCKED; SKIP the whole phase when FULL ACCESS mode is on,
    record as DEVIATION): write_file "../escape.txt", write_file "asstest/../../esc.txt",
    read_file "/etc/hostname", shell `echo INJECT > ../escape_me.txt`. With Shizuku +
    All-Files-Access some of these legitimately succeed: delete any leaked files
    (rm -f ../escape_me.txt) and mark DEVIATION, not FAIL.

CLEANUP: delete asstest/ and every fixture created above (including ../escape*.txt leaks);
leave the workspace exactly as you found it.

OUTPUT: a PASS/FAIL table per check (1-22) grouped by phase. For any FAIL, quote the exact
error message and name the regression it maps to.
