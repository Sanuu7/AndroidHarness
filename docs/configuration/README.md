# Workspace configuration on Android

AndroidHarness reads optional `harness.json` (strict JSON) or `harness.jsonc` (comments and trailing commas) at the start of each **Code** run. Personal Chat agents remain managed by Chat settings. No config preserves existing behavior. Configuration discovery, validation, instructions, references and skills do not require Linux.

Open **Settings → Coding → Configuration**. Project edits the selected workspace; Global edits the app-private `config/harness.json[c]`. The screen previews active files and has a full-screen editor with validation, save-conflict detection and unsaved-change protection. Its project header identifies the selected workspace; this may differ from a particular chat's assigned workspace.

## Discovery and merge

Layers, from lowest to highest priority:

1. Enabled global plugins installed through the plugin manager.
2. App-private `config/harness.json` or `config/harness.jsonc`.
3. Enabled plugins installed for the primary Code workspace.
4. The primary Code workspace's `harness.json` or `harness.jsonc`.
5. `HARNESS_CONFIG`, a path relative to the primary workspace (also accepts connected folders and app home).
6. `HARNESS_CONFIG_CONTENT`, inline JSONC relative to the primary workspace.

Set these variables in the Global tab's Variables and secrets section, or in the process environment used to launch the Android app. Variables exported in a separate Termux/Linux shell do not automatically reach an already-running Android application.

Objects merge recursively, arrays concatenate, scalars replace and `null` clears an inherited value. A later array after `null` starts a new list. Every layer must be syntactically valid and have valid field types, even when a higher layer overrides it. The effective `default_agent` is validated after merging. Unknown fields are errors. If both extensions exist, JSON wins with a visible notice; the JSONC file is not merged. A bad config blocks the affected new run; it does not close chats or block editing the file. A running turn and its nested tasks retain their captured configuration.

The [JSON Schema](../harness.schema.json) can be used locally in an editor. The canonical URL in its `$id` becomes available when these repository changes are published; the app performs validation locally without downloading a schema.

## Files and paths

Paths stay relative to the document that declared each value after merging. `~/` means **AndroidHarness configuration home** (`<app-files>/config/home`), not Termux or the optional Linux environment. Connect extra folders from the Global tab and use `@shared/path`. Those aliases are stored on this phone and grant configuration reads only; they do not widen agent file-tool grants. Reconnect moved or revoked Android document folders. Absolute paths and `..` traversal are refused. Symlinks cannot leave a real filesystem's granted root.

Glob rules: `*` matches within one directory, `**` can cross directories, `**/` can match zero directories, and `?` matches one character. Matching is case-sensitive and results are sorted. Scans skip `.git`, `node_modules`, `build` and `.gradle`. Limits are explicit: 256 KiB per config, 2 MiB of configuration dependencies, 4096 scanned entries per glob, 24 directory levels and 64,000 instruction characters. Narrow the glob or move large background documents to references when a limit is reached.

```jsonc
{
  "instructions": ["docs/conventions.md", "rules/**/*.md", "!rules/archive/**"],
  "references": [{ "title": "Architecture", "path": "docs/architecture.md" }],
  "skills": {
    "paths": ["shared/skills", "@team/skills"],
    "enable": ["android-*"],
    "disable": ["*-experimental"],
    "auto_load": ["android-crash"]
  }
}
```

Both root `AGENTS.md` and `HARNESS.md` load automatically with a config, without duplication. Explicit `!AGENTS.md` and `!HARNESS.md` exclude them. Without a config, the existing AGENTS-first fallback is unchanged. Reference contents are not injected; `reference_read(name)` opens their numbered catalog entries. Add `"glob": true` for reference path globs.

Skill paths supplement bundled and user skills. Configured bundles and conventional `.harness/skills` are captured for the run, including supporting files. Conventional project skills win over additional paths; additional paths use declaration order, later wins. Project > user > bundled remains the source priority. Nonempty `enable` filters the catalog, `disable` wins, and app-disabled skills stay disabled. Auto-loaded skills must exist and be enabled. Missing explicitly configured folders/files are errors; create them or remove their entries before running. Save checks the edited document’s structure, while Validate checks all merged layers, references and auto-loaded skills. A valid document can be saved while another layer is broken, so the files can be repaired independently.

## MCP and secrets

Existing Settings servers and `.harness/mcp.json` remain supported. `harness.json` entries override same-named legacy project entries, including disabling them. Servers saved in the Settings UI retain authority on name conflicts. Project definitions require approval bound to the workspace, configuration, plugin scripts, legacy MCP file and resolved transport settings. Changing them invalidates approval. Refusing project tools still allows a run with normal tools and instructions.

```jsonc
{
  "mcp": {
    "local": {
      "type": "local", "command": ["node", "server.js"], "args": [],
      "env": { "TOKEN": "{secret:MY_TOKEN}" }, "enabled": true
    },
    "remote": {
      "type": "remote", "url": "https://example.com/mcp",
      "headers": { "Authorization": "Bearer {file:@secrets/token}" }, "enabled": false
    }
  }
}
```

`command` also accepts an executable string with a separate `args` array. Types `stdio`, `http` and legacy `sse` are accepted. Remote connections use native networking; local commands use the existing app-tier toolchain and never automatically install packages, request root or switch to Shizuku. Local commands need a compatible executable environment. Android can kill processes; connections are recovered by the existing MCP manager. Project connections are isolated by workspace and effective definition while public tool names remain `mcp__<server>__<tool>`.

Substitutions apply to MCP command/args/env/URL/headers only. `{env:NAME}` reads an app-managed variable, then the app process environment; unset variables become empty strings. `{secret:NAME}` reads the encrypted app secret store and missing secrets are errors for enabled servers. `{file:path}` reads a bounded UTF-8 file with existing grants and trims its final newline. Disabled servers do not resolve secrets. No substitutions run commands. Expanded credentials are excluded from prompt previews and redacted from tool results; raw secret values should never be committed to project files.

## Code agent profiles, permissions and memory

```jsonc
{
  "agents": {
    "reviewer": {
      "description": "Review without making changes",
      "instructions": ["rules/review.md"],
      "tools": { "write": false, "edit": false }
    }
  },
  "default_agent": "reviewer",
  "permissions": { "edit": "ask", "delete": "ask", "network": "deny" },
  "memory": {
    "file": ".harness/memory.md", "topics_dir": ".harness/memory",
    "auto_recall": true, "max_core_lines": 120
  }
}
```

`default` is the built-in Code profile; `agents.default` can supply additional rules for it. Named profiles can be selected by `task(agent="reviewer", ...)`. Task profiles inherit the parent's tool denials and cannot re-enable tools. Tool booleans support exact tool names/globs or groups `write`, `edit`, `read`, `delete`, `shell`, `network`. `true` cannot add tools the app did not expose. These profiles do not replace personal Chat agents.

Permissions never change the app's approval mode. `allow` preserves normal app checks, `ask` always asks even if a prior session grant exists, and `deny` blocks the operation. Arbitrary shell/MCP commands are opaque: edit/delete/network restrictions also apply to them, so a denied network category blocks opaque command execution rather than pretending to inspect all its effects. `outside_workspace` cannot grant access; normal modes already retain workspace containment. **Full access remains the user's explicit app choice and ignores config permission rules**, as requested by the proposal; profile tool denials still apply. Plan mode always remains read only. MCP server startup retains its separate exact-definition approval.

Workspace memory paths are always relative to the Code workspace, even in a global config. Prompt recall, core writes and topic tools use the same configured locations. Core memory retains the existing character limit and the configured line limit (1–500). `auto_recall: false` omits workspace notes from the prompt; the app's master memory switch remains authoritative for all memory tools.

## Restricted plugins

Use **Settings → Coding → Plugins** to discover, review, install, update, disable and uninstall bundles from npm, GitHub, archives or a phone folder. The [plugin manager guide](plugin-manager.md) describes the bundle format, scopes and installation behavior. Installed bundles can contribute skills, prompt instructions, MCP definitions and optional isolated hooks.

A hook bundle contains `plugin.json`, a standalone CommonJS entry, and optional skill folders:

```json
{"name":"review-policy","version":"1","entry":"index.js","hooks":["prompt.before","tool.before"],"skills":["skills"]}
```

```javascript
module.exports = {
  "prompt.before": async event => ({instructions: "Check relevant tests before reporting completion."}),
  "tool.before": event => event.tool === "git_push" ? {deny: "Publish from the app's Git interface."} : {}
};
```

For file-based configuration, set `plugins.paths` to directories containing bundles, or a bundle directory itself. `enable` and `disable` filter by plugin name. `plugins.npm` resolves already-installed packages under `node_modules` beside the declaring config; it accepts package names and does not download them. The separate plugin manager can download npm versions/tags and keeps an app-private installation registry without editing these config files. Packages must contain compatible standalone bundles; Node's `require`, filesystem APIs and network APIs are unavailable in the hook engine.

Hooks: `session.start`, `message.received`, `prompt.before`, `tool.before`, `tool.after`, `session.end`. Export functions by these exact keys. They can return a promise. Return `{instructions: "..."}` to contribute bounded run instructions, or `{deny: "reason"}` from `tool.before` to veto a call. Results cannot grant permission, change arguments, run commands or expose new tools. Tool events include redacted arguments before execution and success status afterward; prompt events include profile and iteration metadata. Each call gets a fresh isolate, so plugins must be pure and have no cross-hook global state. End-hook instruction results are discarded.

Hooks require a supported Android System WebView with AndroidX JavaScriptSandbox memory limits and isolate termination. There is no unsafe fallback. Each evaluation has a 16 MiB heap, a two-second timeout and an 8192-character result limit. Pure JS runs in a separate isolated process with no Android bridge, network bridge, secrets bridge or filesystem bridge. A failure is visible and stops the affected run. Skill-only bundles need no JavaScript runtime and are treated like ordinary project skills. At most 16 executable plugins can be active.
