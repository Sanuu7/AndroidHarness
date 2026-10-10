# AndroidHarness

<a href="https://play.google.com/store/apps/details?id=com.androidharness.app"><img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" alt="Get it on Google Play" height="72"></a>

[![Debug build](https://github.com/Sanuu7/AndroidHarness/actions/workflows/nightly.yml/badge.svg)](https://github.com/Sanuu7/AndroidHarness/actions/workflows/nightly.yml)
[![Version](https://img.shields.io/badge/version-1.7-blue)](https://github.com/Sanuu7/AndroidHarness/releases/latest)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3ddc84)](https://developer.android.com/about/versions/oreo)
![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)

**An AI coding agent that lives on your Android phone.**

AndroidHarness is a native Android app, written in Kotlin with Jetpack Compose, that works on real code projects directly from the device. It reads and edits files, runs shell commands in a real Linux toolchain with bash, git, python, and node, pushes to GitHub, drives a browser, and streams the whole process into a chat you can inspect. No PC and no root required.

<!-- Screenshots: add two or three device captures to docs/images/ and uncomment this block.
<p align="center">
  <img src="docs/images/chat.png" alt="AndroidHarness chat with streaming answers and tool call cards" width="260">
  <img src="docs/images/diff.png" alt="Side by side diff viewer opened from a chat" width="260">
  <img src="docs/images/editor.png" alt="Code editor with syntax highlighting and line numbers" width="260">
</p>
-->

## Highlights

- **A coding agent, not a chatbot.** File edits, shell, git, tests, browser control, subagents, and a card for every tool call that you can open and inspect.
- **Free to start.** The built-in keyless provider works with no sign-up, and local GGUF models run fully offline. Cloud keys and ChatGPT plans are optional.
- **A real shell on a phone.** Commands route by path through Shizuku, a Termux toolchain, or a toybox fallback, with a shell policy and secret redactor on top.
- **Long runs survive interruptions.** Foreground service, resume cards, a persistent message queue, and optional auto-continue keep work safe across screen-off stretches and connection drops.

## Quick start

1. Install from [Google Play](https://play.google.com/store/apps/details?id=com.androidharness.app), or build from source below.
2. Grant storage access. On Android 11 and up the app needs All files access so the shell and file tools can use real filesystem paths.
3. Pick a model. Start instantly with the built-in keyless provider, add an API key in Settings, or download an on-device model from Settings → Local models.
4. Optional but recommended: install [Shizuku](https://github.com/RikkaApps/Shizuku) or [Termux](https://github.com/termux) so shell commands run with proper permissions.

Open a workspace, point the agent at a project, and ask for a change. `/doctor` self-tests every tool family whenever something feels off.

## Features

### Chat

- Full markdown chat with streaming responses, thinking blocks, and a tool call card for every action the agent takes.
- Responsive markdown tables with compact card previews and an expandable full-sheet viewer with horizontal scroll.
- File diff sheets open right from tool call cards, so you can inspect a change without leaving the conversation.
- Turn metrics: response duration, token generation throughput measured over the streaming window itself, first-token latency, and token counts in turn stats.
- Consecutive tool calls roll up into one compact activity card that expands in place with per-call status indicators.
- Verification cards: turns that touch files or run checks end with a list of every build, test, or lint command and its recorded outcome (passed, failed, or unconfirmed). Checks invalidated by a later edit are marked for a recheck.
- Voice input with live waveforms, transcribed by Groq Whisper (`whisper-large-v3` / `turbo`) or native Android speech. Tap the mic to lock recording, or hold with slide-up lock and slide-left cancel.
- Fork any assistant turn into a fresh session with cloned context.
- The last active chat resumes automatically on launch behind shimmering skeleton loading.
- Each chat keeps its assigned workspace. Choose it from Files or the sidebar; other chats and their running tasks stay in their own projects.
- Message queue for follow-ups, with edit, reorder, remove, and Send now. The queue persists across app restarts, each message starts after that chat's successful Run finished notification, and interruptions keep it waiting.
- Long-press your own message for Retry, Copy, and Edit. Retry resends it as a fresh turn.
- The agent can ask you something mid-run, and you can answer from the notification shade or the chat.
- Optional Caveman reply modes: terse, compressed responses with an intensity dial and optional skill enforcement, configured in their own settings screen.
- Chat backup: export every chat with its full message history to a JSON file and import it back on any device. The file holds chats and messages only, never API keys or settings.
- Encrypted settings backup: provider setup, catalogs, and preferences in one encrypted file, with API keys included optionally.

### Scheduled automations

- Recurring or interval-based prompt tasks that run in the background through Android WorkManager.
- An AI-assisted planner that translates plain English instructions into a cron-like schedule and its parameters.
- An editor bottom sheet with quick suggestion chips, per-automation model selection, and manual run triggers.
- Run history logs, execution status indicators, and background completion notifications.

### Agent tools

- **Files.** read, write, edit, search, grep, list, move, delete, fuzzy multi-edit, and apply_patch, which checks every hunk header and line count before it touches a file and rolls back atomically on failure. Moves refuse to overwrite an existing destination unless you pass `overwrite=true`. The agent reads images by filename and extracts text from attached PDFs.
- **Shell.** Run commands with timeouts, launch and manage background processes, install Linux packages, and query Android logs with package, tag, level, and pattern filters.
- **Git.** status, diff, commit, log, show, branch, checkout, push, and pull, with git identity auto-configured so commits never fail on "author unknown".
- **Web.** Search through keyless engines or the Brave and Tavily APIs with a key; fetch pages; make raw HTTP requests with JSON bodies, restricted to public addresses (localhost, private ranges, and other non-public destinations are refused, DNS answers are validated before connecting, and redirects are not followed); call the GitHub API with automatic authentication.
- **Web preview and browser control.** A preview hub for localhost ports, workspace HTML files, and web links, with Eruda DevTools, console logs, and one-tap bug fixing. The agent drives the page itself through browser tools (navigate, snapshot, click, type, scroll, eval, screenshot) behind a floating live-action bubble. Select any element to send its selector, computed styles, console errors, and a screenshot straight to the agent as a fix request.
- **MCP.** Connect Model Context Protocol servers over stdio or HTTP, add them by pasting a Claude config or a `claude mcp add` command, and sign in with OAuth when the server needs it.
- **CodeGraph.** Install it from Settings, enable a local index per workspace, and the agent can explore symbols, callers and callees, change impact, affected tests, and incremental sync with no separate configuration.
- **Subagents.** The task tool spawns subagents that work in parallel on independent chunks, each optionally on a different model. They research read-only by default; with subagent action tools enabled they also edit files and run commands in Act mode, always under the current permission mode.
- **Subagent settings.** Settings → Sub-Agent settings gives all subagents a separate provider and model, or keeps them on the main agent's model. A saved subagent model takes priority over the task tool's model override.
- **Skills, todos, and memory.** Skill tools list, view, and manage the markdown skills library from inside a run. A live todo list, a core memory file that loads at the start of every conversation, and searchable topic files for everything else.

### Files and editor

- Workspace file manager: multi-select batch operations (delete, copy, move via destination picker), create, rename, and share files and folders, with open-in-other-apps support.
- Code editor with multi-color syntax highlighting across Kotlin, Java, Python, JS, TS, HTML, CSS, and Shell, line numbers, unlimited undo and redo, regex find and replace, a word wrap toggle, and encoding preservation.
- Diff viewer with side-by-side and inline modes, dual line gutters, syntax coloring, and change stats.
- Files changed tracking per chat with rewind: full-file undo and selective section undo. Undo checks that the preview still matches the file and preserves unrelated sections. Ask about changes sends a whole-file diff or a single section to the agent for an explanation.
- Build & Test dashboard: save project checks such as Gradle, npm, lint, and test commands, watch live output and pass/fail status, jump straight to parsed file errors, and hand a failed run to the agent for repair. It also detects the project's dev, start, and preview scripts, runs the chosen one, opens the server's URL in the preview automatically, and stops the whole process tree on request.

### GitHub integration

- GitHub is the first settings page. Sign in through GitHub's Device Flow (a code you enter in the browser, no auth server) or connect a personal access token. Verified credentials stay in encrypted app storage, and expiring OAuth connections refresh automatically.
- Import a repository into its own private workspace by browsing your repositories or pasting a public URL, from Settings, the workspace picker, or the chat header.
- Commit & push from GitHub Settings and the Files menu shows the target repository, branch, and changed files. Commit selected files or retry an existing commit with Push existing commits. App history stays excluded, nothing is force-pushed, and the pushed commit is verified against the remote branch.
- Save a GitHub push preset for a workspace, repository, and branch, then run it manually, hourly, or daily from Automation without an AI model. Presets wait while a task is using that workspace and record blocked or failed runs in history.
- `doctor --github` checks the token, git transport, and the free plan's hidden protection limits in one command.

### Remote development over SSH

- Connect to remote Linux machines, servers, or local Termux environments with password or private key authentication, including Ed25519 support via Bouncy Castle.
- SshFs backs a workspace with SFTP: browse directories, read, write, edit, and view diffs on remote files directly from the app.
- Remote agent execution: terminal commands, background shell processes, git tools, and package managers run over the SSH connection.
- A persistent SSH status bar with live connection state and quick reconnect controls.
- Saved SSH profiles in workspace settings for switching between local and remote workspaces.

### Terminal and shells

Commands route by path, so the agent always runs in the strongest environment the device allows:

| Command target | Runs as | Environment |
| --- | --- | --- |
| Privileged commands | shell uid via Shizuku | system access, like adb shell |
| Workspace commands | app uid | Termux-prefix Linux toolchain with real bash, git, python, and node |
| Fallback | app uid | bare toybox sh when nothing else is installed |

A shell policy and a secret redactor keep the agent from escaping the workspace, touching system paths without permission, or leaking API keys.

### Reliability

- A foreground service keeps the agent and terminals alive while the screen is off.
- Interrupted tasks come back as a Resume card. Tool results are saved before the next action, completed writes are not replayed on recovery, and uncertain operations ask you to inspect the current state.
- Optional Auto-continue (Context & limits → Recovery) resumes after server or connection interruptions with the same Resume task prompt. It is off by default, with a per-task retry limit of 1 to 5 (default 3). User stops, task limits, and permanent errors still require manual attention.
- Context & limits lets you edit the saved summary, pin instructions, and remove older model context while retaining the visible chat.
- Optional task-wide limits for tokens, estimated USD cost, and active time, including subagents and compaction. Tasks pause at request or action boundaries with progress saved, in-flight work can exceed a limit, and a reached limit is raised before resuming.
- Approve or deny sensitive actions from the notification shade, with four permission modes up to a full access mode that lifts every sandbox for workspaces you trust.
- Review and revoke every remembered tool permission individually in their own settings section.
- Navigation drawer with a quick-access tool strip (Files, Terminal, Automations, Build & Test) and the active provider at a glance.

### Models and providers

- **Continue with ChatGPT.** Add and select eligible ChatGPT accounts in Settings → Connected accounts. Enable Auto-switch at usage limit to try another connected account when a request reaches a confirmed usage limit, before any answer starts streaming. The app refreshes that account's available models, keeps the same model when supported, or uses its fallback choice. Choose which accounts participate and a fallback model for each. Completed tools and queued messages stay saved; if every account is limited, the task pauses. Thinking levels follow the chosen model's supported efforts. Refresh models, check newer models, reconnect, and sign out from each account's menu. Limits & usage opens ChatGPT's usage settings. Sign-ins stay encrypted on the device and are excluded from settings backups.
- **Built-in keyless Harness provider.** Anonymous free models from Kilo and Pollinations served out of the box, fetched from Kilo's live catalog so retired models drop out on their own, with each model's rate limit shown in the picker and the kilo-auto/free router as the default. No API key needed to start.
- Anthropic, Google Gemini, and any OpenAI-compatible endpoint with a custom base URL.
- Custom cloud model IDs typed directly into the model picker sheet.
- Live model catalog fetch with latency check, per-model price tracking, and a running cost readout, plus a total estimated cost hero on the Stats screen.
- One global thinking ladder from Off to Ultra on every model. Non-native rungs resolve down the chain at request time, never rewriting your pick.
- Per-chat dual planning: a chat menu toggle that runs Plan mode on one model and execution on another, each picked from the same model sheet, with a toast confirming which model fired and a plan card that survives app restarts.

### Local models (offline AI)

- Settings → Local models downloads GGUF files and imports safetensors. Supported dense Qwen 2, Qwen 2.5, Qwen 3, and Llama BPE weights are converted on the device to Q4 GGUF, then selected from the chat provider picker.
- Import file takes a GGUF; Import folder takes safetensors weights with config.json and tokenizer.json. Recommendations include small SmolLM2, Qwen 2.5, and Qwen 3 models in both formats, plus community abliterated variants, with search by name and an Abliterated filter.
- Check available RAM and storage, and adjust context, input, output, and CPU threads yourself. All models stay visible.
- Runs on compatible 64-bit devices. Agent tools and dual planning are available; vision needs a compatible GGUF and matching projector. RAM, storage, and context warnings offer Continue. Models run offline after setup, network tools still need a connection, and speed plus tool reliability depend on the model and device.

### Workspace safety

- Sandboxed file access: the agent cannot read or write outside the workspace, symlinks and binary files are refused, and delete guards protect the workspace root.
- An Aider-style Repo Map indexes codebase symbols automatically and feeds project structure into the agent's context.
- Workspace ignore files keep builds and caches out of the agent's way.
- Context hygiene keeps prompts tight and redacts secrets before they reach a model.
- `/init` writes an AGENTS.md for the project, and `/doctor` runs a 16 point self-test of every tool family.

### Skills and slash commands

- The app ships with a library of markdown skills (git, planning, test driven development, systematic debugging, web design, and more) that the agent loads on demand. They are plain markdown files, so they are easy to edit and you can add your own.
- Attach a skill to a message, or pull one in with a slash command.
- `/clear`, `/compact`, `/cost`, `/doctor`, `/init`, `/plan`, `/skills`, plus any skill or snippet by name. `/plan` flips the agent into Plan mode and loads the planning skill automatically.

## FAQ

- **Do I need a PC?** No. Editing, shell, git, GitHub, SSH, and the models all run on the phone.
- **Do I need an API key?** No. The built-in provider serves free models with no account, and local GGUF models run fully offline. Keys unlock cloud providers when you want them.
- **Do I need root?** No. Shizuku grants shell-level access over wireless debugging without unlocking the bootloader.
- **Does it work offline?** Local models run offline after setup. Web search, page fetches, and GitHub need a connection.
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

Bug reports, fixes, test improvements, and documentation are all welcome. Start with [CONTRIBUTING.md](CONTRIBUTING.md), and use the issue forms for bug reports and feature requests. Privacy details are in [PRIVACY.md](PRIVACY.md), and release notes are in [CHANGELOG.md](CHANGELOG.md).

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
