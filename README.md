# AndroidHarness

A coding agent that lives on your phone.

AndroidHarness is a native Android app, written in Kotlin with Jetpack Compose, that works on code projects directly from the device. It reads and edits files, runs shell commands, uses git, and chats with you about the work as it goes. No laptop required.

Status: early alpha.

## Features

**Chat with a real coding agent**
- Full markdown chat with streaming responses, thinking blocks, and cards showing every tool call the agent makes.
- Attach skills to a message or drop one in with a slash command.
- Multiple workspaces, one workspace switcher, switch projects without losing context.
- Queue a message while the agent is busy; it picks it up when the current run finishes.
- Ask the agent questions mid-run and answer from the notification shade or the chat.

**Agent tools**
- File tools: read, write, edit, search, grep, list, move, delete, plus fuzzy multi-edit and apply_patch with atomic rollback on failure.
- Shell tools: run commands with timeouts, launch background processes, list and kill them.
- Git tools: status, diff, commit. The harness auto-configures git identity so commits never fail on "author unknown".
- Web tools: web search, page fetch, raw HTTP requests with JSON bodies.
- Task tool: spawn subagents that work in parallel on independent chunks.
- Skill tools: list, view, and manage the markdown skills library from inside a run.
- Todo and memory tools: a live todo list, plus a workspace memory file that is loaded at the start of every conversation.

**Shell tiers, not a sandbox hack**
- Commands route by path: Shizuku runs privileged commands as the shell uid, the app uid runs a Termux-prefix Linux toolchain with real bash, git, python and node, and bare toybox sh is the fallback when nothing else is installed.
- A shell policy and a secret redactor keep the agent from escaping the workspace, touching system paths without permission, or leaking API keys.

**Runs that survive anything**
- Foreground service keeps the agent and terminals alive while the screen is off.
- Checkpoints and a run manager let a run survive an app restart.
- Approve or deny sensitive actions from the notification shade, with configurable default permission modes.

**Model flexibility**
- Anthropic, Google Gemini, and any OpenAI compatible endpoint with a custom base URL.
- Live model catalog fetch with latency check, per-model price tracking, and a running cost readout.
- Thinking level control from Off to Max, mapped per provider.

**Workspace hygiene**
- Sandboxed file access: the agent cannot read or write outside the workspace, symlinks and binary files are refused, and delete guards protect the workspace root.
- Workspace ignore files so builds and caches stay out of the agent's way.
- Context hygiene keeps prompts tight and redacts secrets before they reach the model.
- /init writes an AGENTS.md for the project; /doctor runs a 16 point self-test of every tool family.

**Slash commands**
- /clear, /compact, /cost, /doctor, /init, /skills, plus any skill or snippet by name.

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

## License

MIT. See LICENSE.
