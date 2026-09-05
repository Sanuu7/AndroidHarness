# Android SAF Path Resolution

> Resolution between Android Storage Access Framework content URIs and direct filesystem paths.

### Module Responsibility

`SafPathResolver` converts Android Storage Access Framework (SAF) tree URIs into absolute POSIX filesystem paths. It unlocks shell execution by upgrading file-provider-restricted workspaces (`SafFs`) into direct filesystem workspaces (`FileFs`) on shared internal or external storage. It also standardizes project deduplication keys across access mechanisms.

---

### Call Chain and Resolution Flow

```mermaid
flowchart TD
    A[treeUri: Uri] --> B[DocumentsContract.getTreeDocumentId]
    B -->|docId null or throws| C[Return null]
    B -->|docId string| D{treeUri.authority}

    D -->|"com.android.externalstorage.documents"| E[resolveExternal]
    D -->|"com.android.providers.downloads.documents"| F{docId == "downloads"}
    D -->|Other cloud / recents| C

    F -->|true| G[ExternalStorage/Download]
    F -->|false| E

    E --> H[Split docId by ':' limit 2]
    H --> I{volume token}

    I -->|"primary" or "home"| J[Environment.getExternalStorageDirectory + rel]
    I -->|volume isNotBlank| K[/storage/volume + rel]
    I -->|blank / invalid| C

    J --> L[Absolute filesystem path]
    K --> L
    G --> L
```

#### Resolution Lifecycle Sequence

For multi-step workspace ingestion in `WorkspaceManager.addPickedFolder`:

1. Caller supplies user-picked SAF tree `Uri`.
2. `WorkspaceManager.findDuplicate` computes `dedupeKey` via `SafPathResolver.resolve(treeUri)` to verify if folder already exists under `KIND_SHELL` or `KIND_SAF`.
3. `SafPathResolver.resolve` decodes tree URI document ID and authority:
   - Evaluates `treeUri.authority` against known local storage document providers.
   - Parses document identifier tokens into volume identifier and relative subpath.
   - Computes target filesystem string. Returns `null` if provider is remote, virtual, or unmapped.
4. `WorkspaceManager` inspects returned string:
   - If path resolves and `File(path).isDirectory` is true, calls `addShellProject(path)`. Workspace gets `KIND_SHELL` with full shell execution capabilities.
   - If path is null or directory missing, falls back to `addSafProject(treeUri)`. Workspace gets `KIND_SAF` and restricts tooling to content resolver operations.
5. Workspace runtime calls `fsFor(project)`. If resolved path exists on disk, `FileFs` instantiates; otherwise initializes `SafFs`.

---

### Key States and Data Representations

- **Input URI**: SAF Tree URI formatted as `content://<authority>/tree/<treeDocumentId>`.
- **Authority Matching**:
  - `com.android.externalstorage.documents`: Shared internal storage and physical SD/USB volumes.
  - `com.android.providers.downloads.documents`: System download manager trees.
  - Third-party/Cloud URIs (`com.google.android.apps.docs.storage`, etc.): Resolve directly to `null`. Shell execution unsupported.
- **Document ID Format (`volume:relative_path`)**:
  - `primary` / `home`: Maps to `Environment.getExternalStorageDirectory().absolutePath` (`/storage/emulated/0`).
  - `<UUID>` (e.g., `0F1C-2A3D`): Secondary removable storage mounted under `/storage/<UUID>`.
  - Empty relative path: Evaluates to mount point root. Non-empty relative path: Appends `/<rel>`.
- **Deduplication Identity (`dedupeKey`)**:
  - Mapped SAF project key: `SHELL:<resolved_path>`. Matches shell projects targeting same physical directory.
  - Unmapped SAF project key: `SAF:<uri_string>`. Retains raw URI.

---

### Main Files

- `app/src/main/java/com/androidharness/app/workspace/SafPathResolver.kt`: Implements `SafPathResolver` singleton. Encapsulates document ID token splitting, authority checking, and path construction.
- `app/src/main/java/com/androidharness/app/workspace/WorkspaceManager.kt`: Consumes `SafPathResolver` within `fsFor`, `addPickedFolder`, `describe`, and `dedupeKey`. Upgrades project entities from `KIND_SAF` to `KIND_SHELL`.

---

### Boundary Conditions

- **Non-Standard Authorities**: Cloud providers (Google Drive, OneDrive, Nextcloud) and Recents providers fail authority check. Return `null`. Agent falls back to `SafFs`. Shell tools disabled.
- **Malformed Tree URIs**: Document extraction wrapped in `runCatching`. Malformed URIs returning exceptions result in `null`.
- **Raw Download Identifier**: Authority `com.android.providers.downloads.documents` with `docId == "downloads"` bypasses colon parsing. Directly targets `Environment.getExternalStorageDirectory()/Download`.
- **Missing Relative Paths**: Split on `:` with limit 2. Missing second token defaults to empty string (`parts.getOrElse(1) { "" }`). Returns bare storage mount path without trailing slash.
- **Physical Directory Verification**: `SafPathResolver` performs purely textual resolution. `WorkspaceManager` enforces `File(path).isDirectory` validation before promoting to `FileFs` or `KIND_SHELL`. Prevents shell crashes if physical volume unmounted.

---

### Extension Points

- **Additional Storage Providers**: Add cases inside `SafPathResolver.resolve(treeUri)` `when (treeUri.authority)` block (e.g., MediaProvider document trees).
- **Custom Secondary Storage Mount Points**: `resolveExternal` constructs `/storage/$volume`. Custom ROMs or container runtimes mounting secondary storage elsewhere require adjusting target prefix resolution.

---

Sources: [app/src/main/java/com/androidharness/app/workspace/SafPathResolver.kt](app/src/main/java/com/androidharness/app/workspace/SafPathResolver.kt#L1-L47), [app/src/main/java/com/androidharness/app/workspace/WorkspaceManager.kt](app/src/main/java/com/androidharness/app/workspace/WorkspaceManager.kt#L67-L120), [app/src/main/java/com/androidharness/app/workspace/WorkspaceManager.kt](app/src/main/java/com/androidharness/app/workspace/WorkspaceManager.kt#L185-L278)

## Source files

- `app/src/main/java/com/androidharness/app/workspace/SafPathResolver.kt`
