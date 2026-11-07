# AndroidHarness repo wiki

Last updated 07/11/2026, generated from `main` at commit `aae55c4`.

75 pages covering the app's architecture, runtime, tools, UI and operations. Each page names the classes and files it describes, includes a flow diagram where one applies, and ends with links to the source it was written from.

Since the previous edition (September 2026) the app grew a separate Chat section, Live Mode, universal memory, scheduled automations, Telegram control, plugins and marketplaces, a skills manager, local models with GPU support, 9Router, SSH and FTP workspaces, an interactive terminal, the storage inventory, a settings search, 21 languages and a large amount of provider and compaction work. Pages 05 to 13, 21 to 22, 26 to 32, 40, 44, 52 to 58, 59 to 63, 64 to 67, 71 and 75 are new or substantially rewritten for that work.

## Contents

### Application Core

- [Application Initialization and State](01-application-initialization-and-state.md)
- [UI Shell, Navigation, and Layouts](02-ui-shell-navigation-and-layouts.md)
- [Foreground Service and Execution Host](03-foreground-service-and-execution-host.md)
- [Settings Architecture and Search](04-settings-architecture-and-search.md)

### Chat and Assistant UX

- [Chat Screen and View Model](05-chat-screen-and-view-model.md)
- [Conversation Lifecycle and History](06-conversation-lifecycle-and-history.md)
- [Message Rendering and Markdown](07-message-rendering-and-markdown.md)
- [Rich Responses and Interactive Components](08-rich-responses-and-interactive-components.md)
- [Activity Timeline and Tool Cards](09-activity-timeline-and-tool-cards.md)
- [Composer, Attachments, and Voice](10-composer-attachments-and-voice.md)
- [Chat Agents and Personalization](11-chat-agents-and-personalization.md)
- [Chat Search, Saved Answers, and Sharing](12-chat-search-saved-answers-and-sharing.md)
- [Chat to Code Handoff and Chat Workspaces](13-chat-to-code-handoff.md)

### Agent Runtime

- [Agent Execution Engine](14-agent-execution-engine.md)
- [Run Lifecycle, Recovery, and Verification](15-run-lifecycle-recovery-and-verification.md)
- [Context Management and Compaction](16-context-management-and-compaction.md)
- [Reasoning and Thinking Levels](17-reasoning-and-thinking-levels.md)
- [Streaming Retries and Fallback](18-streaming-retries-and-fallback.md)
- [Subagents, Parallel Delegation, and Model Resolution](19-subagents.md)
- [Todos, Steering, and Side Questions](20-todos-steering-and-side-questions.md)
- [Universal Memory, Recall, and Project Notes](21-universal-memory-and-project-notes.md)
- [Live Mode: Hands-Free Voice Conversation](22-live-mode.md)

### Models and Providers

- [LLM Core Contracts](23-llm-core-contracts.md)
- [Anthropic and Gemini Providers](24-anthropic-and-gemini-providers.md)
- [OpenAI Compatible and Responses Providers](25-openai-compatible-and-responses-providers.md)
- [ChatGPT Accounts](26-chatgpt-accounts.md)
- [Harness Keyless Provider](27-harness-keyless-provider.md)
- [Model Catalog and Capabilities](28-model-catalog-and-capabilities.md)
- [Pricing, Cost and Usage](29-pricing-cost-and-usage.md)
- [Provider Controls and Fallback Chains](30-provider-controls-and-fallback-chains.md)
- [Model Picker and Per-Chat Models](31-model-picker-and-per-chat-models.md)
- [Local Models and 9Router](32-local-models-and-9router.md)

### Tool Framework

- [Tool Framework, Dispatch, and Permissions](33-tool-framework-and-permissions.md)
- [Shell Execution and Device Access](34-shell-execution-and-device-access.md)
- [Shell Policy and Secret Redaction](35-shell-policy-and-secret-redaction.md)
- [Background Processes](36-background-processes.md)
- [File Tools, History, and Restore](37-file-tools-history-and-restore.md)
- [Patch, Diff and Fuzzy Editing](38-patch-diff-and-fuzzy-editing.md)
- [Git and GitHub Tools](39-git-and-github-tools.md)
- [Web Research and Search Backends](40-web-research-and-search-backends.md)
- [Network, HTTP and TLS Diagnostics](41-network-http-and-tls-diagnostics.md)
- [Browser Control and Web Preview](42-browser-control-and-web-preview.md)
- [Device and Environment Tools](43-device-and-environment-tools.md)
- [CodeGraph, Repo Map, and Code Search](44-codegraph-repo-map-and-code-search.md)

### Workspace and Storage

- [Workspace Manager and Routing](45-workspace-manager-and-routing.md)
- [Virtual File System, SAF, and Ignore Filters](46-virtual-file-system-saf-and-ignore-filters.md)
- [Storage Access and App Workspace](47-storage-access-and-app-workspace.md)
- [File Manager, Import, and Sharing](48-file-manager-import-and-sharing.md)
- [Editor, Language Tools, and Drafts](49-editor-language-tools-and-drafts.md)
- [Checkpoints, Undo, and File History](50-checkpoints-undo-and-file-history.md)
- [Storage Inventory and Cleanup](51-storage-inventory-and-cleanup.md)

### Extensibility

- [Plugin Manager and Discovery](52-plugin-manager-and-discovery.md)
- [Plugin Formats, Hooks and Sandbox](53-plugin-formats-hooks-and-sandbox.md)
- [Skills System and Manager](54-skills-system-and-manager.md)
- [Slash Commands, Snippets and Prompts](55-slash-commands-snippets-and-prompts.md)
- [Workspace Configuration](56-workspace-configuration.md)
- [MCP Client Manager](57-mcp-client-manager.md)
- [MCP Transports, OAuth and Tool Adapters](58-mcp-transports-oauth-and-adapters.md)

### Remote and Automation

- [SSH Remote Development](59-ssh-remote-development.md)
- [FTP and SFTP Transfers](60-ftp-and-sftp-transfers.md)
- [Interactive Terminal](61-interactive-terminal.md)
- [Scheduled Automations](62-scheduled-automations.md)
- [Telegram Remote Control](63-telegram-remote-control.md)

### Interface and Personalization

- [Theme Palettes and Harness Canvas](64-theme-palettes-and-canvas.md)
- [Motion, Glass, and Haptics](65-motion-glass-and-haptics.md)
- [Learn, Tips, and What's New](66-learn-tips-and-whats-new.md)
- [Localization](67-localization.md)

### Data, Security and Operations

- [Database and Persistence](68-database-and-persistence.md)
- [Backups and Encryption](69-backups-and-encryption.md)
- [Security Model and Approvals](70-security-model-and-approvals.md)
- [Background Reliability and Live Activities](71-background-reliability-and-live-activities.md)
- [Update Manager and Install Routes](72-update-manager-and-install-routes.md)
- [Troubleshooting and Diagnostics](73-troubleshooting-and-diagnostics.md)
- [Build, CI, and Testing](74-build-ci-and-testing.md)
- [Shared Core Models and Pure Utilities](75-shared-core-models.md)
