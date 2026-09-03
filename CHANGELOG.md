# Changelog

## 0.8-alpha (2026-09-04)

### Added

- **Agent browser control**: the agent drives a headless WebView through 13 browser tools (navigate, snapshot, click, type, scroll, eval, screenshot, wait, back/forward/refresh, get URL, open URL). Workspace pages serve over an in-app asset loader, screenshots render inline in the chat and save as JPEG under .harness/screenshots, and a floating bubble with a live action trail expands into the preview sheet pinned to the page the agent is on.
- **Per-chat dual planning**: a Dual planning toggle in the chat menu replaces the old global switch. Plan mode runs on the planning model slot, approval flips to Act on the execution slot, the header shows a fork icon with a model-only subtitle plus Plan/Exec suffix, and the pending plan card survives app process death.
- **read_image tool**: the agent reads images from the workspace or past screenshots by filename, with vision frames fed into every provider and graceful text fallback on text-only models.
- **read_logcat tool**: the agent queries Android logs with package, tag, level, and pattern filters.
- **PDF attachments**: attached PDFs extract their text into the message so the agent can read them.
- **Dynamic package installer**: the agent installs Linux packages through a dedicated tool family with a package manager sheet in Settings.
- **Retry in the message menu**: long-pressing your own message offers Retry alongside Copy and Edit, resending the text as a fresh turn.
- **Total estimated cost in Stats**: the hero line adds the window total at list prices, summed with the same per-row math as the By model card.
- **Tap-to-lock voice recording**: tapping the Groq Whisper mic locks recording open directly, alongside the existing hold, slide-up lock, and slide-left cancel gestures.
- **Model picker reloading state**: the chat model sheet shows a spinner with reloading text on manual refresh and a loading row on auto-fetch, and provider add auto-syncs the catalog with Custom endpoint first.

### Fixed

- **Preview chip only on live servers**: the Open Web Preview button renders only when the mentioned localhost port accepts connections, so dead server mentions show no chip.
- **Plan approval loop resolved**: plan runs resolve the role model through the full fallback chain instead of raw slots, approval questions and mutating tools are rejected in Plan mode, and plan text carries no preview directives.
- **Provider catalog counts agree**: the update toast and the add-provider search hint count the same speakable rows, and the directory updates live on refresh.

## 0.7-alpha (2026-09-01)

### Added

- **Aider-style Repo Map**: an automatic codebase indexer that scans project files and generates a compact hierarchical symbol outline (classes, interfaces, methods, functions, and types for Kotlin, Java, Python, JavaScript, TypeScript, HTML, and Shell scripts). The outline injects directly into the agent's system prompt so the model understands codebase structure without burning tool calls on broad exploration, with an opt-in toggle in Settings under Chat behavior.
- **In-app web preview hub**: a full preview environment for web projects. It auto-probes open localhost ports, previews local workspace HTML files, and opens chat links directly in a bottom sheet with Eruda DevTools, responsive device frames, console logs, and a one-tap "Fix this bug" bridge that sends errors directly back to the agent.
- **Voice input and Groq Whisper transcription**: transcribes speech using either Android's native speech recognizer or cloud-backed Groq Whisper (`whisper-large-v3` and `turbo`). Features a floating input layout with live audio waveforms, hold-to-talk, swipe-to-lock, and slide-to-cancel gestures.
- **Visual diff viewer**: a dedicated diff screen with dual line-number gutters, syntax coloring, additions and deletions stats, and horizontal scrolling for side-by-side review of agent file changes.
- **Fork conversation**: fork any chat from any assistant turn into a fresh session with cloned message history, compaction summary, and token usage records.
- **Resume last active chat**: an option in Settings that automatically reopens your most recent conversation on app launch with a shimmering skeleton loading transition.
- **Biometric auto-lock timeout**: configure how quickly the app locks when backgrounded (Immediately, 1m, 5m, 15m) to avoid repeated authentication prompts during quick app switches.
- **Rich syntax highlighting in code editor**: multi-color token styling for Kotlin, Java, Python, JS, TS, HTML, XML, CSS, JSON, and Shell in the built-in file editor.

### Fixed

- **Chat rendering and streaming performance**: eliminated 60 fps main-thread recomposition bottlenecks during typewriter streaming by optimizing selection layout bounds, precomputing message lookups outside LazyColumn item scopes, and moving infinite animation transitions to hardware graphics layers.
- **Biometric authentication reliability**: require biometric or PIN confirmation before toggling app lock in Settings, and fixed a cold-start initialization race where the app briefly bypassed the lock screen before preferences loaded.
- **Code editor multi-line styling**: fixed span generation in sora-editor where identical consecutive token styles were dropped across line boundaries.
- **Security audit hardening**: hardened scratch directories to 0700, added zip-slip extraction validation, scoped DataStore backup exclusions, validated package file paths, and routed MCP OAuth through Chrome Custom Tabs.

## 0.6-alpha (2026-08-31)

### Added

- **Chat backup and restore**: a Chat backup card in Settings exports every chat with its full message history to a JSON file through the system picker, and imports a file back into the chat list. Import skips chats that already exist by id, so re-importing the same file is a no-op. The file holds chats and messages only: no API keys, providers, or settings ever go in, tool-call payloads ride along as the exact strings from the database so old files keep importing, and imported messages are immediately searchable.
- **Full-text chat search**: the drawer search now searches inside messages, not just titles. Hits come from a Room FTS4 index and render with highlighted snippets under their chat, and a fuzzy switch toggles between word-prefix matching and a substring mode that finds code fragments and partial words. Tapping a hit opens the chat.
- **Separate planning and execution models**: a Settings switch that gives Plan mode its own provider and model slot while execute mode keeps another. Each slot is picked with the same model sheet as the header, falling back to the active provider, and mode changes toast which model will run. A one-time dialog introduces the feature the first time you switch into Plan mode, and its Configure button jumps to Settings scrolled to the card.
- **Share target**: the app appears in Android's share sheet for text and images. Shared text or links prefill the composer, and shared images run through the normal attach pipeline, so sending a stack trace or a browser page to the agent is one tap away.
- **@-file mention picker**: typing @ in the composer suggests files from the current workspace (lazily loaded, ignore rules respected), and picking one inserts the path. The system prompt tells the model that @path means read it.
- **Attachments for every file type**: the attach button now offers Any file next to Photo. Text-like files under 32 KB ride inline as fenced blocks on the message, larger or binary files are copied into the workspace under .harness/attachments and referenced by path, and message bubbles show them as name and size chips.
- **Compaction you can see**: automatic and /compact compaction now show a "Compacting conversation" banner while running and leave a visible notice message where the history was folded, and the context panel updates live right after folding instead of showing a stale number.
- **MCP servers reconnect on their own**: servers that connected successfully once are recorded in a status sidecar and reconnect themselves after the app restarts, remote ones immediately and stdio ones once the Linux toolchain is ready. Failures never disqualify future retries, and removing a server clears its marker.
- **Workspace MCP configs need approval**: a .harness/mcp.json inside a cloned workspace no longer connects its servers silently. The chat shows an approval dialog before the run starts, with Approve remembered per workspace path plus a content hash (so an edited file re-prompts) or Run without them; global servers connect exactly as before.
- **AMOLED theme**: a fourth entry in the theme selector that blacks out every surface role while keeping the accent colors from the dynamic or static palette.
- **Allow screenshots toggle**: a new Privacy section in Settings. Screenshots stay blocked app wide by default; with the toggle on they work everywhere except while a key or token is on screen.

### Fixed

- **Shell tier coreutils work again**: ls -la rejected its own flags and echo/printf emitted nothing. Two causes stacked: the linker shims resolved symlinks to the canonical coreutils target, so the multi-call binary lost the invoked name and its argv[0] dispatch broke, and bash 5.3 stopped sourcing BASH_ENV for -c. Shims now route through the bin entry itself (which also repairs git helpers, xz, setarch and busybox applets) and every spawned command sources the shim explicitly; existing installs regenerate the shims on first shell use or app start.
- **Allowing screenshots really allows them**: with the toggle on, Settings, Providers, and Setup no longer force the whole screen secure; only the surfaces that put a secret on screen raise the policy. Dialogs and bottom sheets are covered too, which they never were before: they live in their own windows that the activity-wide flag could not reach, so a screenshot over a key dialog captured the dialog on a black background.
- **The mention picker no longer eats the next keystrokes**: picking a file from the @ suggestions left the text field's cursor at its old position, so typing landed after the @ instead of after the inserted path. Every programmatic composer write (mention picks, share prefills, slash picks, resets) now places the cursor at the end of what it inserted.
- **The Linux environment install reacts instantly**: a Preparing state with a spinner now covers the package index download and dependency resolution that used to look like a dead button for several seconds before the first package progress appeared, in Settings, the setup screen, the chat environment sheet, and the run-time environment card.

### Changed

- **Unused media permissions removed**: READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, and READ_MEDIA_AUDIO are gone from the manifest. Nothing referenced them; all file and media access goes through the Storage Access Framework and the all-files grant.
- **Credentials stay out of cloud backups**: the encrypted API key preferences, the MCP server config with its plaintext tokens and HMAC sidecar, and the GitHub token copies inside the deployed toolchain are all excluded from Android cloud backups and device transfers, which also keeps the multi-hundred-MB toolchain from blowing the 25 MB backup quota.

## 0.5-alpha (2026-08-29)

### Added

- **MCP servers on every transport**: connect Model Context Protocol servers over stdio, Streamable HTTP, and the legacy HTTP+SSE transport, with session handling, protocol version headers, and both JSON and SSE response modes. Adding a server is paste-first: drop in a Claude mcpServers JSON block, a single server object, or a `claude mcp add` command line and see a live preview before saving. The manual editor covers command, args, env, URL, and headers per transport type, and a workspace `.harness/mcp.json` merges its servers into that workspace's chats.
- **Sign in to MCP servers with OAuth**: when a server answers a request with a 401 challenge, Settings shows an Authenticate button. The flow implements the MCP authorization spec end to end: protected resource and authorization server metadata discovery, dynamic client registration, PKCE S256, the RFC 8707 resource parameter, a deep link redirect back into the app, code exchange, and automatic token refresh, with tokens stored in the encrypted keystore. Verified live against a real OAuth provider (Supabase), where 14 server tools connected and ran.
- **Web search with real API keys**: Brave Search and Tavily join the keyless engines. The compact settings card has a three way provider selector, a Get API key button that opens the provider's key page in the browser, and a save flow that verifies the key with the provider before storing it, so a bad paste never becomes the saved credential. Keys live in per provider slots so switching engines keeps both, and an API failure falls back to the keyless engines with a note.
- **Subagent model override**: the task tool accepts a model id from the provider's live catalog, so parallel subagents can run on different models. Unknown ids are refused with the valid list before anything spawns, the catalog fetch is shared across all parallel tasks in a run, and token usage attributes to the override model. The task title now shows in subagent progress lines.
- **MCP config integrity**: every app-side save of mcp-servers.json writes an HMAC sidecar keyed by a non exportable keystore key. A file edited out of band is refused, its contents are reset, and Settings shows a red banner instead of silently running tampered server definitions.
- **Security hardening from the on-device stress test battery**: the deployed toolchain's git config directories tighten to 0700, memory_write refuses topics that would be silently renamed (reads stay lenient so old names still resolve), and the full access permission mode now states its trust boundary in plain words in Settings.

### Fixed

- **git_show stat mode shows stats again**: `--no-patch` suppresses stat output in any position, so the message plus stats mode now runs `show -s --stat` and really delivers both.
- **Commits stop printing "fatal: cannot exec maintenance"**: every git invocation disables auto gc and maintenance, which the sandbox could never exec anyway.
- **web_search reports what it did**: the `[via]` tag names the engine that actually answered, and an explicitly requested engine while an API backend is active gets a note instead of silently returning different results than asked for.
- **The MCP Test button surfaces sign-in immediately**: Test records the same statuses a real connect does, so a 401 found by Test shows the Authenticate button right away instead of only after a run.
- **The MCP add dialog segmented control lays out correctly**: one short word per segment keeps the type row on a single line.
- **Keystore loss no longer crash-loops the app**: when the encrypted preferences file cannot be decrypted (credential reset, or a backup restored onto a fresh install), the app resets it and starts over empty instead of dying at startup.

### Changed

- Every em-dash is gone from the app's text; string messages got natural punctuation and code comments became commas.
- A stable copy of the debug signing key is kept in the gitignored signing-keys/ folder after a keystore regeneration broke plain reinstalls.

## 0.4-alpha (2026-08-28)

### Added

- **Workspace file manager** — the sidebar "Workspace files" screen is now a full manager. Long-press any file or folder for rename, copy-to, move-to (with a folder picker that refuses dropping a folder into itself), delete, share to other apps (staged through the app cache via FileProvider), and open externally. Header actions create new files and folders; a filter field narrows the current directory. Works identically on app-private, full-access and SAF (picked-folder) workspaces.
- **Real code editor** — the old viewer + plain text field is replaced by a sora-editor screen: gutter line numbers, unlimited undo/redo, incremental keyword highlighting per language, a find bar with match-case and regex toggles plus replace/replace-all in edit mode, word-wrap toggle (on by default), go-to-line, and an explicit save flow (dirty indicator, unsaved-changes prompt on back). Saves restore the file's original BOM/charset/EOL shape instead of silently normalizing it. Binary and 16 MB+ files show an info card with Share / Open-with instead of corrupting as text. Drawer edge-swipe is disabled on the editor screen.
- **Per-chat "Files changed" (GitHub-style)** — every file the agent edits in a conversation is tracked cumulatively per session: the file manager shows green "+N −M" badges per file plus a summary strip, a drawer pill surfaces the open chat's change set, and a Files-changed screen lists every touched file (green dot for new, amber for modified, red for deleted) with tap-to-expand diffs rendered against a gzipped snapshot of the file's content at session start — baselines are pinned on first touch, so diffs stay reviewable even in old chats, and manual editor saves feed the same tracker. At the end of every turn the edited files also render as a collapsible card (total +N −M expanding into a per-file list with tap-to-open).
- **Full access mode** — a fourth permission level (orange, in Settings → Agent and the chat ⋮ → Permission menu) that lifts every sandbox layer at once: no approval prompts, no shell command denylist, no workspace path containment. File tools can read/write any path on the device (absolute paths work), the shell runs from any cwd, and subagents inherit the same freedom. The system prompt tells the model the sandbox is lifted. SAF (picked-folder) workspaces keep their containment — there is no shell root to extend.
- **GitHub auth that survives toolchain reinstalls** — the token's master copy lives in the app's encrypted settings and is re-materialized into both shell tiers on every start and deploy: `~/.gh-token` (0600) for agents, a commit identity, and a `url.*.insteadOf` rewrite that authenticates every `https://github.com` URL. Login lives at the top of Settings: paste an existing token or tap Get access token (browser deep link, with scope toggles for workflow/gist/read:org/delete_repo), and Save-and-check validates it against api.github.com first, showing the verified plan and scopes afterwards. Log out clears the token and every toolchain copy — for real (see Fixed).
- **gh CLI joins the standard toolchain** — GitHub's official CLI (Termux gh 2.98.0, ~10 MB) is part of the package set: fresh installs include it, existing installs pull it via Settings → Terminal & environment → Update, and env_status probes it. With a token set, gh is pre-authenticated automatically via a materialized `~/.config/gh/hosts.yml`.
- **doctor --github diagnostics tool** — one command for the whole GitHub connection: a single API ping (verified login, account type, the real plan name from /user, granted scopes from X-OAuth-Scopes with consequences spelled out for missing delete_repo/gist/read:org/workflow), the token file's real permission bits, git's helper shell (`git var GIT_SHELL_PATH` + exec check) and the insteadOf rewrite, and a deterministic Free-plan trap probe: rulesets/branch-protection return 403 "Upgrade to GitHub Pro" for private repos on free accounts — which reads as "no protection configured" while force-pushes succeed — so it probes the first recently-pushed private repo (GET-only, labeled as such) and one org's, and warns explicitly when protection is paywalled.
- **http_request speaks GitHub** — requests to api.github.com and uploads.github.com automatically carry the stored token as Bearer auth (the 60 req/h anonymous rate limit becomes 5000 and private repos stop 404ing); `github_auth: true` extends auth to other github.com/githubusercontent.com hosts, `github_auth: false` forces anonymous, and the host policy structurally confines the token to GitHub hosts so it can never ride to a third-party URL (listener-probe verified against wildcard-DNS lookalike hostnames). Explicit user Authorization headers win; a 401/403 on an authenticated request hints at doctor --github; missing-token requests get a setup note.
- **Undo becomes a full rewind** — a confirmation preview plus chat roll-back instead of message-level undo.
- **ask_user upgrades** — multi-select options, and bullet-list options baked into question text are recovered into real choices across every model shape, with a redesigned question card. A bundled grill-me skill turns interviews into idea-forcing sessions, and `/plan` stays in the composer.
- **Terminal & environment flow** — a real Check-missing → Update flow in Settings, an `/env` chat command, uninstall confirmation, and a one-time "new packages" popup for existing installs when the package set grows (gh was the first late package).
- **Navigation polish** — the workspace explorer moves to the chat top bar with the workspace switcher inside the file manager, and sidebar search becomes a top-right reveal button.
- **StreamRetrier** — the three inline SSE retry loops consolidate into one shared implementation.

### Fixed

- **Security: the deployed toolchain no longer leaks the GitHub token** — `etc/gitconfig` (which embeds the token in the insteadOf URL) shipped 0644 inside an o+x-traversable /data/local/tmp with 0777 top dirs, readable by ANY installed app that guesses the path. The deploy now chmods the top-level dirs 0700 and all three auth-bearing files 0600, and the staging tarball carries no auth at all — the deployed prefix receives its credentials via a direct privileged-shell write (base64 payloads, 0600) after every deploy.
- **GitHub logout actually logs out** — it used to clear only the app's encrypted copy while `.gh-token`, gh's hosts.yml and the insteadOf rewrite survived in the deployed prefix, so git push and gh api kept working with the supposedly-logged-out token; propagation depended on a full re-stage/redeploy whose failures were silently swallowed. Auth changes now take the direct write path that cannot be skipped, env_status warns "STALE SHELL CREDENTIALS" when the app has no token but the deployed copy still does, and the token file's permission bits are really stat'ed instead of hardcoded ("(0600)" was printed while a 0755 copy shipped).
- **GitHub logout/login no longer ANRs** — the re-stage/redeploy chain gzipped the entire toolchain prefix on the caller's dispatcher and Settings' logout coroutine ran on main, freezing the UI into "input dispatching timed out". The chain switches to Dispatchers.IO internally and the UI updates immediately; token changes also no longer force a 30–60s redeploy (the token fingerprint left the staging hash — auth rides the direct sync, and the tarball is quietly re-staged so it never goes stale).
- **Git can spawn helpers again** — the Termux git ELF's compiled-in SHELL_PATH (/data/data/com.termux/files/usr/bin/sh, dead outside Termux) is patched in place to /system/bin/sh at extract time and self-healed on existing installs, so `gh repo clone`'s credential helper, hooks and aliases work in the privileged tier; the insteadOf rewrite remains the always-works transport in the app-uid tier (W^X). The generated gitconfig sets `init.defaultBranch = main` and no longer writes a `credential.helper` reset.
- **env_status tells the truth** — headline tools are resolved with `command -v` in the exact tier the shell will use (blind File checks reported working tools as missing), a failed probe reports presence UNKNOWN ("redeploying…") instead of "missing: everything", and the GitHub section reports real auth state plus the shebang and HTTPS-transport limits agents need to know.
- **doctor accuracy** — the insteadOf check queried the wrong config key (always reported ABSENT) and the free-plan listing sent type=private together with affiliation=owner, which GitHub rejects with 422 ("state unknown"); both probes are deterministic now.
- **Toolchain repair** — Termux-absolute shebangs are rewritten into the deployed prefix instead of dropping the scripts (python/pip/npm stop vanishing from existing installs), interrupted installs resume and packages whose binaries vanished are reinstalled at startup, installs are serialized against marker races, Termux-absolute symlinks are rewritten as relative links, and dead Android-bridge shim scripts (pm/cmd/am…) are purged so they stop shadowing system binaries.
- **git tools on foreign-owned and non-repo workspaces** — every git invocation carries `-c safe.directory=*` so repositories owned by another uid stop failing with "dubious ownership" (persisted into ~/.gitconfig once as a fallback), `git_status`/`git_diff`/`git_commit` auto-init non-repo workspaces and retry, and git_commit no longer sweeps `.harness/` runtime artifacts into commits.
- **Raw tokens leaked into the chat DB inside URLs** — the redactor caught header-style secrets but missed tokens embedded in clone URLs printed by GIT_CURL_VERBOSE. It now strips `ghp_/gho_/ghu_/ghs_/ghr_` and `github_pat_` tokens anywhere, the `user:password@` userinfo of https URLs, and long token-shaped values behind credential-ish keys.
- **On-device QA batch** — atomic apply_patch (a failed hunk writes nothing), per-session background-process log isolation, case-collision warnings before creating names that alias on case-insensitive mounts, read_file strips UTF-8 BOMs, file_info streams procfs files instead of trusting st_size 0, write_file preserves interior CR bytes (SSE line framing no longer tears CRLF content), and the asstest skill is added for harness self-checks.

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

