# Changelog

## 0.3-alpha (2026-08-27)

### Added
- **In-app updater** — checks GitHub Releases automatically on launch and manually from Settings → Updates. When a newer release is published, a dialog shows the release notes with every markdown link (and bare URLs) tappable, so changelog.md-style bodies open in the browser. Update installs silently through Shizuku (`pm install -r`) when granted, otherwise it hands the APK to the system installer with an unknown-sources retry path. Download shows animated MB/percent progress. Version matching understands Alpha-v0.3-style tags.
- **Hermes-style global thinking ladder** — one canonical scale on every model: Off, Minimal, Low, Medium, High, X-High, Max, Ultra. The raw pick is stored verbatim and resolved at request time: non-native rungs fall down the chain (ultra → max → xhigh → high …) to the closest tier the model supports, floor fallback keeps an enabled ask enabled, and a stronger request never silently bills less than a weaker one.
- **Slash plan** — typing `/plan [instruction]` flips the header into Plan mode automatically AND loads the bundled planning skill into the run prompt (built-in fallback instructions when no plan skill is installed).
- **Stats screen sort + filter** — per-model attribution can be ordered by tokens, estimated price, or request count; price estimates render on every row.
- **file_info tool** for subagents: byte/newline inspection without loading content.
- Active running indicator in sidebar chats, thinking-level badge with model switcher in the header, universal B/M/K token formatting.

### Fixed


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

