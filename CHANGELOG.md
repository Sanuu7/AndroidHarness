# Changelog

## 0.2-alpha (versionCode 3)

### Fixed
- Slash commands now work while the agent is running. Local commands (`/cost`, `/clear`, `/skills`) fire instantly, and `/init`, snippets, and skills expand first then queue for the next turn instead of doing nothing or killing the run.
- Picking a skill no longer sends it straight to chat. It attaches as a removable badge inside the input box; you add a note if you want and tap send when ready.
- Reasoning selector chips (Off / Low / Medium / High / X-High / Max) now wrap onto extra lines instead of clipping Max off-screen on models that support every level.

### Added
- Skills system: bundled, user, and workspace skill layers with slash invocation, an in-chat picker sheet, and a Settings screen for managing them.
- Unit tests covering slash command resolution, dispatch while busy/idle, and skill attachment.

### Notes
- Release APK is signed with the debug keystore for local alpha installs only.
