# Environment Inspection & Probes

> Hardware, operating system, and Android environment detection routines.

### Module Responsibilities

Inspects runtime environment across Android isolation boundaries. Evaluates toolchain availability, permission bits, execution tiers, and privileged daemon state without blind filesystem assumptions.

- `EnvProbes`: Static tier-aware probing routines. Dispatches queries based on target directory permissions.
- `EnvStatusTool`: Agent tool (`env_status`). Aggregates Shizuku state, active shell tier, storage permissions, TLS bundles, and toolchain presence.
- `DoctorTool`: Agent tool (`doctor`). Validates GitHub token authorization, credential permissions, git transport, and API quotas.

---

### Core Call Chain

```mermaid
flowchart TD
    A[Agent / Caller] --> B[EnvStatusTool.execute]
    B --> C[ShizukuManager.state / serviceState]
    B --> D[ShellTierRouter.resolveTier]
    B --> E[EnvProbes.probeRoot]
    E -->|PRIVILEGED & Deployed| F[/data/local/tmp/androidharness/linux]
    E -->|APP_LINUX or Undeployed| G[LinuxEnvironmentManager.prefix]
    B --> H[EnvProbes.commandPresence]
    H --> I[ShellTierRouter.run 'command -v']
    B --> J[EnvProbes.fileMode]
    J -->|Tmp Prefix| K[ShizukuManager.runPrivileged stat]
    J -->|App Prefix| L[android.system.Os.stat]
```

- `EnvStatusTool.execute`: Queries Shizuku status, resolves execution tier for active workspace path.
- `EnvProbes.probeRoot`: Directs target root to `/data/local/tmp/androidharness/linux` for privileged tier or internal prefix for app tier.
- `EnvProbes.commandPresence`: Dispatches batch `command -v` checks inside live shell router. Detects linker shims and PATH resolution.
- `EnvProbes.fileMode`: Selects query implementation. App UID cannot read `/data/local/tmp`; invokes Shizuku privileged shell for external stat queries.

---

### Key States

- **ShizukuState**: `NOT_INSTALLED`, `NOT_RUNNING`, `RUNNING_NO_PERMISSION`, `GRANTED`.
- **UserServiceState**: `BOUND_READY`, `NOT_BOUND`, `BIND_FAILED`. Reflects binder connection status for privileged execution helper.
- **ExecutionTier**:
  - `PRIVILEGED`: Root or ADB execution context via Shizuku.
  - `APP_LINUX`: App UID execution leveraging proot/linker shims inside application sandbox.
  - `TOYBOX`: Basic Android fallback shell (`/system/bin/sh`).
- **Tool Presence**:
  - `ok`: Executable found via shell path lookup (`command -v`).
  - `missing`: Binary absent from search paths.
  - `null`: Probe execution failure (shell crash, active toolchain redeployment).

---

### Main Files

- `app/src/main/java/com/androidharness/app/tools/EnvProbes.kt`: Tier-routed probing helpers (`probeRoot`, `fileMode`, `commandPresence`).
- `app/src/main/java/com/androidharness/app/tools/EnvStatusTool.kt`: Implementation of `env_status` tool interface and status formatter.
- `app/src/main/java/com/androidharness/app/tools/DoctorTool.kt`: Implementation of `doctor` verification tool for GitHub authentication and permissions.

---

### Boundary Conditions

- **App UID Filesystem Blindness**: App sandbox cannot inspect `/data/local/tmp` directly. `java.io.File.exists()` returns `false` on present files. `EnvProbes.fileMode` delegates through `ShizukuManager.runPrivileged` executing `stat -c %a`.
- **Linker Shim Resolution**: Android executable execution depends on custom linker shims. Direct disk file existence checks fail to confirm runnable status. `EnvProbes.commandPresence` executes `command -v` inside target runtime.
- **Redeployment Races**: In-flight toolchain extractions gut binary prefixes. If `commandPresence` returns zero recognized lines, output treated as `null` (unknown) rather than missing.
- **Missing `/bin/bash`**: Android lacks root `/bin/bash`. Tool status warns agent scripts requiring shebang conversion to `#!/system/bin/sh` or explicit `$PREFIX/bin/bash` execution.
- **Credential Permissions**: Token files verified for raw permission mode strings (e.g. `600`). Prevents insecure `0755` permission deployment.

---

### Extension Points

- **Headline Tools Registry**: Extend `headlineTools` list in `EnvStatusTool.kt` to expose additional binary probes (e.g., `make`, `clang`, `rustc`).
- **Tier-Routed Probes**: Implement new query methods on `EnvProbes` following `fileMode` pattern for SELinux context checks or disk quota checks.
- **Diagnostic Checksets**: Add diagnostic flags to `DoctorTool` parameters schema to probe additional subsystems beyond GitHub credentials.

---

Sources: [app/src/main/java/com/androidharness/app/tools/EnvProbes.kt](app/src/main/java/com/androidharness/app/tools/EnvProbes.kt#L1-L92), [app/src/main/java/com/androidharness/app/tools/EnvStatusTool.kt](app/src/main/java/com/androidharness/app/tools/EnvStatusTool.kt#L1-L166), [app/src/main/java/com/androidharness/app/tools/DoctorTool.kt](app/src/main/java/com/androidharness/app/tools/DoctorTool.kt#L1-L158)

## Source files

- `app/src/main/java/com/androidharness/app/tools/EnvProbes.kt`
- `app/src/main/java/com/androidharness/app/tools/EnvStatusTool.kt`
