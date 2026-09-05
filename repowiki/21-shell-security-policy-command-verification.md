# Shell Security Policy & Command Verification

> Command filtering rules, path restriction enforcement, and security policies for shell execution.

# Shell Security Policy & Command Verification

`ShellPolicy` validates shell commands prior to process execution. The engine evaluates destructive system commands, enforces workspace confinement, checks path traversals, verifies inline script contents, and manages signature-based tool approvals.

## Architecture & Verification Flow

```mermaid
flowchart TD
    CMD[Shell Command Input] --> DENY[System Command Denylist Check]
    DENY -->|Match Blocked Pattern| REJECT[Return Denial Reason]
    DENY -->|Pass| RAW[checkSandbox: Raw Command]
    RAW -->|Violation| REJECT
    RAW -->|Pass| EXP[expandVariables: Resolve Env & Local Vars]
    EXP --> EXP_CHECK[checkSandbox: Expanded Command]
    EXP_CHECK -->|Violation| REJECT
    EXP_CHECK -->|Pass| GRANT{isGranted Check}
    GRANT -->|Signature Approved| EXEC[Execute Process]
    GRANT -->|Prompt Required| PROMPT[User Permission Dialog]

    subgraph Sandbox Enforcement
        RAW & EXP_CHECK --> SUB[extractSubshells: Recursive $(...) and `...`]
        SUB --> VARS[extractVariableAssignments]
        VARS --> CD[CD_REGEX: Directory Changes]
        CD --> TOK[extractTokens & checkCommandTokens]
        TOK --> PATH[Path Resolution & Boundary Checks]
    end
```

### Flow Nodes
- **System Command Denylist Check**: Matches non-overridable root-level destructive patterns. Rejects before lexical analysis.
- **checkSandbox (Raw & Expanded)**: Runs security inspection twice—first against raw command string, then against variable-expanded command string to block obfuscated traversals.
- **extractSubshells**: Extracts command substitutions recursively; feeds nested subcommands back into `denyReason`.
- **extractTokens & checkCommandTokens**: Splits sequences by operators (`;`, `&&`, `||`, `|`, `&`), resolves command names, applies per-command argument semantics.
- **Path Resolution**: Validates targets against `workspaceRoot` canonical path and system directory allowlist.

---

## Module Responsibilities

- **System Hazard Mitigation**: Rejects root deletions, shell pipe downloads, filesystem formatting, raw block device writes, fork bombs, and root permission overrides.
- **Path Confinement**: Confines command arguments, redirection destinations, directory navigation (`cd`), and variable assignments within `workspaceRoot` or allowed system paths.
- **Android Storage Enforcement**: Restricts symlink creation (`ln -s`) on Android shared storage filesystems (`/storage/emulated/0`, `/sdcard`), permitting symlinks only when paths map to execution scratch directories.
- **Fine-Grained Authorization**: Generates command signatures from the first two tokens. Prohibits global shell authorization grants.

---

## Call Chain

```
ShellTool.kt / BgShell.kt
 └─ ShellPolicy.commandOf(argumentsJson)
 └─ ShellPolicy.denyReason(command, workspaceRoot, cwd)
     ├─ isRmRoot(cmd)
     ├─ checkSandbox(cmd, workspaceRoot, effectiveCwd)
     │   ├─ extractSubshells(cmd) -> ShellPolicy.denyReason(...) [Recursive]
     │   ├─ extractVariableAssignments(cmd) -> checkSinglePath(...)
     │   ├─ CD_REGEX.findAll(cmd) -> checkSinglePath(...)
     │   ├─ extractTokens(cmd) -> checkCommandTokens(...)
     │   │   ├─ isSymlinkCreation(tokens) && isSharedStorage(cwd)
     │   │   └─ checkSubCommand(...)
     │   │       ├─ isRedirectionOp(...) -> checkSinglePath(..., isRedirection = true)
     │   │       ├─ Argument skipping (echo, printf, grep pattern, sed script)
     │   │       ├─ Script interpreter inspection (python, bash, eval, node -c/-e)
     │   │       │   ├─ extractEmbeddedPaths(token) -> checkAbsolutePath(...)
     │   │       │   └─ checkRelativePath(...)
     │   │       └─ checkSinglePath(...)
     │   └─ TRAVERSAL_REGEX.findAll(cmd) -> checkRelativePath(...)
     ├─ expandVariables(cmd, workspaceRoot, effectiveCwd)
     └─ checkSandbox(expandedCmd, workspaceRoot, effectiveCwd)
 └─ ShellPolicy.grantKey(tool, command)
 └─ ShellPolicy.isGranted(tool, command, allowedSignatures)
```

---

## Key Security Policies & Boundary Rules

### Multi-Step Verification Sequence
To evaluate shell commands safely without false negatives caused by shell escaping or environment substitution, `ShellPolicy` performs evaluation in the following strict order:

1. **System Destructive Pattern Matching**: Compare command string against non-configurable regex patterns (`PIPE_TO_SHELL`, `MKFS`, `DD_DEV`, `FORK_BOMB`, `CHMOD_ROOT`, `RM`). Terminate immediately on match.
2. **Subshell Extraction**: Find all nested command substitutions via `$(...)` balanced-parentheses traversal and backtick regex `` `...` ``. Pass each subshell command into `denyReason` recursively.
3. **Environment and Variable Assignment Traversal**: Extract variable assignments (`KEY=value`). Ensure assigned strings containing file paths resolve inside `workspaceRoot`.
4. **Command Token Parsing**: Parse tokens using custom tokenizer `extractTokens` supporting quotes, escapes, file descriptors (`2>`, `&>`), and chaining operators (`&&`, `||`, `;`, `|`).
5. **Context-Aware Subcommand Argument Evaluation**:
   - For `echo` and `printf`: Ignore arguments as string literals.
   - For `grep`, `rg`, `egrep`, `fgrep`: Skip the first non-option token (search pattern); validate subsequent file paths.
   - For `sed`, `awk`: Skip the first non-option token (script payload); validate target files.
   - For script runners (`python`, `python3`, `node`, `perl`, `ruby`, `sh`, `bash`, `eval` using `-c` or `-e`): Extract absolute paths with `ABS_PATH_REGEX` and scan relative traversal tokens (`../`) inside inline scripts.
6. **Double-Pass Variable Expansion**: Expand `$PWD`, `$CWD`, `$WORKSPACE`, `$HOME`, local assignments, and ANSI-C escape sequences (`$'\x2e\x2e'`). Execute full sandbox checks on the expanded string to prevent variable-based path escape.

### Security Warnings

> **Shared Storage Symlink Restriction**: Android shared storage (`/storage/emulated/0`) uses FUSE/sdcardfs and cannot support POSIX symlinks. `ShellPolicy` blocks symlink creation targeting shared storage. Symlinks are permitted solely when all paths reside in scratch directories (`SCRATCH_ROOTS`).

> **Blanket Authorization Prohibition**: Granting generic permission to the `shell` tool is forbidden. Authorizations require a composite key (`shell#<token1> <token2>`). Approving `git status` does not authorize `git push` or arbitrary binaries.

---

## Critical State & Allowed Paths

`ShellPolicy` maintains zero mutable state. Decisions depend on caller parameters (`command`, `workspaceRoot`, `cwd`) and static system path allowlists:

### Allowed System Path Prefixes (`isAllowedSystemPath`)
- Read/execute runtime roots: `/system`, `/vendor`, `/apex`, `/bin`, `/usr/bin`, `/usr/lib`, `/sbin`, `/system_ext`, `/odm`, `/product`.
- OS pseudofs: `/dev`, `/proc`, `/sys`, `/tmp`.
- Application sandbox scratch roots:
  - `/data/local/tmp/androidharness`
  - `/data/local/tmp/androidharness-scratch`
  - `/data/data/com.androidharness/files/.harness-scratch`
  - `/data/user/0/com.androidharness/files/.harness-scratch`
  - `/data/data/com.androidharness.debug/files/.harness-scratch`
  - `/data/user/0/com.androidharness.debug/files/.harness-scratch`
- App Linux environment root: `/data/data/com.androidharness/files/linux` (including `.debug` variants).

---

## Primary Files

- `app/src/main/java/com/androidharness/app/tools/ShellPolicy.kt`: Primary security verification object. Contains lexical tokenizer, denylist regexes, variable expansion, path confinement logic, and signature generators.

---

## Extension Points

- **System Binary Whitelist Additions**: Register custom SDK or toolchain paths in `ShellPolicy.isAllowedSystemPath`.
- **Command Argument Evaluators**: Add custom argument rules in `ShellPolicy.checkSubCommand` for utilities requiring unique operand skipping (similar to `grep` pattern handling).
- **System Hazard Rules**: Add regex signatures to top of `ShellPolicy.denyReason` for blocking architecture-specific dangerous binaries.

---

Sources: [app/src/main/java/com/androidharness/app/tools/ShellPolicy.kt](app/src/main/java/com/androidharness/app/tools/ShellPolicy.kt#L1-L616)

## Source files

- `app/src/main/java/com/androidharness/app/tools/ShellPolicy.kt`
