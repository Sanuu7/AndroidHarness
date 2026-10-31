# AndroidHarness

<p>
  <a href="https://github.com/Sanuu7/AndroidHarness/releases/latest"><img src=".github/assets/download-from-github.png" alt="Download from GitHub" height="54"></a>
  <a href="https://play.google.com/store/apps/details?id=com.androidharness.app"><img src=".github/assets/google-play.png" alt="Get it on Google Play" height="54"></a>
  <a href="https://buymeacoffee.com/sanuj7"><img src="https://cdn.buymeacoffee.com/buttons/v2/default-yellow.png" alt="Buy me a coffee" height="54"></a>
</p>

[![Debug build](https://github.com/Sanuu7/AndroidHarness/actions/workflows/nightly.yml/badge.svg)](https://github.com/Sanuu7/AndroidHarness/actions/workflows/nightly.yml)
[![Version](https://img.shields.io/badge/version-3.1-blue)](https://github.com/Sanuu7/AndroidHarness/releases/latest)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3ddc84)](https://developer.android.com/about/versions/oreo)
![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)

**App Languages:** English · 简体中文 (Simplified Chinese) · Русский · العربية · فارسی · Português (Brasil) · Deutsch · Español · 日本語 · Bahasa Indonesia · සිංහල (Sinhala)

**An AI coding agent and conversation partner that lives on your Android phone.**

AndroidHarness is a native Android app, written in Kotlin with Jetpack Compose, that works on real code projects directly from the device. It reads and edits files, runs shell commands in a real Linux toolchain with bash, git, python, and node, pushes to GitHub, drives a browser, and streams the whole process into a chat you can inspect. A separate Chat section handles everyday questions, research, writing and brainstorming with automatic read-only tools, and hands project work to Code when you choose. No PC required, and No root.

<!-- Screenshots: add two or three device captures to docs/images/ and uncomment this block.
<p align="center">
  <img src="docs/images/chat.png" alt="AndroidHarness chat with streaming answers and tool call cards" width="260">
  <img src="docs/images/diff.png" alt="Side by side diff viewer opened from a chat" width="260">
  <img src="docs/images/editor.png" alt="Code editor with syntax highlighting and line numbers" width="260">
</p>
-->

## Highlights

- **A coding agent, not just a chatbot.** File edits, shell, git, tests, browser control, subagents, and a card for every tool call that you can open and inspect.
- **A conversation section that stays a conversation.** Chat answers questions, researches with sources, writes, and brainstorms without opening a workspace. Web and GitHub tools are read-only, memories are shared with Code, and a Continue in Code card hands project work over when you choose.
- **Personal Chat agents.** Create reusable assistants in Settings → Chat options → Chat agents, or choose an agent in Chat. Each can have its own instructions, persona, model, tool access, instruction-based skills, conversation starters, and text/PDF knowledge. Conversations retain their starting setup; memory and history stay private to the agent unless you enable shared access.
- **Rich subject cards and more readable research.** Chat can introduce a person, place, or organization with a sourced profile card, a real image, and key facts. Page reading uses structured article/forum content when available and automatically tries browser rendering for JavaScript-only pages; failed extraction services fall back to the original page. Access blocks and incomplete discussions remain clearly identified.
- **Free to start.** The built-in keyless provider works with no sign-up, and local GGUF models run fully offline. Cloud keys and ChatGPT plans are optional.
- **A real shell on a phone.** Commands route through Shizuku, Wireless ADB pairing, optional direct root, a Termux toolchain, or a toybox fallback, with a shell policy and secret redactor on top.
- **Long runs survive interruptions.** Foreground service, resume cards, a persistent message queue, and optional auto-continue keep work safe across screen-off stretches and connection drops.

### New in 3.1

- **9Router.** Settings → 9Router installs and manages the 9Router local model router on this phone: npm install inside the Linux toolchain, start, stop, update and uninstall, auto-start with the app, and one-tap provider setup that connects its local endpoint and API key so its models appear in Chat and Code. While it runs, the service holds a background wake lease without advertising an active task.
- **Custom palettes and Harness Canvas.** Build a theme from your own main, accent and highlight colors, with Material generating readable light and dark shades from up to 24 saved palettes. Or set a photo as the backdrop for Chat and Code, with dimming for readable text and an option to derive the app's colors from the image.
- **Local model performance tests.** Find best settings sweeps generation and prompt thread counts on CPU, GPU or both, using fixed prompts, greedy decoding and a 32-token cap per configuration. A Performance tab shows live device telemetry: memory history, per-core clocks and load, GPU and NPU drivers, thermal status, battery and specs.
- **Faster local follow-ups.** Models stay loaded between turns and continuing a chat reuses the previous prompt prefix, with the reply reporting how many prompt tokens were reused. Prompt processing gets its own thread setting, and Arm64 builds use KleidiAI kernels.
- **Tool activity reader.** Any tool result in the activity log opens full screen with Input and Output tabs, pretty-printed arguments, paged output and copy.
- **Chat multi-select.** Select several chats in the sidebar for batch archive or restore with undo, a combined details dialog, and batch delete that stops running tasks and deletes histories in one transaction.
- **Also in 3.1.** Reply tables expand into a full-screen view and the table guidance now allows qualitative comparisons; local models get a thinking behavior choice; and release builds are no longer minified.

## Quick start

1. Install from [Google Play](https://play.google.com/store/apps/details?id=com.androidharness.app), [GitHub Releases](https://github.com/Sanuu7/AndroidHarness/releases/latest), or build from source below.
2. Grant storage access. On Android 11 and up the app needs All files access so the shell and file tools can use real filesystem paths.
3. Pick a model. Start instantly with the built-in keyless provider, add an API key in Settings, or download an on-device model from Settings → Local models.
4. Optional but recommended: install [Shizuku](https://github.com/RikkaApps/Shizuku), pair Wireless ADB in Settings → Terminal & device, or install the Linux environment inside the app, so shell commands run with proper permissions. On a rooted device, Settings → Terminal & device can also enable optional direct root.

Open a workspace, point the agent at a project, and ask for a change, or open Chat for a question that does not need a project. `/doctor` self-tests every tool family whenever something feels off.

## Features

### Chat

- **A separate conversation section.** Switch between Code and Chat from the drawer or desktop sidebar. Chat keeps its own recent conversations, drafts, model choice and start preference, and has no project files, shell, terminal or device tools.
- **Chat agents.** Choose Regular Chat or a saved agent from the composer. Each agent has its own name, description, instructions, personality and tone, model and thinking level, knowledge files (text, Markdown and readable PDFs up to 8 files and 96,000 characters), tool access and memory mode. Relevant knowledge passages are searched locally and supplied when needed, changes apply to new conversations, and existing chats keep the setup they started with. Deleting an agent keeps its conversations, knowledge snapshots and saved memories.
- **Pin, archive, select and context.** Pin chats to the top or archive them out of recents, with Undo in the chat menu and the sidebar. Select starts multi-select: batch archive or restore with undo, a combined details dialog, and batch delete that stops running tasks first and deletes histories in one transaction. Chats are peers in navigation, so switching conversations replaces the open one and Back returns to the previous screen. Chat has its own 250,000-token ceiling and compacts automatically as the window fills, keeping the latest user turn, the summary and pinned instructions while the full history stays visible.
- **Image inspection.** `view_chat_image` lists and reads images from the conversation, including attachments from earlier turns and browser page screenshots, and `zoom_chat_image` returns a magnified region. Inspected images keep a stable identity, appear inline in the final answer, and a failed inspection is reported instead of returning nothing.
- **Automatic read-only tools.** Chat searches the web, reads pages, queries GitHub, reads user-supplied text and attachments, and uses universal memory without a tool toggle. GitHub tools cover repositories, issues, pull requests, commits, releases, users and code with the connected login or token, and never write.
- **Research depth and sources.** Pick Off, Instant (up to 5 sources), Deep (20), Auto, Deep research (50) or Max research (100). `web_search` enforces the per-turn budget, accepts freshness, language and country filters, uses the selected keyless, Brave or Tavily search API, and runs keyless engines concurrently. `web_fetch` extracts the readable article, pages long text with offset and limits, finds passages, reads PDF text, and marks pages read against the turn's budget. Answers cite sources inline, and Sources in this chat collects every source used.
- **Rich replies and activity.** Markdown and native rich replies render inline, including offline interactive previews, tables, tabs, galleries, multi-series charts, calculators, evidence, diffs and writing blocks; Chat does not create saved artifacts. LaTeX and Mermaid diagrams render from the answer, and substantive replies end with tappable follow-up suggestions. Thoughts, commentary, tool calls and sources stay in one chronological, expandable timeline with live thinking duration and token metrics, and any tool result opens full screen with its input and output. Thinking is collapsed by default and can stay that way in Settings → Chat, and the down button always points at the true bottom.
- **Answers you can work with.** Attach images, text files and text-based PDFs up to 64 MB, with reading, ready, too-large, unsupported and failed states; sending waits until every attachment is ready. Answers can include inline images, with link previews, a YouTube player, and a full-screen viewer that pinches, saves and shares. An Image frequency setting runs from No image to Always, with a per-chat override. Find in chat, Quote the selected answer into the composer, Regenerate a reply, and browse earlier versions of an answer without replacing later history. Interrupted replies offer Continue reply, and draft markers show unsent drafts in the chat list.
- **Instructions and recall.** Settings → Chat Option holds global instructions for tone, format and scope that every Chat uses, and each conversation can keep or replace them for itself, up to 4,000 characters. A Personalization switch keeps the stored text but turns off instructions and saved memory for that chat. `search_chats`, `recent_chats` and `read_chat_snippet` look up earlier conversations and read a small window around a hit instead of a whole chat. Shares from other apps open a destination sheet first, with a new or existing chat and an optional question.
- **Continue in Code.** When a conversation turns into project work, the model can offer a Context card with a title, reason and workspace picker. Choosing it carries the Chat plan into a new Code conversation and runs nothing until you send. Only a successful offer in the current turn supplies card copy.
- **Start screen and section settings.** Settings → Chat chooses Code, Chat or Last selected at launch, whether Chat opens a new or last conversation, and whether thinking stays collapsed. Code keeps its own New/Last choice, and Settings is split into Chat and Coding groups.
- **Universal memory.** Chat and Code share the same memory, managed in Settings → Memory with global or project entries, Chat, Code and Shared sections, categories, pinning, search, edit, delete and delete-all. Each section has its own switch, the other section can be looked up only when allowed, and in-chat receipts show which facts were supplied and let you edit or forget them.

### Code conversations

- **Telegram remote control.** Pair a private bot account in Settings → Telegram to continue real chats, choose projects and models, edit files, approve tools and answer agent questions while the app is minimized. A persistent notification offers Pause and Stop, and saved requests/replies remain recoverable after a connection failure.
- Full markdown chat with streaming responses, thinking blocks that show the provider's summary (long reasoning renders lazily), and a tool call card for every action the agent takes. Thinking starts collapsed, the live clock includes tool time, and token counts never drop mid-turn.
- **Live task progress.** The agent's current task list is sent with every round, and after three tool rounds without an update it is reminded to mark completed milestones and the next in-progress task. Tool batches keep todo_write in its requested position, so the progress card updates before slow subagent work.
- **Browser shortcut in the header.** When a message contains a web or localhost link, or the workspace has an HTML file, the header offers a browser shortcut that opens the preview at its sources.
- **Rich replies.** Assistant answers can mix text with native cards, callouts, metrics, interactive charts, comparisons, checklists, expandable explanations, link cards, tables, tabs, galleries, calculators, evidence, diffs, writing blocks and custom interactive previews. LaTeX and Mermaid diagrams render offline, and follow-up suggestions become tappable chips. Choices and preview controls can prepare a follow-up in the normal composer. Existing versioned artifacts remain available.
- **Interactive concepts in chat.** Ask for a mockup, chart, diagram, or small interactive tool. Tool-capable models can create offline HTML/CSS/JS previews with Preview/Code tabs, fullscreen, revision history, Revise, and Save HTML. Concepts stay with the chat through restarts, backups, and forks.
- Responsive markdown tables with compact card previews and an expandable full-sheet viewer with horizontal scroll.
- File diff sheets open right from tool call cards, so you can inspect a change without leaving the conversation.
- Turn metrics: response duration, token generation throughput measured over the streaming window itself, first-token latency, and token counts in turn stats.
- Consecutive tool calls roll up into one compact activity card that expands in place with per-call status indicators, and any tool result opens in a full-screen reader with Input and Output tabs, pretty-printed arguments, paged output and copy. Groups larger than twelve rows use a bounded list, so long activity stays responsive and its last results remain reachable.
- Inline Writing/Edit rows show filenames and animated added/removed line counts while file arguments stream. Tap a row for the syntax-colored preview and token details. Successful writes retain measured counts in chat history. Toggle them in Settings → Chat & commands → Show live code. Providers that return complete arguments at once show the row when those arguments arrive.
- Verification cards: turns that touch files or run checks end with a list of every build, test, or lint command and its recorded outcome (passed, failed, or unconfirmed). Checks invalidated by a later edit are marked for a recheck.
- Voice input with live waveforms, transcribed by Groq Whisper (`whisper-large-v3` / `turbo`) or native Android speech. The recorder is a compact capsule with a waveform and timer in the composer. Tap the mic to lock recording, or hold with slide-up lock and slide-left cancel, and a running transcription can be cancelled.
- **Read replies aloud.** Every assistant message has a speaker control that reads it with the device voice or a Groq PlayAI voice, with a spinner while a Groq clip is generated and a stop control while it plays. Settings → Voice & speech → Voice output picks the engine, model, voice and speed (0.75× to 1.5×), tests the voice, and can read every new reply automatically, including the commentary between tool calls. Code, tables, links and other markdown are cleaned up before speaking, long replies are split to each engine's limit, Groq clips are prefetched so sentences do not gap, and generated audio is cached.
- Fork any assistant turn into a fresh session with cloned context.
- The last active chat resumes automatically on launch behind shimmering skeleton loading.
- Long chats open with the most recent messages. Load older messages when needed; pages keep complete turns together, search still jumps to older results, and paging does not change the context used by the model.
- **Workspaces per chat.** A chat can work in one workspace or several: open Workspaces for this chat from the drawer or the desktop sidebar, tick the projects it should see, set Can edit or Read only for each, and pick the primary where plain paths and new files land. It also works on a draft chat before the first message, without losing what you typed. Every tool call names the workspace it targets, read-only projects refuse edits (Full access does not lift this), commands and Git step aside while any selection is read only, and undo keeps each project's files separate. Other chats and their running tasks stay in their own workspaces.
- **Projects.** Each project brings together chats, automations, repository, memory, Git branch and pending changes, the last named Build & Test check, and a running or static preview. Files, Terminal and Build & Test open directly in that project without changing another chat's workspace or creating a chat. Project details lets you rename it or delete it after a review that lists its chats and automations, refuses while work is running or another project still uses the workspace, and keeps the folder on disk. New chats belong to the project they were started from.
- **Project memory.** Chats in a project share one notes file, edited from the project screen and counted against the same limit the agent uses. Saving compares against the version you loaded, so a draft written while the agent was updating the notes is refused with a reload prompt instead of overwriting newer work, and saving waits while a task is running in that workspace.
- Message queue for follow-ups, with edit, reorder, remove, and Send now. The queue persists across app restarts, each message starts after that chat's successful Run finished notification, and interruptions keep it waiting.
- Long-press your own message for Retry, Copy, and Edit. Retry resends it as a fresh turn.
- The agent can ask you something mid-run. Question cards stage single-choice, multiple-choice, or written answers, show exactly what will be sent, and wait for an explicit Send answer. You can also answer from the notification shade.
- Optional Caveman reply modes: terse, compressed responses with an intensity dial and optional skill enforcement, configured in their own settings screen.
- Chat backup: export every chat with its full message history to a JSON file and import it back on any device. Backups now record workspace names, so import previews which workspaces are missing and lets you reconnect each one to an existing project or a newly added folder; restored chats keep their primary workspace and read-only grants. The file holds chats and messages only, never API keys or settings.
- Encrypted settings backup: API provider setup, custom API model IDs, and preferences in one encrypted file, with API keys and MCP configuration included optionally. Local models and their selections are excluded. A failed export names the setting that blocks it instead of a generic error, provider endpoints that work in chat validate here too, and the password can be as short as 6 characters.
- Workspace layout in Settings → Appearance: Auto, Phone, or Desktop. Wide windows use a desktop workspace with a persistent project/chat sidebar, a focused conversation, resizable file and change panels, and a right-side terminal panel. Narrow windows keep a compact layout. Appearance also offers Flat or Squiggly progress bars with live previews, and controls use Material expressive press shapes and motion.
- Desktop controls: right-click, long-press, or use a chat's menu to manage it. Tab/Shift+Tab moves focus; Ctrl+L focuses the composer, Ctrl+Enter sends, Ctrl+N starts a chat, Ctrl+Shift+B toggles the sidebar, and Ctrl+Alt+F/T/G opens Files/Terminal/GitHub. Ctrl+S saves in the editor. Enter runs a terminal command; Up/Down recalls commands.

### Scheduled automations

- Schedule a message from the composer's actions menu: pick the date and time, how it repeats (once, daily, hourly, or manual), which chat runs it, the workspace, and the model. The chat shows its scheduled messages above the composer, and every schedule can be paused, resumed, or cancelled from the Automations screen.
- Delete an automation from its card after a confirmation. Active and scheduled runs stop, while the chat history and previous run results stay available.
- Occurrence-safe runs: worker retries, resumes after an interruption, and app restarts never send the same prompt twice. Completed tools and queued messages stay in place, a busy chat makes the run wait its turn, and taking the chat over yourself stops the schedule for review instead of fighting your changes.
- Unmetered-only schedules wait for a connection without a data limit, such as Wi-Fi, so a run never burns metered data.
- Recurring or interval-based prompt tasks that run in the background through Android WorkManager.
- An AI-assisted planner that translates plain English instructions into a cron-like schedule and its parameters.
- An editor bottom sheet with quick suggestion chips, per-automation model selection, and manual run triggers.
- Run history logs, execution status indicators, and background completion notifications.

### Agent tools

- **Files.** read, write, edit, search, grep, list, move, delete, fuzzy multi-edit, and apply_patch, which checks every hunk header and line count before it touches a file and rolls back atomically on failure. Writes use a temporary file and atomic replace, preserve permissions, and refuse non-UTF-8 files instead of corrupting bytes. Search and grep page through sorted results with offset and limit, and `read_tool_output` pages through an earlier tool result without re-running it. Moves refuse to overwrite an existing destination unless you pass `overwrite=true`. The agent reads images by filename and extracts text from attached PDFs. `file_history` lists durable gzip copies saved before and after writes, undo and restore, and `file_restore` recovers a missing file or replaces an existing one only after checking its current SHA-256; copies survive restarts and chat truncation, up to 32 MB per file.
- **Shell.** Run commands with timeouts, launch and manage background processes, install Linux packages, and query Android logs with package, tag, level, and pattern filters. Package installs report resolving, downloading, verifying, unpacking and configuring phases, the dependency closure, a completed-package count and byte-level totals in a dedicated card, and background jobs record their real exit code and whether they exited or were killed. Commands follow the selected access mode: Auto, Normal, Shizuku, Root or Both. Both keeps Shizuku and direct root live together, using Shizuku for ordinary work and the root service for `access="root"` calls in the same task. `access="shizuku"` pins a command to Shizuku and never falls back to the app UID or root; app-workspace development uses the app UID by default. A running command can be stopped, and cancelling its task ends the local process group or the Shizuku or root command instead of leaving it in the background.
- **Git.** status, diff, commit, log, show, branch, checkout, push, and pull, with git identity auto-configured so commits never fail on "author unknown".
- **Web.** Search through keyless engines or the Brave and Tavily APIs with a key, with freshness, language and country filters and reported publication dates; fetch readable page text with paging, passage search, PDF extraction and optional image URLs; make raw HTTP requests with JSON bodies, restricted to public addresses (localhost, private ranges, and other non-public destinations are refused, DNS answers are validated before connecting, and redirects are not followed); call the GitHub API with automatic authentication.
- **Environment guide.** Every request carries a live description of the device environment, including the access mode, root and Shizuku state, default command route and workspace rules, built from the tools actually attached to that request. The bundled `androidharness-environment` skill adds references for workspaces, device access, packages, builds and diagnostics, and `env_status` reports real Java and D8 paths, including repaired launchers and broken build packages that need reinstalling.
- **Web preview and browser control.** A preview hub for localhost ports, workspace HTML files, and web links, with Eruda DevTools, console logs, and one-tap bug fixing. The agent drives the page itself through browser tools (navigate, snapshot, click, type, scroll, eval, screenshot). Each call renders as its own card with the page, site, action, and status; Watch opens the preview at that call's page even if a later call navigated elsewhere, saved history keeps the cards, and the preview can stop a running agent. Select any element to send its selector, computed styles, console errors, and a screenshot straight to the agent as a fix request.
- **Workspace configuration.** Use `harness.json` or `harness.jsonc` for shareable instructions, references, skill paths, Code agent profiles, memory, MCP and restricted plugins. Manage project and global configuration in Settings → Coding → Configuration. See the [configuration guide](docs/configuration/README.md) and [JSON Schema](docs/harness.schema.json).
- **Plugin manager.** Discover starter packs and community npm packages in Settings → Coding → Plugins. Review compatible bundles from npm, GitHub, ZIP/TGZ archives or phone folders, then install skills, prompt instructions, MCP definitions and isolated hooks for one workspace or all Code workspaces. Enable, disable, update and uninstall without changing your project configuration. See the [plugin guide](docs/configuration/plugin-manager.md).
- **MCP.** Connect Model Context Protocol servers over stdio or HTTP, add them by pasting a Claude config or a `claude mcp add` command, and sign in with OAuth when the server needs it.
- **CodeGraph.** Install it from Settings, enable a local index per workspace, and the agent can explore symbols, callers and callees, change impact, affected tests, and incremental sync with no separate configuration.
- **Subagents.** The task tool spawns subagents that work in parallel on independent chunks, each optionally on a different model. They research read-only by default; with subagent action tools enabled they also edit files and run commands in Act mode, always under the current permission mode.
- **Subagent settings.** Settings → Sub-Agent settings gives all subagents a separate provider and model, or keeps them on the main agent's model. A saved subagent model takes priority over the task tool's model override.
- **Skills, todos, and memory.** Skill tools list, view, and manage the markdown skills library from inside a run. A live todo list, a core memory file that loads at the start of every conversation, and searchable topic files for everything else.
- **Universal memory.** Settings → Memory manages facts shared by Chat and Code: Chat, Code or Shared sections, global or project scope, profile, preference, project, decision and fact categories, pinning, search, add, edit, delete and delete-all. `remember` and `recall` work in both sections, each section can be switched off, the other section can be looked up only when allowed, a bounded set of relevant memories is added to each request, in-chat receipts show which facts were supplied and let you edit or forget them, an organized digest can replace related entries while the originals stay restorable, and memories travel in chat and encrypted settings backups.

### Files and editor

- Workspace file manager: multi-select batch operations (delete, copy, move via destination picker), create, rename, and share files and folders, with open-in-other-apps support.
- Code editor with tabs, Quick Open (Ctrl+P), the command palette (Ctrl+Shift+P), project search and replace (Ctrl+Shift+F), multi-color syntax highlighting across Kotlin, Java, Python, JS, TS, HTML, CSS, and Shell, line numbers, unlimited undo and redo, regex find and replace, persistent word wrap and match options, and encoding preservation. The active tab, order, cursor, and scroll position survive restarts.
- **Offline language tools.** Completion, outline, go to definition, find references, rename, and formatting run in a bundled local worker with no network or file access. JS/TS get the full set; HTML, CSS, SCSS, LESS and JSON get completion, outline and formatting, with CSS-family definitions and references inside a file; Markdown gets an outline and formatting; YAML gets formatting. Rename opens reviewed drafts across files, and formatting stays undoable.
- **Recoverable drafts.** Unsaved text is written to app-private storage and restored after rotation, closing the editor, or process death, including the original encoding, BOM, and line endings. Recovery is scoped to the chat, project, workspace, and file, and a status line says when a draft is saving, saved, recovered, or failed.
- Diff viewer with side-by-side and inline modes, dual line gutters, syntax coloring, and change stats.
- Files changed tracking per chat with rewind: full-file undo and selective section undo. Undo checks that the preview still matches the file and preserves unrelated sections. Ask about changes sends a whole-file diff or a single section to the agent for an explanation.
- Select code in the editor and use Ask agent for Explain, Fix, Refactor, or Write tests. The chat opens with the selection, its file and line range as a draft prompt, marked when the buffer had unsaved changes, and nothing is sent until you review and send it.
- **Safe saves.** Saving waits while an agent is working in the workspace. If the file changed after you opened it, the editor shows Compare, Reload, and Keep my edits instead of silently replacing the newer text.
- **FTP and SFTP sync.** Settings → Connections → FTP / SFTP sync previews uploads and downloads between a local workspace and a remote folder, reports new, replaced and unchanged files, asks before replacing anything, and never deletes. Git history and generated folders are skipped, a sync is limited to 512 MB per file and 1 GB or 10,000 entries, and SFTP checks the saved SSH host key and accepts a password or private key.
- **FTP sharing.** Settings → Connections → FTP server shares one workspace with an FTP client, read only or read and write, optionally over the local network, with a background Stop action, symlink and special-file protection and a 512 MB upload limit.
- Build & Test dashboard: save project checks such as Gradle, npm, lint, and test commands, watch live output and pass/fail status, jump straight to parsed file errors, and hand a failed run to the agent for repair. It also detects the project's dev, start, and preview scripts, runs the chosen one, opens the server's URL in the preview automatically, and stops the whole process tree on request.

### GitHub integration

- GitHub is the first settings page. Sign in through GitHub's Device Flow (a code you enter in the browser, no auth server) or connect a personal access token. Verified credentials stay in encrypted app storage, and expiring OAuth connections refresh automatically.
- Import a repository into its own private workspace by browsing your repositories or pasting a public URL, from Settings, the workspace picker, or the chat header.
- Commit & push from GitHub Settings and the Files menu shows the target repository, branch, and changed files as a folder tree. Tap a folder to open it, long-press to select or deselect everything inside, and move around with the breadcrumb; selections survive going in and out of folders. Folder picks publish the exact changed files, so nested renames and deletions stage correctly while unrelated files are left alone. Commit selected files or retry an existing commit with Push existing commits. App history stays excluded, nothing is force-pushed, and the pushed commit is verified against the remote branch.
- Save a GitHub push preset for a workspace, repository, and branch, then run it manually, hourly, or daily from Automation without an AI model. Presets wait while a task is using that workspace and record blocked or failed runs in history.
- `doctor --github` checks the token, git transport, and the free plan's hidden protection limits in one command.

### Remote development over SSH

- Connect to remote Linux machines, servers, or local Termux environments with password or private key authentication, including Ed25519 support via Bouncy Castle.
- SshFs backs a workspace with SFTP: browse directories, read, write, edit, and view diffs on remote files directly from the app.
- Remote agent execution: terminal commands, background shell processes, git tools, and package managers run over the SSH connection.
- A persistent SSH status bar with live connection state and quick reconnect controls.
- Saved SSH profiles in workspace settings for switching between local and remote workspaces.

### Terminal and shells

Commands route by path, so the agent always runs in the strongest environment the device allows. Explicit root requests can also use the root service when it is enabled:

| Command target | Runs as | Environment |
| --- | --- | --- |
| Privileged commands | shell uid via Shizuku | system access, like adb shell |
| Privileged commands, no Shizuku | shell uid (2000) via paired wireless debugging | system access, like adb shell |
| Explicit root requests | UID 0 via the verified root service | full device access when root is enabled |
| Workspace commands | app uid | Termux-prefix Linux toolchain with real bash, git, python, and node |
| Fallback | app uid | bare toybox sh when nothing else is installed |

A shell policy and a secret redactor keep the agent from escaping the workspace, touching system paths without permission, or leaking API keys.

**Explicit Shizuku access.** `access="shizuku"` pins a shell command to Shizuku and never falls back to the app UID or direct root. App-workspace development commands use the app UID by default, Android system commands can request Shizuku explicitly, and SSH workspaces refuse Shizuku and root because those transports are Android-local. Shizuku running as UID 0 no longer needs a separate root grant, tool cards name the transport (Normal, Shizuku, Wireless ADB, Dhizuku or Root), and the verified UID stays in diagnostics.

**Wireless ADB pairing.** Settings → Terminal & device → Wireless ADB pairs with Android's own wireless debugging through the same flow Shizuku uses on a non-rooted phone. Android shows a six-digit code, the app proves it knows the code over a local TLS connection, and the device then trusts the app's key; the code is used once and never saved. It is entered from a notification because Android keeps its pairing dialog on top, with manual port and code fields as a fallback. Commands run as UID 2000, so UID 0 features and app-private files stay out of reach. The privileged tier uses the connection automatically for terminal commands, Linux environment setup, package work and background jobs while Shizuku stays available when it is connected. Requires Android 11 or newer, Wi-Fi and notification permission; pairing survives a reboot, but Wireless debugging must be turned on again.

**Dhizuku Device Owner.** When Dhizuku shares Device Owner access, the explicit `device_owner_shell` tool runs commands as the owner app with its Android restrictions. It never substitutes for ADB, root or SSH, its execution receipt names the owner UID, and Settings → Root access shows the real Dhizuku state. Shevery is recognized as a compatible privileged shell provider alongside Shizuku and Wireless ADB.

**Optional root access.** Settings → Terminal & device adds opt-in root through a verified local service. Access modes are Auto, Normal, Shizuku, Root and Both, with an auto-fallback switch. Both keeps both connections live: ordinary commands use Shizuku and `access="root"` calls use the direct root service in the same task. Root is limited by default to an app session, one chat or one action and to projects you allow; a temporary root mode switch, behind a confirmation dialog, removes those limits for every project while the connection stays active. The root browser opens protected folders and text files up to 64 KB, and its saves refuse stale edits with a SHA-256 check. Tool cards show which backend ran (Normal, Shizuku or Root), detached root jobs can be stopped or resumed, and tracked kills verify the process start time and log. KernelSU, KernelSU Next, SukiSU Ultra, APatch and Magisk are detected and named in the card and diagnostics. Root is granted once after each app start. The Root enabled chip appears only after the direct root service is verified; when a direct grant is denied but Shizuku runs as UID 0, the card says privileged commands will use Shizuku instead.

The app-tier shell and the terminal run bundled Bash directly on Android 8 and 9, and through the system dynamic linker from Android 10 up, where Android requires it for app-storage binaries.

### Reliability

- Setup requires storage access before starting. Notifications are optional; you can continue using progress and approvals inside the app without granting notification permission.
- **Background and notifications.** Settings → Background & notifications has a Run in background switch that stops agents when you leave the app, a notifications switch, and a shortcut to Android's notification settings. Each row states the current state and what it changes.
- **App language.** Settings → App language offers System default, English, 简体中文, Русский, العربية, فارسی, Português (Brasil), Deutsch, Español, 日本語, Bahasa Indonesia and සිංහල, applies immediately without a restart, persists on Android 12 and earlier, and follows Android's per-app language picker on Android 13 and up. The interface is translated into all eleven, with Arabic and Persian keeping code, diffs and terminals left to right; model and provider names, tool descriptions, agent prompts and shell output stay in English. The page links to the POEditor project for anyone who wants to help translate, and CONTRIBUTING.md explains the workflow.
- **Update install route.** Updates can install through Automatic, Root, Shizuku or the Android installer. Automatic uses an enabled and already authorized device access route and falls back to the Android installer, a dispatched install is never retried automatically, and the dialog names the route that installed the update.
- A foreground service keeps the agent and terminals alive while the screen is off.
- **Live activities on Android 16.** While a run or terminal is active, the ongoing notification can be promoted to a Live Update: Live updates on Pixel and near-stock Android, a Now Bar card on Samsung One UI, Live alerts on ColorOS and OxygenOS. The card shows a short state and progress, and offers Approve or Deny, or Skip or Install, while the agent waits for you. Idle services are never promoted, background services such as 9Router hold the wake lease without the promotion, and Settings → Agent behavior → Live activities has the switch, the detected device and skin, its real permission state and per-brand setup steps.
- The privileged shell verifies its connection. The standard Shizuku user service remains the default. If its startup fails on API 11–13, AndroidHarness automatically tries a private pipe-based compatibility runner using the same command and background-job implementation. This bypasses the Android Application initialization crash seen with Shizuku 13.6 on some OEM devices. Compatibility mode is marked ready only after checking the runner's identity and executing a privileged command. Terminal, tools, setup and diagnostics recognize both modes; Shizuku permission is still required.
- A Shizuku runner that cannot bind shows a failure reason, expandable details and copy actions in Terminal & device and Troubleshooting. Copy startup log reads recent startup logs through Shizuku's main server on API 11–13, even when the runner cannot bind; no computer is needed. Reports include app/server versions, error time and logs filtered to this app's helper and service records. Missing logs and collection failures are reported explicitly. Compatibility recovery keeps the original startup error available without showing the working connection as failed. Late callbacks from old attempts cannot replace the live connection.
- Interrupted tasks come back as a Resume card. Tool results are saved before the next action, completed writes are not replayed on recovery, and uncertain operations ask you to inspect the current state.
- Troubleshooting checks the default provider's model catalog, storage, the default workspace, Linux tools, Shizuku, SSH, connected MCP servers, and notifications in one place, and exports a diagnostic report built from a fixed whitelist of states. Credentials, endpoints, names, file paths, chats, and raw logs are never included.
- Settings → Storage measures shared Linux tools, npm and pip download caches, workspaces, local models, chat data and runtime staging, with per-workspace totals and honest "not measured" notes for SSH, picker-only and unavailable folders. Review cleanup previews exact paths and sizes and deletes only those files after a fresh check, waits for running tasks, tracked background processes and a closed terminal, and shared Linux packages are offered for removal only when nothing requires them and their recorded files are unchanged, quarantining files first so an interruption rolls back.
- Optional Auto-continue (Context & limits → Recovery) resumes after server or connection interruptions with the same Resume task prompt. It is off by default, with a per-task retry limit of 1 to 5 (default 3). User stops, task limits, and permanent errors still require manual attention.
- Context & limits lets you edit the saved summary, pin instructions, and remove older model context while retaining the visible chat.
- Compaction never deletes messages. When a context nears its limit it summarizes a snapshot of the older conversation into a hidden note and commits only if the chat has not changed meanwhile; if anything moved, the summary is refused and your history is kept. /compact can be stopped, a failure is saved in the chat as a card with Details and Retry, and a context that is too large for the model says so instead of quietly dropping the oldest messages.
- Optional task-wide limits for tokens, estimated USD cost, and active time, including subagents and compaction. Tasks pause at request or action boundaries with progress saved, in-flight work can exceed a limit, and a reached limit is raised before resuming.
- Approve or deny sensitive actions from the notification shade, with four permission modes up to a full access mode that lifts every sandbox for workspaces you trust. Turning on Full access requires your fingerprint, face or device PIN, in Settings, the chat header, the workspace access menu and when a settings backup would restore it.
- Review and revoke every remembered tool permission individually in their own settings section.
- The phone drawer and desktop sidebar share one project tree, with Chats and Projects views, search, pinning, archived chats, chat actions and undo, multi-select with batch archive, details and delete, a delete confirmation, and a Tools footer with Files, Terminal, Automations and Build & Test.

### Models and providers

- **Continue with ChatGPT.** Add and select eligible ChatGPT accounts in Settings → Connected accounts. Picking another account refreshes its available models and keeps your current model when that account offers it. Thinking levels follow the chosen model's supported efforts. Refresh models, check newer models, reconnect, and sign out from each account's menu. Limits & usage opens ChatGPT's usage settings. Sign-ins stay encrypted on the device and are excluded from settings backups.
- **Built-in keyless Harness provider.** Anonymous free models from Kilo and Pollinations served out of the box, fetched from Kilo's live catalog so retired models drop out on their own, with each model's rate limit shown in the picker and the kilo-auto/free router as the default. No API key needed to start.
- **A2Agent.** A preconfigured OpenAI-compatible provider shown first in the brand list with a New badge. Its API key and model setup work like any other provider.
- Anthropic, Google Gemini, and any OpenAI-compatible endpoint with a custom base URL.
- **Fast provider setup.** Adding a connection needs only its API key, because the first catalog model is preselected, and the new provider becomes active on its own default model instead of inheriting a model override from the previous provider.
- **Connection controls.** Each provider can keep up to 20 named API keys with individual switches, rotate through the enabled keys, and recover a rejected, rate-limited or out-of-quota request with another key before any reply text exists. Choose Use global setting, Direct connection or Custom proxy per provider with HTTP or SOCKS5 and optional HTTP credentials, and set one global provider proxy for replies and model lists. Key pools and proxy credentials stay in encrypted storage and travel in settings backups.
- **Vision support follows the model.** DeepSeek V4 Flash and V4.1 Flash accept images; DeepSeek V4 Pro is text-only, so Chat and Code offer attachments that match the selected model.
- Custom cloud model IDs typed directly into the model picker sheet.
- Live model catalog fetch with latency check, per-model price tracking, and a running cost readout, plus a Usage screen with model activity, estimated cost, cache hit rate, a separate AI code changes card, the token breakdown, and a collapsible definitions section. DeepSeek estimates follow the request's UTC rate: peak 01:00-04:00 and 06:00-10:00 on weekdays, half price at all other times including weekends. Stats windows (Today, 1 week, 1 month, Lifetime) count requests by the day they were made on your local calendar, so continuing an old chat never moves earlier work into today.
- **Thinking controls follow the model.** Levels come from the provider's own model list when it reports them, with models.dev and shipped family tables as fallback, and models without reasoning controls no longer show a fake ladder. The header shows the resolved level and how it was chosen. Custom levels can be added, renamed, reordered, and removed per provider and model, and an optional CEL expression maps them to request fields, with an inherited default for every remote API. Local models get a thinking behavior choice: follow the Chat setting, request reasoning from the template, or answer directly.
- Per-chat dual planning: a chat menu toggle that runs Plan mode on one model and execution on another, each picked from the same model sheet, with a toast confirming which model fired and a plan card that survives app restarts. Long plans expand into a complete, formatted, selectable view before approval.
- Per-chat models: one choice for every chat is the default, as before. Turn off Use the same model in every chat in the picker and each chat remembers its own provider and model for the active, planning and execution roles plus its own thinking level, labelled This chat in the header. Model IDs are saved with the chat, so editing a provider's default later cannot change an older chat, and a chat whose provider or model is gone asks you to pick again instead of sending the request somewhere else.
- **Fallback chains** (issue #33): up to four backup models across providers take over, in order, when the selected model fails. Quota exhaustion switches immediately, ordinary rate limits and temporary failures retry first (0 to 5 retries), and a partial response is never retried or sent again. Each destination uses its own credentials and your selected model stays selected; the chat notes the switch. Configure a default chain in Settings → Models & providers, or per chat from the picker: Use default, Custom chain, or Off. Subagents and automatic compaction follow the same chain, a request from a model outside it is refused with a review message rather than sent, Stay within selected provider keeps every request (subagents included) on the selected connection, and settings backups carry the chain with non-portable providers stripped.
- **One provider list** (issue #34): Settings → Models & providers and the in-chat model picker share the same list. Disable a connection without deleting it (it keeps its key and leaves the pickers until re-enabled, and a search still reveals it one tap away), reorder connections with move up and move down, and search models across every provider, with each hit naming its provider. Order and disabled state persist and travel in settings backups.
- **Favourites and Recents**: star models in the picker and switch between All, Favourites and Recent collections, with favourites sorted first. Recent keeps the last ten picks per provider with Clear recent, both lists stay on the device and never enter backups, and a favourite whose provider was removed stays visible but cannot be selected.
- **Provider-first picker**: the sheet opens on the chat's active provider, with a Choose provider control that opens a searchable provider list and a Manage providers shortcut, plus search, Favourites and Recent filters and a thinking-only toggle. Tapping a model stages it and a `Use <model>` button confirms, so scrolling or a stray tap cannot switch models, and a disabled provider asks before it is enabled and used. Show all searches every catalog at once, hidden models and disabled providers included.
- **Manage models**: decide which of a provider's models appear in quick selection, with search, per-model checkboxes, Select results and Refresh models. Once you customize a provider's list, newly discovered models stay hidden until you add them, a Hidden tag marks what Show all is revealing, and hiding a model never changes a chat that already uses it.
- **Reasoning indicators**: a colored brain marks reasoning support reported by the provider or set by you on a custom model, a muted brain marks an unconfirmed family-name hint, and tapping it explains the source. Gemini catalogs read the model's own thinking field, so a guess is never presented as a provider report.
- **Custom provider headers**: add extra HTTP headers to an API connection for gateways that need them, with per-row enable toggles and masked values. They are stored encrypted, sent only to that connection, excluded from chat exports and backups unless keys are included, and redirects are disabled while any header is enabled so credentials cannot leak.
- **Bulk provider actions**: long-press a provider, or choose Select from its menu, to enable, disable or delete several at once. Multi-delete reviews the exact list first and keeps your checks if it fails, and deleting a provider never reroutes a chat that used it: the chat keeps its selection and asks you to choose again.

### Personalization

- **Custom color palettes.** Settings → Appearance → Your palettes keeps up to 24 palettes, each with main, accent and highlight colors picked by hex value or by hue, saturation and brightness. Material generates readable light and dark shades from them, the palette is previewed before saving, deleting the active one returns the app to its selected preset, and settings backups carry and validate palettes.
- **Harness Canvas.** Add a photo or artwork up to 32 MB and show it as the backdrop for Chat, Code or both, with dimming so text stays readable and an option to match the app's color palette to the image. Colors can follow the wallpaper, a preset palette, a custom palette or the canvas; each choice releases the others, and removing the canvas restores the previous theme. Canvas settings travel in backups and are validated on import.
- **9Router.** Settings → 9Router installs and manages the 9Router local model router on this phone. Installation places the npm package inside the Linux toolchain with validated release versions; the page can start, stop and restart the server, check the npm registry for newer releases, update in place with rollback, uninstall everything, and auto-start when the app opens. Add 9Router to providers starts it if needed and connects its local endpoint (`127.0.0.1:20128/v1`) with an auto-created API key, so its models appear in the Chat and Code pickers; removing the connection leaves the router installed. The service holds a background wake lease without advertising an active task.

### Local models (offline AI)

- Settings → Local models downloads GGUF files and imports safetensors. The page splits into My models and Explore, with the experimental switch and GPU support above the tabs. Supported dense Qwen 2, Qwen 2.5, Qwen 3, and Llama BPE weights are converted on the device to Q4 GGUF, then selected from the chat provider picker.
- Import file takes a GGUF; Import folder takes safetensors weights with config.json and tokenizer.json. Recommendations include small SmolLM2, Qwen 2.5, and Qwen 3 models in both formats, plus community abliterated variants, with search by name and an Abliterated filter.
- Check available RAM and storage, and adjust context, input, output, generation threads and prompt threads yourself. All models stay visible.
- **Performance tests and device overview.** Find best settings sweeps generation and prompt thread counts on CPU, GPU or both, with the same three prompts, the model's template, a fresh context, greedy decoding and a 32-token cap per configuration. A warm-up run is shown but excluded from scores, speeds are averaged, answers that differ from the reference run are flagged, and the recommended backend and thread counts can be applied to the model. The Performance tab also shows live device telemetry every two seconds: memory history, per-core online state, clocks and load, system and app CPU, GPU renderer, vendor and driver identity, NPU and accelerator drivers reported by Android's discovery API, thermal status, battery level and temperature, and device specs.
- **Loaded models stay in memory.** A model stays loaded between turns and continuing a chat reuses the previous prompt prefix, so follow-ups start faster; the reply reports how many prompt tokens were reused. Deleting a model, Stop and unload, performance tests and memory pressure release the cache, and vision requests bypass it. Arm64 builds use KleidiAI CPU kernels where the device and operation support them.
- **GPU acceleration.** On 64-bit devices, enable the experimental switch to choose Auto, CPU or GPU. Auto prefers Vulkan and retries on CPU when the backend is unavailable or cannot start a reply before output begins. The Vulkan runtime is not in the base APK: GitHub builds download the package that matches the installed app and device from a pinned release and verify its size and SHA-256 before loading, Play builds deliver it as an on-demand feature module, and CPU inference works without it.
- Runs on compatible 64-bit devices. Agent tools and dual planning are available; vision needs a compatible GGUF and matching projector. RAM, storage, and context warnings offer Continue. Models run offline after setup, network tools still need a connection, and speed plus tool reliability depend on the model and device.

### Workspace safety

- Sandboxed file access: the agent cannot read or write outside the workspace, symlinks and binary files are refused, and delete guards protect the workspace root.
- An Aider-style Repo Map indexes codebase symbols automatically and feeds project structure into the agent's context.
- Workspace ignore files keep builds and caches out of the agent's way.
- Context hygiene keeps prompts tight and redacts secrets before they reach a model.
- Optional root is explicit and per project. Workspace file tools and ordinary commands never use it; the agent must request `access="root"`, and the tool card shows an execution receipt naming the backend that ran.
- `/init` writes an AGENTS.md for the project, and `/doctor` runs a 16 point self-test of every tool family.

### Skills and slash commands

- The app ships with a library of markdown skills (git, planning, test driven development, systematic debugging, web design, on-device Android builds, the AndroidHarness environment, and more) that the agent loads on demand. They are plain markdown files, so they are easy to edit and you can add your own.
- Attach a skill to a message, or pull one in with a slash command.
- `/clear`, `/compact`, `/cost`, `/doctor`, `/init`, `/plan`, `/skills`, `/btw`, plus any skill or snippet by name. `/plan` flips the agent into Plan mode and loads the planning skill automatically.
- `/btw` asks a quick side question while the agent keeps working. It replays the main task's latest request unchanged, so the answer sees every file and decision so far and is served from the same prompt cache, with the hit rate shown under the answer. It can use read-only tools, its usage counts toward the chat's cost, and the question and answer never join the conversation. While a task runs, typing a message also shows a /btw button next to queue.

## FAQ

- **Do I need a PC?** No. Editing, shell, git, GitHub, SSH, and the models all run on the phone.
- **Do I need an API key?** No. The built-in provider serves free models with no account, and local GGUF models run fully offline. Keys unlock cloud providers when you want them.
- **Do I need root?** No. Shizuku grants shell-level access without unlocking the bootloader, and the built-in Wireless ADB pairing can do the same without the Shizuku app. Direct root is optional if the device is rooted, and root and Shizuku can be used together in Both mode.
- **Does it work offline?** Local models run offline after setup. Web search, page fetches, and GitHub need a connection.
- **Can I change the language?** Yes. Settings → App language offers the system language, English, Simplified Chinese, Russian, Arabic, Persian, Brazilian Portuguese, German, Spanish, Japanese, Indonesian and Sinhala, and applies without a restart. More languages can be added through the POEditor project.
- **Where do my chats and keys go?** Chat traffic goes to the provider you pick, nothing else. Credentials stay in encrypted storage on the device, secrets are redacted before they reach a model, settings backups include API keys only if you ask, and ChatGPT sign-ins are excluded from backups entirely.
- **Can it damage my files?** It is sandboxed to the workspace, every write is diffable, and Files changed offers rewind, full-file undo, and section undo.
- **Which devices are supported?** Android 8.0 and up. Local models need a compatible 64-bit device.

## Build from source

Requires JDK 17 and the Android SDK.

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Run the unit tests with:

```bash
./gradlew :app:testDebugUnitTest
```

Debug APKs also build automatically on every push to `main` and nightly from the Actions tab. For the full local setup, see [CONTRIBUTING.md](CONTRIBUTING.md).

## GitHub setup

In Settings → GitHub, choose **Sign in with GitHub** or **Use a personal access token**.

- Device sign-in displays a code to copy into GitHub in your browser.
- Enable workflow access only if you need to change GitHub Actions files.
- Fine-grained PATs must include the target repositories and **Contents: read and write**. Workflow changes also need **Workflows** access.
- Organization approval or SSO authorization may be required.

Import needs the Linux environment installed. New clones use private app storage, so broad storage permission is unnecessary.

Commit & push works on device repositories with shell access and a GitHub HTTPS origin. SSH workspaces use credentials configured on their host. Push presets bind the saved repository and branch, preserve unrelated staged files, never force-push, and verify the resulting remote commit. Review files first; enabling future changes explicitly lets a preset include later changes in that workspace.

A failed custom AI automation cannot be diagnosed from its prompt alone. Open its history for the actual failure. Common causes include a wrong workspace, missing Git, an SSH remote, insufficient token permissions, newer remote commits, or branch protection. Native push presets avoid credentials in prompts and give step-specific git diagnostics, and a failed push can be retried with **Push existing commits**.

<details>
<summary>Building with your own OAuth app</summary>

For local builders: register a [GitHub OAuth app](https://github.com/settings/developers), enable **Enable Device Flow**, and set `GITHUB_CLIENT_ID` in the ignored `local.properties` file. Local debug and release builds include that value in the APK. The Client ID stays out of repository source, documentation, and public build workflow configuration. No client secret, callback server, or auth backend is used. Builds without local configuration retain PAT login. Keep a PAT available as a fallback, because GitHub limits Device Flow requests for a shared OAuth app.

</details>

## Developer repo wiki

A generated architecture reference covering the agent engine, tools, providers, the MCP stack, and the workspace layer: [repowiki](https://github.com/Sanuu7/AndroidHarness/tree/main/repowiki). Last updated: 2026-09-06.

## Contributing

Bug reports, fixes, test improvements, and documentation are all welcome. Start with [CONTRIBUTING.md](CONTRIBUTING.md), and use the issue forms for bug reports and feature requests. Translations are managed on [POEditor](https://poeditor.com/join/project/ZPbHNa9WWc); CONTRIBUTING.md covers the workflow. Privacy details are in [PRIVACY.md](PRIVACY.md), and release notes are in [CHANGELOG.md](CHANGELOG.md).

## Inspirations

AndroidHarness borrows ideas and design taste from open source projects across the ecosystem:

- [Hermes Agent](https://github.com/NousResearch/hermes-agent)
- [Aider](https://github.com/Aider-AI/aider)
- [pi (ohmypi)](https://github.com/earendil-works/pi)
- [OpenCode](https://github.com/anomalyco/opencode)
- [Claude Code](https://github.com/anthropics)
- [Roo Code](https://github.com/RooCodeInc/Roo-Code) / [Cline](https://github.com/cline/cline)
- [browser-use](https://github.com/browser-use/browser-use)
- [Termux](https://github.com/termux)
- [Shizuku](https://github.com/RikkaApps/Shizuku)
- [CodeGraph](https://github.com/colbymchenry/codegraph)
- [Caveman](https://github.com/JuliusBrussee/caveman)
- [llama.cpp](https://github.com/ggerganov/llama.cpp)
- [sora-editor](https://github.com/Rosemoe/sora-editor)
- [Eruda](https://github.com/liriliri/eruda)

## License

MIT. See LICENSE.
