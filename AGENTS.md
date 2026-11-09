# AndroidHarness — Agent Instructions

## Development Workflow

- **Branching**: feature branches from `main` (e.g. `feat/voice-auto-send`).
- **Commits**: Conventional Commits (e.g. `feat(voice): …`, `fix(chat): …`).
- **PRs**: Open against `main`; CI must pass (APK 32-bit build, lint).
- **Code style**: Kotlin, Jetpack Compose, Material3 Expressive.
- **Tests**: Device tests in `app/src/androidTest`; run with `./gradlew connectedAndroidTest`.

## Key Areas

- **Voice Input**: `VoiceInputController` (inbuilt + Groq Whisper), `MessageComposer` (silence detection + auto-send), `ChatViewModel.toggleVoiceAutoSend()`.
- **Settings**: DataStore via `SettingsRepository`; `AppSettings` data class; `AgentSettingsCatalog` for agent-facing keys.
- **Agent**: `AgentEngine`, `RunManager`, `ToolFailurePolicy`, `ChatContextPolicy`.
- **Compose UI**: `MessageComposer`, `VoiceRecorderCapsule`, `ChatScreen`, `SettingsScreen`.
- **MCP/Tools**: `McpManager`, `GroqWhisperClient`, `GroqTtsClient`.
- **Local LLM**: `GpuDeliveryFactory`, `GgufModelLoader`.

## Conventions

- **State**: `StateFlow` + `collectAsStateWithLifecycle` in UI; `MutableStateFlow` in VM.
- **DataStore**: `PreferencesDataStore` with `stringPreferencesKey`/`booleanPreferencesKey`/`intPreferencesKey`/`floatPreferencesKey`; `restorePortable` for backup migration.
- **Strings**: All user-facing text in `res/values/strings.xml` + `res/values-pt-rBR/strings.xml`.
- **Icons**: Prefer `Icons.AutoMirrored.Outlined.*` for directional icons.

## Current Feature: Voice Auto Send

- Settings: `voiceAutoSend` (Toggle), `voiceAutoSendTimeoutMs` (1000–5000 ms, 100 ms steps), `voiceAutoSendShowInPlus` (Toggle, default true).
- Engines: Inbuilt (Android SpeechRecognizer) + Groq Whisper (MediaRecorder + API).
- Silence detection: Inbuilt uses rms >8 dB + client-side timeout; Groq uses amplitude >0.03 or new peak + timeout watcher in controller.
- Interrupt-safe: Stop button suppresses auto-send for that turn.
- Empty-speech guard: discards transcriptions that are empty or only punctuation.
- + menu item gated by `voiceAutoSendShowInPlus`; optimistic toggle via `ChatViewModel.toggleVoiceAutoSend()`.
