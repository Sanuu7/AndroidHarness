# AndroidHarness

A coding agent that lives on your phone.

AndroidHarness is a native Android app, written in Kotlin with Jetpack Compose, that works on code projects directly from the device. It reads and edits files, runs shell commands, uses git, and chats with you about the work as it goes. No PC required.

Status: early alpha.

## Features

**Chat with a real coding agent**
- Full markdown chat with streaming responses, thinking blocks, and cards showing every tool call the agent makes.
- Responsive markdown tables with compact card previews and an expandable full-sheet viewer with horizontal scroll.
- In-chat file diff sheet to inspect file changes directly from tool call cards without leaving the conversation.
- Turn performance metrics: tracks response duration (ms) and token generation throughput (tokens/sec) alongside token counts in turn stats.
- Turn activity rollups and streamlined tool cards: consecutive tool calls roll up into a compact activity summary with reactive expansion states and status indicators.
- Voice input with live waveforms and Groq Whisper cloud transcription (`whisper-large-v3` / `turbo`) or native Android speech. Tap the mic to lock recording open, or hold with slide-up lock and slide-left cancel.
- Fork conversations from any assistant turn into a fresh session with cloned context.
- Resume last active chat automatically on launch with shimmering skeleton loading.
- Attach skills to a message or drop one in with a slash command.
- Multiple workspaces, one workspace switcher, switch projects without losing context.
- Keep multiple queued instructions with edit, reorder, remove, and Send now controls. The queue persists across app restarts and is consumed at agent boundaries.
- Long-press your own message for Retry alongside Copy and Edit, resending it as a fresh turn.
- Ask the agent questions mid-run and answer from the notification shade or the chat.
- Chat backup and restore: export every chat with its full message history to a JSON file and import it back on any device. The file holds chats and messages only, never API keys or settings.

**Scheduled automations**
- Define recurring or interval-based prompt tasks that run in the background via Android WorkManager.
- AI-assisted planner translates plain English instructions into cron-like schedules and parameters.
- Automation editor bottom sheet with quick suggestion chips, per-automation model selection, and manual run triggers.
- Run history logs, execution status indicators, and background completion notifications.

**Agent tools**
- File tools: read, write, edit, search, grep, list, move, delete, plus fuzzy multi-edit and apply_patch with atomic rollback on failure. The agent reads images by filename and extracts text from attached PDFs.
- Shell tools: run commands with timeouts, launch background processes, list and kill them, install Linux packages, and query Android logs with package, tag, level, and pattern filters.
- Git tools: status, diff, commit, log, show, branch, checkout, push, and pull. The harness auto-configures git identity so commits never fail on "author unknown".
- Web tools: web search through keyless engines or the Brave and Tavily APIs with a key, page fetch, raw HTTP requests with JSON bodies, and GitHub API requests that authenticate automatically.
- In-app web preview: universal preview hub for localhost ports, workspace HTML files, and web links with Eruda DevTools, console logs, and one-tap bug fixing. The agent also drives the page itself through browser tools (navigate, snapshot, click, type, scroll, eval, screenshot) with a floating live-action bubble.
- MCP tools: connect Model Context Protocol servers over stdio or HTTP, add them by pasting a Claude config or a claude mcp add command, and sign in with OAuth when the server needs it.
- Task tool: spawn subagents that work in parallel on independent chunks, each optionally on a different model.
- Skill tools: list, view, and manage the markdown skills library from inside a run.
- Todo and memory tools: a live todo list, a core memory file that loads at the start of every conversation, and topic files with search for everything else.

**Files and editor**
- A workspace file manager: multi-select batch operations (delete, copy, move via destination picker), create, rename, and share files and folders, with open-in-other-apps support.
- A real code editor: multi-color syntax highlighting across Kotlin, Java, Python, JS, TS, HTML, CSS, and Shell, line numbers, unlimited undo and redo, find and replace with regex, word wrap toggle, and encoding preservation.
- Visual diff viewer: side-by-side / inline diff viewer with dual line gutters, syntax coloring, and change stats.
- Per chat Files changed tracking with rewind, full-file undo, and selective section undo. Undo checks that the preview still matches the file and preserves unrelated sections.
- Build & Test dashboard: save project checks such as Gradle, npm, lint, and test commands, watch live output and pass/fail status, jump straight to parsed file errors, and hand a failed run to the agent for repair.

**GitHub built in**
- Login with GitHub in Settings by pasting a personal access token, or tap Get access token to create one on GitHub. AndroidHarness verifies the token before saving it in encrypted app storage. Git push/pull, the bundled gh CLI, and GitHub API requests reuse the saved token.
- doctor --github checks the token, git transport, and the free plan's hidden protection limits in one command.

**Shell tiers, not a sandbox hack**
- Commands route by path: Shizuku runs privileged commands as the shell uid, the app uid runs a Termux-prefix Linux toolchain with real bash, git, python and node, and bare toybox sh is the fallback when nothing else is installed.
- A shell policy and a secret redactor keep the agent from escaping the workspace, touching system paths without permission, or leaking API keys.

**Runs that survive anything**
- Foreground service keeps the agent and terminals alive while the screen is off.
- Interrupted tasks show a Resume task card. Tool results are saved before the next action; completed writes are not replayed on recovery, and uncertain operations require inspecting current state.
- Context & limits lets you edit the saved summary, pin instructions, and remove older model context while retaining the visible chat.
- Optional task-wide token, estimated USD cost, and active-time limits include subagents and compaction. Tasks pause at request/action boundaries with progress saved; in-flight work can exceed a limit. Raise a reached limit before resuming.
- Approve or deny sensitive actions from the notification shade, with four permission modes up to a full access mode that lifts every sandbox for workspaces you trust.
- Redesigned navigation drawer with a quick-access tool strip (Files, Terminal, Automations, Build & Test) and a dedicated active provider card.

**Model flexibility**
- Built-in keyless Harness provider on the OpenCode Zen relay for immediate out-of-the-box model access without API keys.
- Anthropic, Google Gemini, and any OpenAI compatible endpoint with a custom base URL.
- Custom model IDs: enter any custom model name directly in the model picker sheet across all supported providers.
- Live model catalog fetch with latency check, per-model price tracking, and a running cost readout, plus a total estimated cost hero on the Stats screen.
- One global thinking ladder from Off to Ultra on every model; non native rungs resolve down the chain at request time, never rewriting your pick.
- Per-chat dual planning: a chat menu toggle that runs Plan mode on one model and execution on another, each picked from the same model sheet, with a toast confirming which model fired and a plan card that survives app restarts.

**Workspace hygiene**
- Sandboxed file access: the agent cannot read or write outside the workspace, symlinks and binary files are refused, and delete guards protect the workspace root.
- Aider-style Repo Map: automatic codebase symbol indexing that feeds project structure into the agent's context.
- Workspace ignore files so builds and caches stay out of the agent's way.
- Context hygiene keeps prompts tight and redacts secrets before they reach the model.
- /init writes an AGENTS.md for the project; /doctor runs a 16 point self-test of every tool family.

**Slash commands**
- /clear, /compact, /cost, /doctor, /init, /plan, /skills, plus any skill or snippet by name.
- /plan flips the agent into Plan mode and loads the planning skill automatically.

## Skills

The app ships with a library of markdown skills: git, planning, test driven development, systematic debugging, web design, and more. The agent loads them on demand. Skills are plain markdown files, so they are easy to edit, and you can add your own.

## Setup

1. Install the app.
2. Grant storage access. On Android 11 and up the app needs "All files access" so the shell and file tools can use real filesystem paths.
3. Add an API key in Settings.
4. Optional but recommended: install Shizuku or Termux so the agent can run shell commands with proper permissions.

## Build

Requires JDK 17 and the Android SDK.

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### GitHub Setup (Optional)

No OAuth backend or GitHub OAuth App is required.

1. Open Settings > GitHub > Login with GitHub.
2. Paste an existing personal access token, or tap Get access token to open GitHub's token creation page.
3. The `repo` scope is included for repository access. Enable the optional scopes shown in the app only when you need those capabilities.
4. Save the token. AndroidHarness verifies it with GitHub before storing it and refreshes git and `gh` authentication immediately.

Run the unit tests with:

```bash
./gradlew :app:testDebugUnitTest
```

## Developer Repo Wiki

A generated architecture reference covering the agent engine, tools, providers, MCP stack, and workspace layer: [repowiki](https://github.com/Sanuu7/AndroidHarness/tree/main/repowiki). Last updated: 2026-09-06.

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
- [llama.cpp](https://github.com/ggerganov/llama.cpp)
- [sora-editor](https://github.com/Rosemoe/sora-editor)
- [Eruda](https://github.com/liriliri/eruda)

## License

MIT. See LICENSE.
