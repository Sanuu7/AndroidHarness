# AndroidHarness

A coding agent that lives on your phone.

AndroidHarness is a native Android app, written in Kotlin with Jetpack Compose, that works on code projects directly from the device. It reads and edits files, runs shell commands, uses git, and chats with you about the work as it goes. No PC required.

Status: early alpha.

## Features

**Chat with a real coding agent**
- Full markdown chat with streaming responses, thinking blocks, and cards showing every tool call the agent makes.
- Voice input with live waveforms and Groq Whisper cloud transcription (`whisper-large-v3` / `turbo`) or native Android speech.
- Fork conversations from any assistant turn into a fresh session with cloned context.
- Resume last active chat automatically on launch with shimmering skeleton loading.
- Attach skills to a message or drop one in with a slash command.
- Multiple workspaces, one workspace switcher, switch projects without losing context.
- Queue a message while the agent is busy; it picks it up when the current run finishes.
- Ask the agent questions mid-run and answer from the notification shade or the chat.
- Chat backup and restore: export every chat with its full message history to a JSON file and import it back on any device. The file holds chats and messages only, never API keys or settings.

**Agent tools**
- File tools: read, write, edit, search, grep, list, move, delete, plus fuzzy multi-edit and apply_patch with atomic rollback on failure.
- Shell tools: run commands with timeouts, launch background processes, list and kill them.
- Git tools: status, diff, commit, log, show, branch, checkout, push, and pull. The harness auto-configures git identity so commits never fail on "author unknown".
- Web tools: web search through keyless engines or the Brave and Tavily APIs with a key, page fetch, raw HTTP requests with JSON bodies, and GitHub API requests that authenticate automatically.
- In-app web preview: universal preview hub for localhost ports, workspace HTML files, and web links with Eruda DevTools, console logs, and one-tap bug fixing.
- MCP tools: connect Model Context Protocol servers over stdio or HTTP, add them by pasting a Claude config or a claude mcp add command, and sign in with OAuth when the server needs it.
- Task tool: spawn subagents that work in parallel on independent chunks, each optionally on a different model.
- Skill tools: list, view, and manage the markdown skills library from inside a run.
- Todo and memory tools: a live todo list, a core memory file that loads at the start of every conversation, and topic files with search for everything else.

**Files and editor**
- A workspace file manager: create, rename, move, copy, delete, and share files and folders, with open-in-other-apps support.
- A real code editor: multi-color syntax highlighting across Kotlin, Java, Python, JS, TS, HTML, CSS, and Shell, line numbers, unlimited undo and redo, find and replace with regex, word wrap toggle, and encoding preservation.
- Visual diff viewer: side-by-side / inline diff viewer with dual line gutters, syntax coloring, and change stats.
- Per chat Files changed tracking: GitHub style badges and diffs for every file the agent touches, with rewind.

**GitHub built in**
- Sign in once in Settings: the agent's git push and pull, the bundled gh CLI, and GitHub API requests all authenticate automatically, and the login survives toolchain reinstalls.
- doctor --github checks the token, git transport, and the free plan's hidden protection limits in one command.

**Shell tiers, not a sandbox hack**
- Commands route by path: Shizuku runs privileged commands as the shell uid, the app uid runs a Termux-prefix Linux toolchain with real bash, git, python and node, and bare toybox sh is the fallback when nothing else is installed.
- A shell policy and a secret redactor keep the agent from escaping the workspace, touching system paths without permission, or leaking API keys.

**Runs that survive anything**
- Foreground service keeps the agent and terminals alive while the screen is off.
- Checkpoints and a run manager let a run survive an app restart.
- Approve or deny sensitive actions from the notification shade, with four permission modes up to a full access mode that lifts every sandbox for workspaces you trust.

**Model flexibility**
- Anthropic, Google Gemini, and any OpenAI compatible endpoint with a custom base URL.
- Live model catalog fetch with latency check, per-model price tracking, and a running cost readout.
- One global thinking ladder from Off to Ultra on every model; non native rungs resolve down the chain at request time, never rewriting your pick.
- Separate planning and execution models: a Settings switch that lets Plan mode run one model while execute mode runs another, each picked from the same model sheet, with a toast confirming which model fired.

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

Run the unit tests with:

```bash
./gradlew :app:testDebugUnitTest
```

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
