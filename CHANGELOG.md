# Changelog

## 0.3-alpha (2026-08-27)

### Added

- **Hermes-style global thinking ladder** — one canonical scale on every model: Off, Minimal, Low, Medium, High, X-High, Max, Ultra. The raw pick is stored verbatim and resolved at request time: non-native rungs fall down the chain (ultra → max → xhigh → high …) to the closest tier the model supports, floor fallback keeps an enabled ask enabled, and a stronger request never silently bills less than a weaker one.
- **Slash plan** — typing `/plan [instruction]` flips the header into Plan mode automatically AND loads the bundled planning skill into the run prompt (built-in fallback instructions when no plan skill is installed).
- **Stats screen sort + filter** — per-model attribution can be ordered by tokens, estimated price, or request count; price estimates render on every row.
- **file_info tool** for subagents: byte/newline inspection without loading content.
- Active running indicator in sidebar chats, thinking-level badge with model switcher in the header, universal B/M/K token formatting.

### Fixed

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

### Notes

- The release APK is signed with the debug keystore (local alpha distribution; swap signing config before any public/Play release).
