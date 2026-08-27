# Changelog

## 0.3-alpha (2026-08-27)

### Added

- **Full access mode** — a fourth permission level (orange, in Settings → Agent and the chat ⋮ → Permission menu) that lifts every sandbox layer at once: no approval prompts, no shell command denylist, no workspace path containment. File tools can read/write any path on the device (absolute paths work), the shell runs from any cwd, and subagents inherit the same freedom. The system prompt tells the model the sandbox is lifted. SAF (picked-folder) workspaces keep their containment — there is no shell root to extend.
- **In-app updater** — checks GitHub Releases automatically on launch and manually from Settings → Updates. When a newer release is published, a dialog shows the release notes with every markdown link (and bare URLs) tappable, so changelog.md-style bodies open in the browser. Update installs silently through Shizuku (`pm install -r`) when granted, otherwise it hands the APK to the system installer with an unknown-sources retry path. Download shows animated MB/percent progress. Version matching understands Alpha-v0.3-style tags.
- **Hermes-style global thinking ladder** — one canonical scale on every model: Off, Minimal, Low, Medium, High, X-High, Max, Ultra. The raw pick is stored verbatim and resolved at request time: non-native rungs fall down the chain (ultra → max → xhigh → high …) to the closest tier the model supports, floor fallback keeps an enabled ask enabled, and a stronger request never silently bills less than a weaker one.
- **Slash plan** — typing `/plan [instruction]` flips the header into Plan mode automatically AND loads the bundled planning skill into the run prompt (built-in fallback instructions when no plan skill is installed).
- **Stats screen sort + filter** — per-model attribution can be ordered by tokens, estimated price, or request count; price estimates render on every row.
- **file_info tool** for subagents: byte/newline inspection without loading content.
- Active running indicator in sidebar chats, thinking-level badge with model switcher in the header, universal B/M/K token formatting.

### Fixed

- **git tools unusable on foreign-owned repos**: every git invocation now carries `-c safe.directory=*`, so repositories owned by another uid (Shizuku shell reading the app workspace, shared storage owned by media) stop failing with "dubious ownership". If an exotic setup still trips it, `safe.directory '*'` is persisted once into ~/.gitconfig (creating it if missing) and the command re-runs. The commit identity fallback also writes the global config when repo-local config fails.
- **write_file silently stripped carriage returns**: streaming hosts that emit raw CR bytes inside JSON tool arguments had them eaten by the SSE line reader (okio's readUtf8Line treats any lone CR as a line break), tearing the payload in half and silently dropping the whole argument delta — CRLF content then landed as LF. The reader now frames on LF only, strips one preceding CR for CRLF framing, and preserves interior CRs byte-for-byte.
- **file_info misreported procfs files as empty**: /proc/self/status & co report st_size = 0 regardless of content, so file_info called them empty while read_file showed ~1KB. Size metadata is now only trusted when non-zero; zero-stat entries are streamed (1 MB cap) to measure real bytes, with a size_note rendered when stat undercounts.
- **Case-collision warnings**: on shared-storage / SAF mounts (case-insensitive), write_file, create_dir and move_file warn before creating a name differing only in case from an existing sibling ("CaseTest" vs "casetest" aliasing into one file). App-private storage stays case-sensitive and silent.
- **UTF-8 BOM leaked into read_file output** as invisible characters at the start of line 1, breaking exact matching; BOMs are now stripped on read.
- **git_commit swept .harness/ runtime artifacts** (background-process logs, scratch) into commits via `add -A`; staging now excludes the directory. git_status/git_diff output no longer ends with a stray blank line.
- **Shell-tier TLS hangs**: ship a Mozilla CA bundle with the app, materialize it into the toolchain prefix, and export SSL_CERT_FILE / CURL_CA_BUNDLE / REQUESTS_CA_BUNDLE / GIT_SSL_CAINFO / NODE_EXTRA_CA_CERTS in every shell tier. curl/python verify https://httpbin.org now (HTTP 200); without the vars curl fails with CURLE_CACERT, isolating exactly what was broken.
- **Exec bits dropped on workspace mounts**: designated exec-capable scratch dirs (/data/local/tmp/androidharness-scratch for the privileged tier, an app-private mirror for the app uid) where tarballs keep exec bits and symlinks; HARNESS_SCRATCH advertises the right one per tier.
- **Sandbox carve-out** so the shell can use exactly those scratch dirs while everything else outside the workspace stays blocked; near-miss prefixes stay refused, symlink creation is allowed only when every operand lives in a scratch root.
- **Tar extraction routing**: archives extracted into a shared-storage workspace are retargeted into the exec-capable scratch dir with an explanatory note, mirroring the npm --no-bin-links handling.
- **git over HTTPS from the re-rooted toolchain**: export GIT_EXEC_PATH so git-remote-https resolves ("remote helper 'https' aborted session" gone).
- **Timeout process-group leaks and zombie accumulation**: setsid execution plus descendant-tree killing, PR_SET_CHILD_SUBREAPER reparenting, waitpid reaping, Shizuku-shell procfd scanning for orphans.
- **Shell sandbox escapes** (Bug G family): variable/command substitution bypasses ($PWD/../, $(echo ...), $'\x2e\x2e') are blocked; echo/printf/grep literal patterns remain allowed.
- **Background process handling**: logs materialize under .harness/bg in the workspace, all shell redirections handled, fsync on file writes, git index lock backoff retries, cleaner stderr parsing.
- **Prompt caching**: deterministic schema sorting, Anthropic 4-point cache breakpoints, OpenRouter ephemeral cache control, session cluster user affinity, prompt_cache_key for remote hosts, full OpenAI/RunInfra caching JSON field variants, richer cache token extraction across providers.
- **Cost tracking**: exact multi-model cost sums and breakdowns per session, live model costs fetched from the models.dev catalog.
- **Crashes**: IllegalStateException on launch when the Shizuku client is unattached; LazyColumn duplicate-key crash when streaming commits to a message.
- UI polish: static top-left picker/title without running/writing flashes, thinking badge repositioned next to the context button, turn-update helper text, streamlined cache-status calculation.

### Changed

- Thinking level selection removed from the model picker — it lives only in the header badge menus, which offer the full ladder for every model without fallback captions.
- README credits inspirations: Hermes Agent (Nous Research), pi by Mario Zechner, and OpenCode.

