# Plugin manager

Open **Settings → Coding → Plugins**. Plugins extend **Code** runs. Personal Chat agents retain their own settings.

- **Installed** lists versions, sources and scopes. Enable or disable a bundle, inspect its contents, update it or uninstall it. Filter by the current project or Global; All includes other projects so their installations remain manageable.
- **Discover** offers two bundled starter packs, for review and regression testing. Community search uses the npm registry's `androidharness-plugin` keyword. Results are published by their authors and are not verified marketplace endorsements. Compatibility is checked against the downloaded files before installation.
- **Add** accepts an npm package, GitHub repository, local ZIP/TGZ archive or Android document folder. Every source opens a review before installation.

The review lists bundled instruction files, skill names, MCP servers and hooks. Choose the selected project or Global. Project scope follows the workspace identity, rather than the chat that opened Settings. A project installation overrides a global installation of the same plugin name; other plugins remain active. Updates keep the installation's identity, scope and enabled state.

Changes apply to the next Code run. Running turns keep their captured configuration and immutable bundle files. Updating or uninstalling a plugin does not edit `harness.json`, remove project files, or remove unrelated MCP settings. Files from superseded installations are reclaimed at the next app process start. Installed bundles are app-private and are not automatically shared with collaborators or included in the existing settings-backup format.

## Sources

**npm:** enter `plugin-name`, `@team/plugin`, `plugin-name@1.2.0` or `@team/plugin@beta`. Exact versions and tags are supported; dependency ranges, shell commands such as `npx …`, arbitrary registry URLs and dependency installation are not. The installer uses the registry API and checks the archive against its published integrity hash. It never executes npm lifecycle scripts. For community discovery, publishers can add `"keywords": ["androidharness-plugin"]` to `package.json`.

**GitHub:** enter `owner/repository` or its `https://github.com/owner/repository` URL. Optional fields select a branch, tag, commit and bundle subfolder. The requested ref is resolved to an immutable commit before the archive is downloaded. A connected GitHub account supplies access to private repositories when its token permits it. Updates explicitly resolve the chosen ref again; there is no silent background code update.

**Local:** choose an archive or a folder using the Android file picker. Imports copy their files into app storage; they do not continue reading mutable source files during runs. A folder whose root contains a valid `SKILL.md` is adapted into a skill-only bundle, including its supporting files. To update a local installation, choose its replacement source and review it again.

Downloads are limited to 25 MiB, expanded bundles to 64 MiB, individual files to 16 MiB, entries to 2048 and nesting to 24 levels. Archive extraction rejects escaping paths, links, duplicate paths and special files. Unsupported or damaged bundles fail before changing an existing installation.

## Bundle format

A compatible bundle has a root `plugin.json`. npm's `package/` wrapper and GitHub's repository wrapper are handled automatically. For a repository with several bundles, specify the bundle's subfolder.

```text
plugin.json
instructions.md
skills/
  review/
    SKILL.md
index.js                  # optional, only for hooks
server.js                 # optional, self-contained MCP server
```

```json
{
  "name": "team-review",
  "version": "1.0.0",
  "description": "Shared review workflow",
  "instructions": ["instructions.md"],
  "skills": ["skills"],
  "entry": "index.js",
  "hooks": ["prompt.before"],
  "mcp": {
    "docs": {
      "type": "remote",
      "url": "https://your-service.example/mcp",
      "headers": {"Authorization": "Bearer {secret:DOCS_TOKEN}"},
      "enabled": false
    }
  }
}
```

`name` is a stable identifier containing 1–64 lowercase letters, digits or hyphens, starting with a letter or digit. `version` is required. Optional metadata includes `description`, `author` and `homepage`. At least one instruction, valid skill, MCP server or hook is required. Unknown manifest fields are rejected. The [manifest schema](plugin.schema.json) describes the supported structure.

`instructions` contains relative UTF-8 file paths, up to 64 KiB per file and 64,000 bytes in total. Their text joins the Code system prompt. Keep large background material in project references instead. `skills` contains folders of skill subdirectories, each with `SKILL.md`; duplicate skill names inside one bundle are rejected. Explicit project skills retain priority over plugin skills.

`mcp` follows the workspace configuration MCP format. Server identifiers are namespaced by plugin name to prevent unrelated bundles from replacing each other's tools. Local server paths can use `{plugin:root}`, for example:

```json
{"mcp":{"tools":{"command":["node","{plugin:root}/server.js"]}}}
```

Managed local servers run on this phone, including when the selected workspace uses SSH. They need an executable/runtime supported by the app. Ship self-contained server files, use a supported existing executable, or configure a suitable `npx` command for runtime startup. The plugin installer does not install a server's dependencies. Remote servers use native networking.

MCP startup and executable hooks retain the existing exact-definition project approval. Installation does not start a server or grant execution permission. A changed server, hook or resolved secret invalidates its prior approval. Configure variables and encrypted secrets under **Coding → Configuration → Global**. Disabled servers can be installed before their secrets are available. Use the workspace configuration to override a namespaced server when required.

Hooks require a standalone JavaScript entry. They run in Android's isolated JavaScript runtime, with no Node `require`, filesystem or network bridge. Supported events, return values, time and memory limits are described in the [configuration guide](README.md#restricted-plugins). Skills and static instructions do not require that runtime.

Packages written for another harness are not automatically compatible. Repackage their supported skills, instructions and MCP definitions with this manifest, and adapt executable hooks to AndroidHarness's restricted API. An incompatible package is reported instead of being installed with missing functionality.
