# Changelog

## Unreleased

### Added

- **Automatically continue questions**: in Code, a question the agent asks now shows a Recommended badge on the option it prefers, and if nobody answers within 5 minutes the app continues with that option while a countdown shows on the card. The agent is told the app chose, not you, so it can say so and change course. Settings → Coding → Agent behavior has the switch (on by default); turning it off makes current and future questions wait for you. Open-ended questions with no options always wait.
- **Show to-do list**: Settings → Coding → Code conversation & commands can hide the agent's to-do list in Code. It is on by default and only changes what you see.
- **Allow subagents**: a master switch at the top of Settings → Coding → Sub-Agent settings. Off removes the subagent tools, tells the agent they are unavailable, blocks any attempt to start one and stops running subagents. The agent can turn it off when you ask but never back on; only you can.
- **Agent settings reach more of the app**: asking the agent to change settings now also covers auto compact, the resume prompt, the new switches above, cross-section memory, Canvas in Chat and Code, local model instructions and tools, Live Mode (speak replies, pause length) and the Whisper model. Anything that widens what the agent does asks for your approval first.
- **Discover sorting and filters**: sort by most stars, recently updated, most downloaded, newest, oldest or name, and filter by type, source, category and setup. Cards show GitHub stars, npm downloads and update times when the source reports them, and stars are fetched for listings that only link to a GitHub repository.
- **Installed status in Discover**: plugins you already have show an Installed banner with their Global or project scope, and an Update button when the catalog lists something newer. All, Installed, Not installed and Updates buttons hide what you already have, and the Installed tab gets an Update available badge, an Updates filter and search.
- **Skills manager**: Settings → Skills is now a searchable list with Enabled and Disabled filters, source chips, per-skill switches and bulk Enable, Disable and Delete with Undo. A detail sheet shows the rendered skill, its Markdown source and files, and a full-screen editor replaces the small dialog; editing a bundled skill saves your own copy.
- **Storage inventory**: Settings → Storage now lists every installed component with its size and real location, grouped into App data, Plugins, Skills, Workspaces, Linux packages, Local models and Caches. That covers each plugin (global and per workspace), user, workspace and bundled skills, each recorded Linux package plus global npm and pip packages, local models and unfinished downloads, every saved workspace, the chat database, settings, web page data and background tasks, and the npm, pip, Gradle, Linux package, plugin preview and model catalog caches. Related folders such as the different settings stores show as one item. Items open a detail sheet with every location, the file count, owners and a Copy path action, and the list can be searched or sorted by largest.

- **Interactive terminal**: the Terminal now opens a real shell on a pseudo-terminal, so full-screen and interactive programs work: nano, fzf, Python and Node REPLs, `top` and prompts such as `git add -p`. Keys go straight to the running program, colors (16, 256 and true color), cursor movement and screen redraws render correctly, and a long-running command keeps going while you leave the screen or rotate the phone. Two rows of extra keys add Esc, Ctrl, Alt, Tab, arrows, Home, End, Page Up and Page Down, a Ctrl+C key and an Fn row with F1 to F12 and common symbols. Ctrl and Alt apply to the next key on one tap and stay on after a second tap. Hardware keyboards, mouse reporting, pinch to resize text, scrolling back through output, paste and copy are supported. The shell is bash from the Linux environment, or Android's basic shell when that is not installed, and the button beside the title switches back to command mode.

### Changed

- **Plugins and skills design**: Material 3 Expressive styling with shaped type badges, connected button groups, grouped lists and a floating selection toolbar.
- **Storage scanning**: a Rescan button shows which category is being measured and how far along it is, and Stop cancels the scan. Measuring runs in the background with a time limit, and the last finished scan stays on screen while a new one runs.

### Fixed

- **Skill list refresh**: adding, editing or deleting a skill now updates the list right away.
- **Storage totals**: installed packages, plugins and other components no longer go missing from Settings → Storage, and the total now matches what is on disk. Each file is counted once even when it is hard linked, shared by several packages or inside another listed folder, and shared caches and shared package files appear as their own items. Anything that cannot be measured (permission denied, picker-only or SSH workspaces, a missing folder, a scan error or a timeout) is listed as Not measured with the reason, and the total says how many items it leaves out.

## 3.3 (2026-11-05)

### Added

- **Live Mode** (#77): a hands-free voice mode for talking through an idea. Start it from the empty message box or the + menu, talk normally, and hear replies read aloud while the conversation is saved to this chat. Three levels set what it can do (talk only, read files and search, or read and start tasks, which sends the plan to the main agent under the chat's usual permissions and approvals), and you can pick a fast model, pause length, speech engine and whether replies are spoken. Settings stay on this device.
- **Auto-compaction threshold** (#81): Code can summarize older context at a point you choose instead of a fixed 80%. Settings → Coding → Agent behavior accepts 250K, 258K, 350K, 1M, 1 million or an exact token count, and `/autocompact` sets it for one chat, with `default` returning to the app setting. Space for the reply is always reserved, a running task uses the new value at its next request, and a per-chat override survives restarts.
- **Context meter** (#81): a ring beside the Code composer shows how full the working context is, amber near 75% and red near 90%. Tapping it opens used and remaining tokens, whether the figure is an estimate or the last request, whether earlier messages were summarized, cached tokens and cache hit rate, with links to Context limits and a Compact action that leaves the draft alone.
- **Subagent button** (#81): background subagents now show as a labelled button above the composer accessories instead of a wide bar. It shimmers while agents run, can be dragged anywhere and remembers that position across chats and restarts, opens the agent list and per-agent details (Open chat, Stop, Back), and fades a couple of seconds after the last agent finishes. Opening a chat with completed subagents no longer revives it.
- **More app languages**: Vietnamese, Turkish, Malay, Thai, French, Hindi, Korean, Tamil, Bengali, Filipino and Traditional Chinese, for 21 languages in total. Traditional and simplified Chinese are separate choices, legacy Tagalog requests map to Filipino, and script or region variants such as zh-TW, zh-HK and fr-CA select the right option.
- **Markdown preview** (#74): Markdown files open rendered in the code editor with a Show preview/Show source toggle, using the chat's Markdown rendering and its 400k-character limit.
- **File and folder import**: the file manager can import individual files or a whole folder from the device, and the chat attachment picker accepts several files at once, with progress and an imported count.
- **Plugin MCP servers in Connected tools**: Settings → MCP lists the MCP servers supplied by enabled plugins, with their plugin, Global or Project scope, workspace approval state and live status, plus links to manage the plugin or review workspace permissions. Plugin servers no longer appear twice.

### Changed

- **Settings search**: every setting is searchable by name and description, results open the exact control, collapsed sections expand on arrival and the match is highlighted. The app-owned workspace can also use privileged shells, under Settings → Environment → Advanced device access.
- **Chat answer formatting**: guidance now keeps paragraphs to two to four sentences with blank lines between blocks and breaks long answers into headed sections, so replies read better on a phone.
- **Links in answers**: only a link that trails a sentence collapses to a citation icon. A link that opens its line or list item, follows a colon, or is a bare URL stays visible and tappable, which also keeps requested link lists from disappearing into icons.
- **Search key pools** (#75): each search provider can hold up to 20 keys. A key that is rejected, rate limited or out of credit is skipped for the next enabled one, and key checks report which key failed.
- **Harness free limits**: an exhausted free quota or a temporarily limited free model now opens a dialog with a plain explanation, the suggested wait time and a Switch Provider action, instead of a raw error snackbar. Continuing a task after switching uses the provider you picked.
- **Images in answers**: the image-frequency setting now decides how often pictures appear, not how many or where. Answers can mix inline pictures with rich components before, between or after prose, galleries accept as many useful images as the answer needs, and the model may explain a shortfall instead of inventing URLs.
- **Plugin installs**: catalog cards and pasted sources show live progress while resolution, runtime setup and installation run, with a Cancel action and a full loading state on Discover, so a slow install no longer looks stuck.
- **Memory entry limits** (#81): the 600-character limit per memory is now a setting with a custom count or an unlimited option. Saved entries are never truncated, digests keep their own allowance, and long entries appear as query-aware excerpts in prompts and recall with a pointer to the full text.

### Fixed

- **Gemini tools** (#80): tool schemas are sent through Gemini's JSON Schema field, so tools with dictionary arguments (such as HTTP headers) and nested schemas work instead of being rejected.
- **9Router npm installs** (#82, #84): lazy npm and npm lifecycle installs from the router now run through the app's own Node and shell shims, with arguments, working directory, environment and exit status preserved. Shared launchers are not rewritten, and other commands such as d8, r8 and minify keep their normal path.
- **Rich response recovery**: providers that wrap a JSON callout or card in `::callout` or `::card` directives instead of a harness-ui fence now render correctly, including in already-saved chats, with the usual schema and URL validation.
- **Side questions** (#76): the side-question input stays above the keyboard.
- **Empty tool arguments** (#79): a tool call that sends empty arguments now gets a hint naming the arguments it forgot, instead of a bare failure.

## 3.2 (2026-11-01)

### Added

- **Workspace configuration**: a `harness.json` or `harness.jsonc` in the workspace, plus a global one in the app home, configures Code runs: project instructions (the AGENTS.md/HARNESS.md behavior folds in), background references read on demand through a new `reference_read` tool, skill folders with enable/disable and auto-load rules, named Code agents the task tool can target with per-agent tool rules, memory file and topic locations, MCP servers with `{env:}`, `{file:}` and `{secret:}` substitution, and per-group ask/deny permissions layered under the app's own approvals. Every loaded value is redacted from what the model sees, paths cannot leave their granted folder, and a changed secret invalidates the workspace's tool approval. Managed in Settings → Coding → Configuration, documented with a guide and JSON schema.
- **Plugins**: install AndroidHarness plugin bundles from npm (with integrity verification), GitHub (pinned to an immutable commit), a local archive or folder, or the bundled starters. Every install is previewed with its actual contents before it lands, bundles are re-verified against their digest before each use so a tampered plugin is refused, and a plugin can add instructions, skills, hooks and MCP servers. Hooks run in an isolated JavaScript sandbox with no file or network access, may deny a tool call, and only run after the usual approval. Managed in Settings → Coding → Plugins.
- **Side questions**: `/btw` asks a quick question while the agent keeps working, in Chat and Code. It replays the main task's latest request unchanged, so the answer sees every file and decision so far and is served from the same prompt cache, with the hit rate shown under the answer. Read-only tools may run, nothing is ever written to the conversation, and only the token usage counts toward the chat's cost. Without a running task it answers from a transcript instead.
- **Steering**: messages sent while the agent works are read at its next step under the new AH Auto mode. The message is saved before it leaves the queue, so a crash never loses or duplicates it, the model is told it arrived mid-task, and a message sent during the final answer keeps the turn going. Settings → Agent can restore the old wait-for-the-whole-task behavior.
- **GitHub pull requests**: after committing and pushing, open a pull request from the chat (one tool, user-approved) or from the Commit & push sheet. The branch is compared with its base without moving any refs, unpushed or stale branches are refused with the next step, an already-open pull request is returned instead of a duplicate, and the description is generated from the branch's commits, changed files and the build/test checks recorded in the chat.
- **Custom visuals**: models draw diagrams, charts, widgets and mockups as raw `harness-svg` and `harness-html` fences instead of escaped JSON. Visuals inherit the app's live theme colors and follow custom palettes and the Canvas, are built from a shared class kit, and a `visual_guide` tool loads detailed drawing rules per kind. While one streams, a titled placeholder holds its place instead of flashing half-written markup.
- **AH Rumble**: the phone pulses as a chat turn moves through thinking, tool start, tool finished, tool failed, writing the reply, answer finished and needs-you, each with its own pattern and level. Pulses use Android's built-in haptic effects when the motor supports them, otherwise a shaped custom waveform. Five hand-tuned sets are offered, a random one is picked on first run with a Shuffle for another, and a smooth flow hums for as long as a reply streams, changing feel while a visual is being written. Chat pulses by default, Code has its own switch, and battery saver plus a spacing guard keep bursts calm.
- **Saved answers**: bookmark any answer and a copy of its text is kept, so it survives regenerating, editing or rewinding the chat. The sidebar's Saved list shows each with its chat, age and a prose preview, and the sheet opens the stored text with Open in chat, copy and remove with undo.
- **Agent-managed settings**: ask for a change ("turn on dark mode", "set rumble strength to 40") and the agent looks the setting up and changes it. Every change lands on a card with before and after values, an Undo per change and links to each setting. Sensitive settings always ask first, the feature can be turned off in Settings → Agent, and things only you can do (API keys, sign-ins, downloads, plugins, language) become links to the right page.
- **Retry on another model**: regenerating an answer, or retrying after an interruption, can run that one turn on a different model from your favourites and recents, without changing the chat's own model and with silent fallback disabled, so the answer really comes from the model you picked.
- **Local model downloads and hub**: interrupted downloads resume where they stopped, after a dropped connection or a pause, and a paused model shows how much was already stored. Explore can search Hugging Face with popularity sorting and suggestions tuned to the phone's chip, memory and storage, and a storage card breaks the model folder into installed, unfinished, leftover and other, with leftovers cleanable.
- **On-device Android builds**: `android_sdk_install` sets up OpenJDK, D8, aapt2, apksigner and `android.jar` behind one approval card, and the android-build skill covers a Gradle-less route for plain Java apps (template and build script included) alongside the existing Gradle route, with release signing guidance. Real APKs can be built, signed and installed without a PC.
- **Tips while tasks run**: an occasional tip card teaches a feature or setting mid-run, skips everything already set up, and can be turned off in Settings → Agent.
- **Learn section**: Settings opens with step-by-step guides for what the app can do, searchable and savable, offline, with example prompts you can copy and deep links that open the exact screen each lesson talks about.

### Changed

- **Harness Glass surfaces**: sheets and dialogs in the GitHub flow, the sidebar and the composer pick up the frosted and liquid glass rendering, and the glass scene is rasterized in its own layer so animations like the drawer no longer re-record everything behind it.
- **Expressive controls**: every button, icon button, switch, checkbox and radio button shares one press motion with a shape morph, applied per control so sizes, colors and touch targets are untouched.
- **Plain numbered steps**: numbered lists in replies render as ordinary steps again; the interactive checklist version only appears when a reply is asked to track tasks.
- **GitHub import**: the import dialog lists your repositories with a confirmation step before cloning, signed-out users land on URL import first, and a finished import opens the new workspace's Files screen.
- **Settings search**: results for features a device cannot use stay findable but are labelled ("Needs Android 13 or later", "No vibration motor on this device") instead of disappearing, and the Live Updates control explains itself on older Android instead of leaving an empty spot.
- **Refresh rate**: the app asks for the screen's fastest mode at its current resolution on every resume, so animations are not pinned to 60 Hz by vendor policy.
- **Release builds**: release APKs are minified and resource-shrunk again, reversing the 3.1 switch; keep rules cover the pairing TLS bridge, JavaScript interfaces, WorkManager, throwable and environment names, SSH and protobuf, so the download shrinks without changing behavior.
- **Languages**: the Learn, rumble, tips, settings-change, side-question, steering, pull-request, visual-guide, GitHub import, plugin and configuration screens are translated in all ten app languages.

### Fixed

- **Update checks**: the updater compares versions across every published release and picks the highest one with an APK this device can run, instead of trusting the list order; release titles with dates no longer parse as version numbers; automatic checks retry transient failures quietly and re-run whenever the app returns to the foreground; and the check no longer blocks a network thread.

## 3.1 (2026-10-30)

### Added

- **9Router**: install, run and manage the 9Router local model router on this phone from Settings → 9Router. Installation places the npm package inside the AndroidHarness Linux toolchain with validated release versions, and the page can start, stop and restart the server, check the npm registry for newer releases, update in place (falling back to the previous installation if activation fails), uninstall everything including its files, saved accounts, keys and provider connections, and auto-start when the app opens. Add 9Router to providers starts the router if needed, creates or reuses its AndroidHarness API key through the router's own control protocol, and saves an OpenAI-compatible provider at `127.0.0.1:20128/v1`, so its models appear in the Chat and Code pickers; removing the connection leaves the router installed. While the service runs it holds a background wake lease without advertising an active task.
- **Custom color palettes**: build an app theme from your own colors. Up to 24 saved palettes, each with a main, accent and highlight color chosen by hex value or by hue, saturation and brightness, and Material generates readable light and dark shades from them. Palettes can be saved, applied, edited and deleted, the active palette is previewed before saving, deleting the active palette returns the app to its selected preset, and settings backups validate saved palettes.
- **Harness Canvas**: a personal image backdrop for Chat and Code. Add a photo or artwork up to 32 MB, show it in Chat, Code or both, dim it so text stays readable, and optionally match the app's color palette to the image. Colors can follow the wallpaper, a preset palette, a custom palette or the canvas, and each choice releases the others; removing the canvas restores the previous theme. Canvas settings are validated in backups.
- **Local model performance tests**: Find best settings on an installed model runs CPU, GPU or CPU plus GPU passes over generation and prompt thread counts, using the same three prompts, the model's template, a fresh context, greedy decoding and a 32-token output limit for every configuration. A warm-up run is shown but excluded from scores, speeds are averaged across the three prompts, answers that differ from the reference run are flagged, and the recommended backend and thread counts can be applied to the model. The GPU pass requires the experimental Vulkan support, and the test unloads the model when it ends.
- **Device overview**: the Local models page gains a Performance tab with live device telemetry, refreshed every two seconds: memory with available RAM history, unused RAM and the app process share, internal storage, each CPU core's online state, current and maximum clock, and load, system and app CPU usage, the GPU renderer, vendor and driver identity, the optional GPU engine's install state, accelerator and NPU drivers reported by Android's discovery API, thermal status, battery level and temperature, and device specifications including board, CPU architectures, Android version and uptime. A small native library reads accelerator drivers without creating models or running inference.
- **Tool activity reader**: any tool result in the chat activity log opens full screen with Input and Output tabs, pretty-printed call arguments, paged output and a copy action. Longer tool outputs stay inside the inline panel with a More below hint and a Latest jump, nested disclosures keep their own scroll depth, and a live log keeps following new output until you scroll away.
- **Chat multi-select**: the chat menu offers Select, and the sidebar then supports selecting several chats at once for batch archive or restore with an undo snackbar, a combined details dialog showing each chat's workspace and model, and batch delete that stops running tasks first, deletes all selected histories in a single database transaction and reclaims images no remaining chat uses. Selection clears when the drawer closes or you navigate, and a destructive action cannot run while another one is in progress.
- **Thinking behavior for local models**: each local model can be set to request reasoning from the model template, to answer directly without thinking, or to follow the Chat thinking switch. Leading `<think>` content that a model's template leaves inside the text is still detected and shown in the thinking block.

### Changed

- **Local model caching**: a model stays loaded in memory between turns, and continuing a conversation reuses the prompt prefix (KV cache) that the previous turn built, with the reply reporting how many prompt tokens were reused. Deleting a model, stop and unload, performance tests and system memory pressure release the cache, vision requests bypass it, and a changed model file invalidates it.
- **Local models page**: load and stop now show real states (In use, Loaded in memory, Stopping and unloading), Stop becomes Stop and unload for a cached model, and prompt processing has its own thread setting next to generation threads. Arm64 builds use KleidiAI CPU kernels where the device and operation support them.
- **Reply tables**: Markdown and native tables gain an expand action that opens a full-screen view reusing the existing filter, sort, row selection and copy tools, and both table styles drop their card border and shaded rows for a flatter look with dividers. The presentation guidance now lets tables compare qualitative attributes when aligned rows and short cells genuinely help, instead of reserving tables for measured data.
- **Live Updates**: only active task work (a run, an approval or terminal work) can promote the persistent notification to a Live Update. Background services such as 9Router keep their wake lease without the promotion, the idle status never promotes, and the test now verifies what the app requests instead of what the OEM granted.
- **Wireless pairing on older Android**: below Android 14 the pairing TLS session now uses the bundled Conscrypt provider for TLS 1.3 and exports keying material through it, instead of reflecting into the platform's hidden Conscrypt class and lifting hidden-API restrictions at runtime.
- **Release builds**: release APKs are no longer minified and resource-shrunk; the ProGuard keep rules from 3.0 were removed with the switch, so installs are larger again and stack traces keep their original names.
- **Languages**: the 9Router, palette, canvas, performance, tool reader and chat selection screens are translated in all ten app languages, including Arabic, Persian and Indonesian plural forms.

### Fixed

- **Rich replies from non-conforming models**: components written as `::card`, `::callout`, `::profile` or `::preview` line directives are recovered into proper harness-ui fences, and bare JSON components whose `type` field is not first are recognized again. Ordinary JSON in prose stays literal text, fenced code is left alone, and an incomplete component while streaming waits instead of flashing broken output. The reply prompt now also tells models to use the fence and avoid colon directives.

## 3.0 (2026-10-28)

### Added

- **Chat agents**: create personal assistants with their own name, description, instructions, personality and tone, model and thinking level, knowledge files, tools and memory mode. Knowledge accepts text, Markdown and readable PDFs up to 8 files and 96,000 characters, and relevant passages are searched locally and supplied to the chosen model. The agent picker sits in the composer, changes apply to new conversations while existing chats keep their saved setup, and deleting an agent keeps its conversations, knowledge snapshots and saved memories. Answers can use a sourced profile card for a person, place or organization, with up to 8 sourced facts and 6 source links.
- **Wireless ADB**: Settings → Terminal & device → Wireless ADB pairs with Android's own wireless debugging and runs shell commands as UID 2000 without Shizuku or root. The six-digit code is entered from a notification, with manual port and code fields as a fallback, and the stored key can be reused or forgotten. A connected session is used automatically by terminal commands, Linux environment setup, package work and background jobs, while Shizuku stays available when it is connected. Requires Android 11 or newer, Wi-Fi and notification permission; pairing survives a reboot, but Wireless debugging must be turned on again.
- **Dhizuku Device Owner access**: the `device_owner_shell` tool runs commands through an explicitly authorized Dhizuku Device Owner service as the owner app, with Android app limits and no fallback to ADB, root or Shizuku. Settings → Root access gains a Dhizuku card with its real connection state, and Shevery is recognized as a compatible privileged shell provider.
- **Chat pinning and archiving**: pin chats to the top of the list, archive them out of recents, and unarchive to restore one, from the chat menu or the sidebar. Chats are now peers in navigation, so switching conversations replaces the open one instead of stacking them and Back returns to the screen you came from.
- **Chat context**: Chat has its own ceiling of 250,000 tokens, independent of the Code context setting, and compacts automatically as the window fills. Compaction keeps the latest user turn, the summary and pinned instructions, while the full history stays visible in the app.
- **Image inspection in Chat**: `view_chat_image` lists and reads images from the conversation, including attachments sent earlier and page screenshots captured by browser reads, and `zoom_chat_image` returns a magnified region for detail. Inspected images keep a stable identity, appear inline in the final answer, and a failed inspection is reported as a failure instead of returning nothing.
- **Background and notification controls**: Settings → Background & notifications adds a Run in background switch that stops agents when you leave the app, a notifications switch, and a shortcut to Android's notification settings. Each row states the current state and what it changes.
- **Local GPU support**: local GGUF models can run on Vulkan on 64-bit devices, behind the experimental switch, with Auto, CPU and GPU choices. Auto prefers the GPU and retries on CPU when the backend is unavailable or cannot start a reply before output begins. The Vulkan runtime ships outside the base APK: GitHub builds download the package that matches the installed app and device from a pinned release and verify its size and SHA-256 before loading it, while Play builds deliver the same library as an on-demand feature module. The GPU support card shows checking, download progress, install, retry and cancel, links to the runtime repository, and CPU inference works without it.
- **A2Agent**: a new preconfigured OpenAI-compatible provider, pinned above the other brands with a New badge. Its API key and model setup work like any other provider.
- **Sinhala**: සිංහල joins App language, the Android per-app language list and the locale configuration, with the interface translated across chat, settings, rich replies and local models.

### Changed

- **Settings home**: the home screen is grouped by purpose (Your assistant, Integrations, Personalize, Connections, Workspace & device, App & data, Coding) with grouped rows, and search still finds every entry with its deep link. Pages keep a back history, so Back returns to the previous settings page instead of leaving Settings.
- **Local models page**: the page splits into My models and Explore tabs, with the experimental switch, GPU support and model actions grouped above the tabs.
- **Chat header**: the model picker is narrower in phone Chat conversations so the title and controls fit, and the agent picker now lives in the composer.
- **Provider keys**: the key editor is shared between provider management and connection controls, with show and hide per key, the saved key count, and a clear message when saved keys cannot be loaded.
- **Linux setup prompt**: the setup card appears when a command actually fails with a missing tool, instead of offering the full environment before anything is needed.
- **Rich tables**: table options, row selection and sorting now live in a real toolbar.
- **Release builds**: release APKs are minified and resource-shrunk, with keep rules for native pairing, JavaScript bridges, WorkManager and SSH, so the download is smaller without changing behavior.

### Fixed

- **Web research**: a rate-limited search backend is paused instead of being retried immediately, a page that blocks the browser reader can fall back to a configured extraction service, and each answer records a receipt with the sources, pages and backend it used.
- **Chat continuation**: the internal prompt that resumes an interrupted answer stays out of the transcript instead of appearing as a user message.

## 2.9 (2026-10-26)

### Added

- **Provider connection controls**: each API provider can hold up to 20 named API keys with individual switches. Rotate API keys sends requests through the enabled keys in turn, and a key that is rejected, rate limited or out of quota can be replaced by another key only before any answer text or tool output exists, so a failed request is never replayed. Providers also gain a proxy setting (Use global setting, Direct connection or Custom proxy) with HTTP or SOCKS5 and optional credentials for HTTP proxies, plus one Global provider proxy used by replies and model lists. Key pools and proxy credentials stay in encrypted storage, changes apply to new requests, and settings backups move to v4 with key pools and proxy settings, leaving credentials out when keys are excluded.
- **FTP and SFTP sync**: Settings → Connections → FTP / SFTP sync selects a local workspace and a saved remote connection, previews uploads and downloads before applying them, and reports new files, replacements and unchanged entries. Replacing existing files needs approval, nothing is deleted, and Git history and generated folders are skipped. A sync is limited to 512 MB per file and 1 GB or 10,000 entries in total. SFTP checks the saved SSH host key and accepts a password or private key, while FTP states that it sends passwords and files unencrypted.
- **FTP sharing**: Settings → Connections → FTP server shares one workspace with an FTP client, read only or read and write, with an option to accept connections from the local network. Sharing continues in the background with a Stop action in its notification, refuses symbolic links and special files, and limits uploads to 512 MB.
- **Update install method**: the update dialog offers Automatic, Root, Shizuku and Android installer. Automatic uses an enabled and already authorized device access route and falls back to the Android installer, a dispatched install is never retried automatically, and the dialog names the route that installed the update.
- **Memory sections and global Chat instructions**: saved memories now belong to Chat, Code or Shared. Each section has its own switch, Chat and Code can optionally look up the other section, existing memories become Shared, and Consolidate memories builds a reviewed digest of related entries whose originals stay restorable. In-chat receipts say whether a memory was saved or was already saved, and a failed save is reported. Settings → Chat Option holds global Chat instructions up to 4,000 characters that every Chat uses, and one Chat can keep or replace them for itself; instructions saved before this release stay as overrides.
- **Workspace file links**: file names in Code answers and inline code open in the editor, with an optional `:line` or `#L` suffix to land on a line. Markdown links that point at workspace files open the editor instead of the browser, while commands, URLs and loopback preview addresses keep their current behavior.
- **Stop for running commands**: while a terminal command runs, the run button becomes Stop and Ctrl+C interrupts from a hardware keyboard. Cancelling a task now ends its local process group and commands running through Shizuku or root instead of letting them continue in the background.
- **Languages**: Persian (فارسی), Brazilian Portuguese (Português (Brasil)), German (Deutsch), Spanish (Español) and Japanese (日本語) join App language, the Android per-app language list and the locale configuration, each listed under its own name. Persian is laid out right to left while code, diffs and terminals stay left to right.

### Changed

- **Chat reading position**: a streamed reply keeps its rendered item identity when it is saved, so the transcript no longer shifts as the live answer becomes history, and a fling to the bottom still resumes following. Expanded disclosures contain their own drags and flings in Chat and Code, including in standalone previews. Tables with one or two columns fit the phone width, wider tables add a position bar and swipe hint, and blank lines inside a streaming code fence no longer split the live block.
- **Drawer gestures**: side swipes no longer open the drawer on Settings, providers, skills, stats, setup, previews or memory screens, and a drawer that was open is closed when one of them opens. Workspace screens keep the edge swipe.
- **Composer and voice**: the Groq recorder is a compact capsule in the typing area with a live waveform, a timer, slide left to cancel and slide up to lock, and a Transcribing state that can be cancelled. The personalization menu opens centered under its pill with a chevron that turns while open. Menus animate from the pressed location, sidebar rows and icon buttons share press feedback, and switching between Chat and Code uses a short directional fade and slide.
- **Chat presentation guidance**: rich responses are chosen for the shape of the answer instead of a target percentage, tables are kept for genuine tabular data or an explicit request, and charts for quantitative trends. Component examples are labeled as structure, not content to copy.

### Fixed

- **File filters on Android 8 through 13**: `search_files` and `grep` name and include filters called `Path.of`, which only exists from API 34, so they failed on those releases; they now use `Paths.get`.
- **Groq transcription**: a response without a usable text value is reported instead of producing a blank transcript, error text redacts the API key, and cancelling transcription cancels the HTTP request and removes the recording file. A recorder that fails to start is cleaned up along with its audio file.
- **Provider save errors**: a provider that cannot be saved now points at Connection controls for its API keys.

## 2.8 (2026-10-24)

### Added

- **Chat instructions and personalization**: each Chat conversation has its own instructions, up to 4,000 characters, appended to the Chat system prompt for tone, format and scope, plus a Personalization switch on the empty state and in Chat settings. Off keeps the stored text but skips both the instructions and saved memory for that conversation. The chat menu also opens Sources in this chat, a deduplicated list of every web source used in the conversation that marks pages read versus results found, and each answer shows the model that produced it without the catalog provider prefix.
- **Research quality**: `web_search` accepts freshness (day, week, month, year), language and country, reports publication dates when a backend supplies one, canonicalizes and deduplicates result URLs, caches searches and pages for the session, retries transient rate limits, runs keyless engines concurrently so one slow engine cannot hold the query, and reports which engine answered. Chat adds an Off research depth that removes `web_search` for the turn. `web_fetch` reduces HTML to the main article, pages long text with `offset` and `max_chars`, finds passages with `find`, extracts text from PDFs, sends a browser identity, warns when a page needs JavaScript, and counts pages read against the turn's budget.
- **Shared reply components**: table, tabs, gallery, multi-series chart, calculator, evidence, diff and writing blocks work in both Chat and Code, alongside the existing cards, callouts, metrics, charts, choices and previews. Tables filter, sort, select and copy, tabs keep their selection, galleries swipe and open a zoomable viewer, calculators run bounded arithmetic with no scripts, evidence links sources and recorded checks, diffs show before and after, and writing blocks copy or share exact text. Selections can attach as context to the next message but never send, edit files, or run tools.
- **Math, diagrams and follow-ups**: LaTeX renders inline and as display math, Mermaid diagrams render from fenced blocks, and substantive answers can end with two or three tappable follow-up suggestions. Bundled KaTeX and Mermaid assets work offline, and the follow-up block is removed from stored and copied text.
- **Images in answers**: `web_fetch images=true` returns direct image URLs from the page, filtering icons, logos and tiny images, and answers can place them inline. Standalone links render preview cards, YouTube links get an inline player, hotlink-blocked images load through a browser identity, broken images are hidden, and tapping an image opens a full-screen viewer with pinch zoom, save and share. A new Image frequency setting offers No image, Less often, Default, More often and Always in Settings, with a per-chat override that can inherit the app setting; the model still chooses the number, and answers avoid a wall of near-identical pictures.
- **Past chats and sharing**: `search_chats`, `recent_chats` and `read_chat_snippet` let Chat look up earlier conversations by keyword or recency and read a small window around a hit, never a whole chat. Shares from other apps open a destination sheet where the text, links and images are reviewed first, then sent to a new chat or an existing one with an optional question and shortcut chips; share targets accept `SEND_MULTIPLE` and any MIME type.
- **Languages**: Russian, Arabic and Indonesian join English and Simplified Chinese in App language, the Android per-app language list and the locale config. Arabic keeps code, diffs, terminals and other technical content left to right while the interface follows right to left.
- **Environment guide and build tools**: every run includes a live description of the device environment (access mode, root and Shizuku state, default command route and workspace rules) built from the tools actually attached to that request, plus the bundled `androidharness-environment` skill with references for workspaces, device access, packages, builds and diagnostics. On-device Java, D8, R8 and apksigner launchers are repaired after install, `JAVA_HOME`, `PATH` and `LD_LIBRARY_PATH` are set for both execution tiers, broken build packages are detected and reinstalled, and `env_status` reports the real Java and D8 state instead of claiming readiness.
- **Process termination guard**: descendant process termination now refuses PID 0, PID 1 and any non-positive value explicitly, as an extra safety check before sending SIGKILL.

### Changed

- **Chat landing and activity**: the personalization pill stays pinned to the top of the empty chat while the mark, headline and suggestions sit in the vertical center. The live thinking block shows one clock that includes tool time, token counts never drop when a provider reports a smaller number, a blank working bubble no longer appears under the live disclosure, and the thinking block is collapsed by default in Code as well. Activity glyphs use Lucide Animated motion, page reading shows animated eyes, the thinking title names the tool currently running, and research counters animate below the thinking block.
- **Chat following**: a sent question snaps to the top of the conversation, expanded detail panels own their own scroll and drag gestures, and the transcript follows new output only while the reader has not scrolled away, so a finished reply or a late update cannot pull the view back unexpectedly.
- **Sources and citations**: citation links render as a small link icon with the label kept for copying and selection, the full source list lives in Sources in this chat, and the From Chat link now sits on the handoff greeting instead of the message body.
- **Provider setup**: adding a provider works with the API key alone, because the first catalog model is preselected, and a newly added provider becomes the active one on its own default model instead of inheriting a stale model override from the previous provider.
- **Chat assistant prompt**: the Chat prompt now identifies AndroidHarness, explains how answers render on a phone (short paragraphs, Markdown tables, tagged code fences, LaTeX and the reply language), and points at the shared rich components.

### Fixed

- **Markdown links inside formatting**: links nested in bold, italic, strikethrough and headings resolve as links again instead of leaking as literal `[label](url)` text, while code spans stay literal.
- **Empty chat crash**: the centered empty state fills the list viewport instead of requesting an unbounded height, which could crash composition before the first message.
- **Chat empty state alignment**: the content centers correctly in both Chat and Code, with extra clearance only where the personalization pill overlaps.

## 2.7 (2026-10-23)

### Added

- **Chat**: a second conversation section beside Code, reached from the drawer or desktop sidebar. Chat keeps its own recent conversations, drafts, model choice and start preference; it is conversation-only, with no project files, shell, terminal or device tools. Its tools are automatic and read-only: web search and page reading, `github_api`/`github_search`/`github_file`, `read_chat_text` for user-supplied text and attachments, and `remember`/`recall` against universal memory. Answers stream with Markdown and native rich replies, including offline interactive previews, and the header shows the model name without its catalog provider prefix.
- **Chat research and sources**: the header's research depth control sets a per-turn source budget — Instant (5), Deep (20), Auto, Deep research (50) or Max research (100) — and `web_search` enforces it across the whole turn. Chat uses the selected keyless, Brave or Tavily search API, reads pages before relying on them, cites claims inline, and keeps a per-turn Sources list that marks pages read versus results found. Read-only GitHub tools cover repositories, issues, pull requests, commits, releases, users and code with the connected login or a personal access token, and never create, edit, comment, merge or push.
- **Chat answers and attachments**: attach images, text files and text-based PDFs up to 64 MB; each file shows reading, ready, too large, unsupported or failed, images are marked when the model's support is unconfirmed, and sending waits until every attachment is ready. Find in chat jumps to matches, Quote puts the selected answer text in the composer instead of the clipboard, and Regenerate retries a reply. Answers keep previous versions with a version switcher that works from any turn without replacing later history, interrupted replies offer Continue reply, and draft markers in the chat list show unsent drafts.
- **Chat activity and scrolling**: Chat keeps thoughts, commentary, tool calls and sources in one chronological, expandable timeline with live thinking duration and token metrics; thinking is collapsed by default and can stay that way in Settings → Chat. Streamed prose remains the answer even when a turn uses tools. New turns anchor on the latest question and follow output while it streams; when a reply finishes the view returns to the question unless the reader scrolled away or chose the bottom, and that choice survives late updates. Search results stay in place, the down button always points at the true bottom, and expanded details follow only while the reader has not dragged away.
- **Continue in Code from Chat**: when a conversation needs project editing or execution, the model can offer a Context card with a short action title and reason. The card opens a workspace picker, creates a Code conversation with the Chat plan carried over, and runs nothing until you send. Only a successful offer in the current turn supplies card copy; failed, old or malformed offers fall back to the default text, and the Chat itself is unchanged.
- **Universal memory**: Chat and Code share one memory store with global or project scope and profile, preference, project, decision and fact categories. `remember` and `recall` are available in both sections, a bounded block of relevant memories is added to each request, and extra entries stay searchable. Settings → Memory lists, searches, pins, adds, edits and deletes entries, with a delete-all confirmation. In-chat receipts show which saved facts were supplied to the model and let you edit or forget them; memories travel in chat and encrypted settings backups.
- **Durable file recovery**: `file_history` lists private gzip copies saved before and after agent writes, undo and restore, and `file_restore` recovers a missing file or replaces an existing one only after inspecting its current SHA-256. Copies are capped at 32 MB per file, survive restarts, undo and chat truncation, and are recorded even if the task pauses because write and undo paths are non-cancellable. A run that starts with missing workspace files warns explicitly and points to the saved versions, and a restore is never replayed as a completed action.
- **Explicit Shizuku access**: `shell` gains `access="shizuku"`, which pins the command to Shizuku and never falls back to the app UID or direct root. App-workspace development commands use the app UID by default, Android system commands can request Shizuku explicitly, and SSH workspaces refuse Shizuku and root because those transports are Android-local. Shizuku running as UID 0 no longer needs a separate root grant, execution receipts name the transport as Shizuku, and the verified UID stays in diagnostics.
- **Start screen and section settings**: Settings → Chat adds Start screen (Code, Chat or Last selected), a separate Start chat choice (New chat or Last chat), and Keep thinking collapsed. Code keeps its own New Code chat / Last Code chat choice, so changing one section never changes the other. The default start screen is Last selected, Chat opens a new conversation, Code resumes its last, and settings backups carry the start choices including the legacy Code toggle. Settings is split into Chat and Coding groups, and the new strings are translated into Simplified Chinese.

### Changed

- **Package install cards**: package installs now report resolving, downloading, verifying, unpacking and configuring phases, the dependency closure and completed count, and byte-level totals in a dedicated card. Only a committed result can say Installed; failures keep the error and never show a stale download as progress.
- **ChatGPT account auto-switching was removed**: the Auto-switch setting, per-account participation and fallback model, and usage-limit tracking are gone. Picking another account refreshes its model list and keeps the current model when that account offers it; fallback chains still handle model failures.

### Fixed

- **DeepSeek vision classification**: DeepSeek V4 Pro is treated as text-only, while DeepSeek V4 Flash and V4.1 Flash remain vision-capable. The broad `deepseek-v4` match was replaced with the Flash model IDs, so Pro no longer appears image-capable while the Flash variants do.

## 2.6 (2026-10-21)

### Added

- **App language and Simplified Chinese**: the new App language page in Settings offers System default, English and 简体中文, applies the choice immediately, persists it on Android 12 and earlier, and stays in sync with Android's own per-app language picker on Android 13 and up. User-facing copy across the app now lives in string resources, including chat, Files, the editor, projects, automations, setup, Usage, updates and the full Settings tree, so a translated page keeps its search entries, deep-link anchors and sidebar labels together. Simplified Chinese covers the app's user interface; model and provider names, tool descriptions, agent prompts and shell output stay in English. Play language splits are disabled so the bundled translation is always available, and the page links to the POEditor project for community translations, which CONTRIBUTING.md now documents.
- **Live activities**: on Android 16 and later the running agent's ongoing notification can be promoted to a Live Update, which Pixel and near-stock devices show on the lock screen and in the status bar, Samsung shows as a Now Bar card, and ColorOS and OxygenOS show as Live alerts. The card carries a short state (Thinking, Writing, Coding, Working, Retry, Approve, Reply, Install or Needed) instead of a cut-off sentence, shows indeterminate progress while the run continues, and offers Approve and Deny, or Skip and Install, when the agent is waiting for you. It appears only while a run or terminal holds the keepalive, so an idle background service is never promoted. Settings → Agent behavior → Live activities adds a switch, the detected device and skin, its real permission state, the steps for One UI, Pixel and stock Android, HyperOS, ColorOS and OxygenOS, OriginOS, MagicOS and other skins, and a button that opens the matching system settings page. Troubleshooting adds a shortcut to the same setting.
- **Text to speech for replies**: every assistant message gets a read-aloud control that shows a spinner while a Groq clip is generated and stops playback when tapped again. Settings → Voice & speech → Voice output offers the on-device engine, which works offline and uses voices installed for the app language, or Groq TTS with the same key as Whisper. Groq models are read from the account's model list so newer ones appear without an app update, voices come from each model family's documented list with a free-form voice id when a model has none, speed runs from 0.75× to 1.5×, and a test button speaks a sample with the saved settings. Speak every reply reads each assistant text block as it lands, including commentary between tool calls, queues blocks so they never interrupt each other, and does not read history when turned on in an open chat. Markdown is cleaned before anything is spoken: code fences are skipped, tables become readable rows, links, URLs, footnotes, HTML and list markers are removed, and a block with nothing pronounceable is passed over. Long replies are split to each engine's input limit, two Groq clips are generated ahead so sentences do not leave gaps, and generated audio is cached by model, voice, speed and text so replaying a reply costs nothing. Orpheus streams WAV with placeholder sizes, so the app stamps the real sizes before playback, and a model that needs an organization admin to accept its terms opens a dialog with the Groq Console link instead of failing silently.
- **Device authentication for full access**: turning on Full access now asks for the fingerprint, face or device PIN first, and cancelling keeps the previous permission mode. The check covers the Settings permission picker, the chat header menu, the workspace access menu, and restoring a settings backup that carries Full access. When the device cannot show the prompt, the change is refused instead of allowed.

### Changed

- **Root status matches the real connection**: the Root enabled chip now appears only after the direct root service is verified, so a root Shizuku running as UID 0 no longer makes a denied KernelSU, APatch or Magisk grant look enabled. When the direct grant is denied but privileged commands will still run through Shizuku, the Root access card says that instead of leaving the stale denied reason, and Shizuku keeps its own status line.

## 2.5 (2026-10-20)

### Added

- **Project deletion**: delete a project from the sidebar, the Projects list or its details dialog. A review lists the saved chats and automations that will be removed, refuses while a task is running or while chats in another project still use the workspace, and explains that the folder and its files stay on disk; the app workspace cannot be deleted. Deleting removes the project's chats, history, undo and pins, stops its automations, and completes cleanup even if you leave the screen.
- **AI code changes in Usage**: Usage now has an AI code changes card with lines added, lines removed and net change, backed by a new activity table that stores only hashed file keys. Source and configuration edits from agent file tools and eligible local foreground shell commands are counted; documentation, generated files, dependencies, binaries and manual editor saves are excluded. Totals survive chat deletion and follow the selected period, and a definitions section explains what is included.
- **Restore chats into workspaces**: chat backups now record workspace names, and import first previews which workspaces are missing. Choose an existing project or add a folder for each one, and the restored chats, their primary workspace and read-only grants are remapped to the new ids. Older backups still import, and an inconsistent or unavailable mapping is refused before anything is written.
- **Recoverable editor drafts**: unsaved editor text is written to app-private storage with atomic replacement, then restored after rotation, closing the editor or process death, including the original encoding, BOM and line endings. A status line says when a draft is saving, saved, recovered or failed, and recovery is scoped to the chat, project, workspace and path. Saving or discarding clears it, and a recovered draft still goes through the normal disk-conflict check. Word wrap, match case and regex search now persist.
- **Tabbed editor and project tools**: open several files as tabs and switch from the tab row or Open files; the active tab, order, cursor and scroll position survive restarts. Quick Open (Ctrl+P), the command palette (Ctrl+Shift+P) and project search/replace (Ctrl+Shift+F) are available from the editor, Files and the chat menu. Search skips ignored trees and escaping symlinks and is bounded by file, byte and result limits; replace shows per-file diffs, preflights read-only workspaces, unsaved drafts and disk hashes, then rechecks each file before writing. The chat header shows a browser shortcut when a message contains a web or localhost link or the workspace has an HTML file, and that shortcut opens the preview at its sources.
- **Offline code intelligence**: the editor bundles a local language worker for completion, outline, go to definition, find references, rename and formatting. JS/TS get the full set; HTML, CSS, SCSS, LESS and JSON get completion, outline and formatting, with CSS-family definitions and references inside a file; Markdown gets an outline and formatting; YAML gets formatting. Rename opens reviewed drafts across files instead of writing directly, formatting stays undoable, and the worker runs in a sandboxed WebView with no network, file access or external navigation. Shortcuts are F12 for definition, Shift+F12 for references, F2 for rename and Ctrl+Shift+I for format.
- **Root access**: Settings → Terminal & device adds opt-in root through a verified local service. Modes are Auto, Normal, Shizuku, Root and Both, with an auto-fallback switch; Both keeps both connections live, runs ordinary commands through Shizuku and sends `access="root"` calls to the direct root service in the same task. Root is limited by default to an app session, one chat or one action and to projects you allow; a temporary root mode switch, behind a confirmation dialog, removes those limits for every project while the connection stays active. The root browser lists protected folders, opens text files up to 64 KB and saves with a SHA-256 check that refuses stale edits. Tool cards show an execution receipt (Normal, Shizuku · ADB, Shizuku · Root or Root), detached root jobs can be stopped or resumed, a dispatched command is never replayed, tracked kills verify the process start time and log, and the Grant button becomes Root enabled only after the connection is verified. Root is granted once after each app start.
- **Root manager detection**: identifies KernelSU, KernelSU Next, SukiSU Ultra, APatch and Magisk from the manager package, root-service markers and the kernel version, so markers hidden by SUSFS are still covered by the package check. The Root access card and diagnostics show the detected manager.
- **Per-call browser cards**: browser tool calls render as their own cards in the chat, each with the page, site, action and status, instead of one floating bubble. Watch opens the preview on that call's page even if a later call navigated elsewhere, cards survive saved history, and the preview can stop a running agent. Subagent pages use the same cards.
- **Automation delete**: delete an automation from its card after a confirmation that stops active and scheduled runs. Chat history and previous run results stay available.
- **Live task progress**: the agent's current task list is sent with every round, and after three tool rounds without an update it is reminded to mark completed milestones and the current in-progress task. Tool batches keep todo_write at the position the model requested, so the progress card updates before slow subagent work instead of after the batch.

### Changed

- **Thinking blocks** show only the Thinking label and the provider's summary. The estimated token count, duration and reasoning setting were removed, blank saved blocks are hidden, and long saved reasoning renders lazily.
- **Large chat histories** keep tool groups and turn activity responsive: more than twelve rows use a bounded lazy list, long reasoning renders line by line, tool arguments are parsed off the UI thread, and chevron animations no longer recompose the whole card.
- **The model picker opens on the chat's active provider** instead of browsing all providers first; a search still crosses every provider.
- **Usage** has a redrawn summary with model activity, estimated cost and cache hit rate, a separate AI code changes card, the token breakdown and a collapsible definitions section.
- **Files keeps the current folder** across chat and session refreshes instead of resetting to the workspace root; switching to a different workspace still starts at its root.

### Fixed

- **DeepSeek cost estimates use the request time**: peak rates apply 01:00-04:00 and 06:00-10:00 UTC on weekdays, and all other times, including weekends, are billed at half. Usage, the chat cost dialog and the context panel split the stored timestamps, so existing sessions are recalculated. V4.1 flash lists $0.30/M input, $1.20/M output and $0.006/M cache reads at peak.
- **Editor saves no longer overwrite agent edits**: saving waits while an agent is working in the workspace, and a file changed after it was opened shows Compare, Reload and Keep my edits instead of silently replacing the newer text.
- **Long tool groups and turn summaries keep their last rows reachable** instead of composing the entire history and stretching far past the screen.

## 2.4 (2026-10-19)

### Added

- **Interactive concepts in chat**: ask for a mockup, chart, diagram, or small interactive tool and a tool-capable model can return a self-contained HTML/CSS/JS concept through `create_artifact`. Each concept has Preview and Code tabs, an expanded view, version history, Reload, Revise, and Save HTML; Revise attaches the selected revision to the composer without sending. Revisions are stored on saved messages, survive restarts, forks and chat backups (format 2, older backups still import), and the preview renderer has no network, file access, storage, native bridge, popup, download, or permission access, so generated code cannot call tools.
- **Rich replies**: assistant answers can mix prose with native cards, callouts, metrics, charts, comparisons, checklists, expandable explanations, link cards, and self-contained interactive previews using a `harness-ui` block. Selection state persists, incomplete streamed blocks are never executed, invalid blocks show a text recovery action, and copy and search show readable content. Card actions and Discuss selection prepare a draft in the composer and never send a message or change files on their own.
- **Reviewable question cards**: the agent's question card now stages single-choice, multiple-choice, and written answers, shows exactly what will be sent, and waits for an explicit Send answer. Draft state is keyed to the tool call, so reopening the chat does not lose it.
- **Model capability picker**: the redesigned model sheet shows Thinking, Tools and Images badges with the source behind each claim, a provider sub-screen with search, model details, and Edit list. The picker now opens on All models and hides Favourites or Recent when they are empty.
- **One sidebar for every layout**: the phone drawer and desktop sidebar share the same project tree, with Chats and Projects views, search, pinning, archived chats, chat actions and undo, delete confirmation, and a Tools footer. The chosen view persists.
- **Model-native thinking controls**: thinking choices now come from each model instead of one global ladder. Levels are read from the provider's model list when it reports them, with models.dev and the shipped family tables as fallback. Custom levels can be added, renamed, reordered and removed per provider and model, and an optional CEL expression overrides the request fields. Local models keep their native on/off control, the header shows the resolved level and how it was chosen, and thinking blocks show the provider's reported reasoning tokens and active setting.
- **Package install progress**: Linux package installs report the package name, byte-level download progress, size, percentage, and completed package count. The install card stays in the chat while the install runs and shows the final error if it fails.
- **Progress bar style**: Settings → Appearance adds Flat and Squiggly progress bars with live previews. The choice applies immediately, persists, and travels in settings backups.
- **`read_tool_output`**: the agent can page through an earlier tool result by character offset, including text omitted from model context, instead of guessing or re-running the tool.
- **Background process exit records**: background jobs now report the real exit code and whether they exited or were killed, and the last 100 terminal records survive app restarts.

### Changed

- Chat keeps the app's Material You palette in light, dark, and AMOLED modes instead of replacing it with fixed gray surfaces.
- The composer keeps a steady height while typing, aligns text to the bottom for multi-line input, and scrolls long drafts inside the field instead of moving the surrounding controls.
- `search_files` and `grep` sort results and accept offset and limit paging, and `search_files` can stay non-recursive.
- File edits write through a temporary file and atomic replace, preserve POSIX permissions, and refuse non-UTF-8 files instead of corrupting bytes through replacement characters.
- Fuzzy edits rebase indentation when a normalized match consumes the line, so a replacement stays inside its enclosing block.
- Tool call rows key on message, index, and ID, so providers that reuse tool call IDs cannot make rows collide or disappear; live-code drafts use their own key namespace.
- Expressive press shapes and motion in the composer, model picker, sidebar tabs, and tool cards. The composer's send button no longer scales its tap target.
- Opening the drawer no longer focuses chat search or opens the keyboard, while tapping search afterwards keeps focus.
- README now carries the feature guides directly after the standalone documents were removed.

### Fixed

- Provider reasoning metadata: model list effort values and defaults are parsed, validated, and used only for the endpoint that reported them, so one provider's vocabulary cannot leak into another.
- Bare and automatic models no longer invent thinking ladders, and models reported as non-reasoning hide the menu.
- Assistant reasoning continuation is stored with its provider, endpoint, model, format, and content digest, and is replayed only to the exact endpoint that produced it, so switching models cannot leak native reasoning.
- Browser navigation uses exact history offsets, no longer loads `about:blank` during a history change, reports JavaScript alerts, confirms, prompts and blocked popups in tool output, and cleans up headless WebViews.
- `pkg install` verifies every requested package is present before reporting success and distinguishes newly installed packages from ones that were already installed.
- Provider catalog refreshes are serialized and briefly cached, and a connection change is detected before the result is saved.
- File info and listing report missing, special, and permission-denied paths clearly.
- `git diff` establishes repository context first, so it cannot enter no-index mode.

## 2.3 (2026-10-16)

### Added

- **Workspaces per chat** (issue #38): a chat can use several workspaces at once, each with its own access. The drawer entry Workspaces for this chat, also in the desktop sidebar, opens the access editor: tick the projects it should see, set Can edit or Read only for each, and choose the primary where plain paths and new files land. It also works before the first message, so a draft chat can pick access without losing what you typed. Workspace tools take a workspace_id, the agent is told exactly what each project allows, read-only projects refuse every write, and while any selected project is read only, commands, Git and integrations are unavailable (file tools keep working, and Full access does not lift these checks). Saved paths carry their workspace id, so undo, Files changed and checkpoints keep each project's files separate, including in chats upgraded from a single workspace. A chat with saved changes keeps its primary and existing workspaces while you can still add workspaces or change access, changes wait while a task is running, selected workspaces may not overlap, and deleting a project that a chat still uses is refused. The selection travels in chat backups.
- **Storage** (issue #37): Settings → Storage measures what the app holds on the device: shared Linux tools, shared npm and pip download caches, workspaces with project files and build outputs, on-device models, chat history with recovery data, and runtime staging. Totals are logical file sizes, every root counts once, links are not followed, and SSH hosts, picker-only folders and the privileged runtime are reported as not measured instead of estimated. Review cleanup lists every candidate with its exact path and size, and deletion rechecks each file first: changed or linked files are skipped, caches download again when needed, and build outputs older than seven days are offered only for recognized Gradle trees that you select. Cleanup waits for running tasks, tracked background processes and an open terminal. Shared Linux packages gained ownership records: installs remember their files, dependencies and the workspace that requested them, releasing a requirement only edits that note, and a package can only be removed when nothing requires it and its files are unchanged, with files quarantined first so an interrupted removal rolls back.
- **Custom provider headers** (issue #41): API connections accept extra HTTP headers for gateways that need them, edited right in the provider sheet with per-row enable toggles, hidden values and a masked preview. Values stay in encrypted storage, appear in settings backups only when keys are included, and never enter provider metadata, chat exports or diagnostic strings. Headers the app manages itself (Authorization, content type and similar) cannot be overridden, and while any custom header is enabled that connection stops following redirects so a credential cannot be forwarded to another host. Every wire protocol and model discovery send them, and resume asks you to pick the model again if the connection's URL or type changed.
- **Manage models** (issue #36): decide which of a provider's models appear in the picker. Manage models lists the catalog with search, per-model checkboxes, Select results and Refresh models. Once you customize a list, newly discovered models stay hidden until you add them, Show all reveals everything including disabled providers, and a Hidden tag marks what it is exposing; custom models you add join the list immediately. Hiding a model never changes a chat that already uses it.
- **Reasoning indicators** (issue #36): a colored brain marks reasoning support reported by the provider or marked by you on a custom model, a muted brain marks an unconfirmed model-family hint, and no icon means support is unknown or reported as unsupported. Tapping an icon explains where the claim comes from. Gemini catalogs read the model's reported thinking field instead of guessing from names, and older cached Gemini entries keep their previous guess labelled as inferred.
- **Bulk provider actions** (issue #36): long-press a provider, or choose Select from its menu, to select several at once, then Enable, Disable or Delete them together. Multi-delete starts with a review of the exact list and what stays in place, Edit selection returns to the list with checks intact, and a failed deletion keeps the remaining providers selected for a retry. Built-in, connected-account and on-device providers can be disabled but not deleted, and deleting a provider never reroutes a chat that used it: the chat keeps its selection and asks you to choose again.

### Changed

- **The model picker is provider-first** (issue #36): the sheet shows the current provider in a Choose provider control that opens a searchable provider list with a Manage providers shortcut, with search, Favourites and Recent filters and Show all below it; Show all searches every catalog, hidden models and disabled providers included. Tapping a model stages it and a `Use <model>` button at the bottom confirms, so a scroll or a stray tap cannot switch models, and a disabled provider is enabled only after a confirmation. Manage models sits in the sheet header, and refresh, the thinking-only toggle and the reasoning legend share one row above the results.

### Fixed

- **Android 8 and 9 app shell and terminal** (issue #43): the app-tier shell launched bundled Bash through the system dynamic linker, which only behaves from Android 10 on; on Android 8 and 9 the linker printed its help banner and exited successfully without executing anything, so commands silently did nothing. Older versions now execute Bash directly, existing installs regenerate their shim file, and the terminal uses the same process builder as every other command.
- **Strict gateways and cache hints** (issue #42): the session cache key and its user field were sent to every remote endpoint, and gateways that reject unknown fields answered with a 400. These hints now go only to the endpoints that document them, api.openai.com and openrouter.ai; other gateways receive clean requests and still report cached-token usage.

## 2.2 (2026-10-14)

### Added

- **Fallbacks: ordered backup models across providers** (issue #33): configure up to four models that take over, in order, when the selected model fails. Quota exhaustion switches immediately, ordinary rate limits and temporary failures are retried first (0 to 5 retries), and a failure after any response output is never retried or switched, so a partial response is kept for review. Each destination uses its own credentials, the chat's selected model stays selected (the chat notes the switch), and a model that is unavailable, disabled or missing a key is skipped without a request. A default chain lives in Settings → Models & providers; each chat can use the default, keep a custom chain, or turn fallbacks off, which also disables the ChatGPT account switch for that chat. Subagents and automatic compaction follow the same chain, a request from a model outside it is refused with a review message rather than sent, Stay within selected provider keeps every request (subagents included) on the selected connection, and settings backups carry the chain with non-portable providers stripped.
- **Live code preview**: while the agent generates files, write_file, edit_file, multi_edit and apply_patch render as code rows instead of waiting for the result. Each row names the file, shows added and removed line counts, and expands into a diff preview: streaming lines compared against the file on disk, or marked as generated code when there is nothing to compare. Rows move through Generating draft, Draft ready, Writing, Saved, Not saved and Awaiting approval, report estimated tool tokens while generating plus the response's measured output tokens once the provider reports usage, and saved rows keep their real line counts after reopening because the counts are stored with the tool call. Streamed argument fragments are display-only and can never authorize or execute a tool, and the new Show live code switch in Settings → Chat behavior (on by default) turns the rows off, saved history included.
- **Provider management** (issue #34): disable a connection without deleting it, reorder connections with move up and move down, and search across every provider from the chat's model picker, where each hit names the provider it belongs to. A disabled provider keeps its credentials and leaves the model pickers until it is enabled again (a search still shows it, one tap away), the order and disabled state persist, ride in settings backups, and Settings and the in-chat provider sheet now share the same list.
- **Shizuku compatibility runner**: on devices where Shizuku's standard user service cannot bind (an OEM-specific failure that used to surface as a plain connection error), the app starts the same privileged shell runner through a compatibility path: the installed app is launched as the Shizuku shell user and talks over a private pipe protocol, arguments are never evaluated as shell text, and the runner's identity is verified before the first command. Commands, detached jobs and process control behave the same, Settings shows Compatibility ✓ while it is active, and the standard binding is retried first on the next refresh or reconnect. The card keeps the exact failure reason with Copy error details and Copy startup log (logcat filtered to this app's helper records; commands and environment values are never collected), and the agent is told the runner's state so it asks for a grant, start or refresh instead of assuming ADB-shell access.
- **Ask the agent about selected code**: selecting code in the editor shows an Ask agent bar with Explain, Fix, Refactor and Write tests. The chat opens with the selection, its file and line range as a draft prompt, marked when the buffer had unsaved changes; nothing is sent until it is reviewed and sent.
- **Model favourites and recents**: star models in the picker and switch between All, Favourites and Recent collections, with favourites sorted first. Recent holds the last ten picks per provider with Clear recent, both lists stay on the device (never in backups), and a favourite whose provider was removed stays visible but cannot be selected.
- **Troubleshooting page**: one Settings screen checks the default provider's model catalog, storage, the default workspace, Linux tools, Shizuku, SSH, connected MCP servers and notifications, and exports a diagnostic report built from a fixed whitelist of states. Credentials, endpoints, names, file paths, chats and raw logs are excluded from the export.
- **Project overview**: the project screen now shows the repository branch, the number of changed files or that the tree is clean, the latest named Build & Test check with its outcome even after that screen was closed, and rows that open Files, Terminal and Build & Test scoped to the project. A running dev server's address is recovered from its log tail into the preview sheet, a static index.html entry point is offered for preview, and Refresh re-reads the repository state.

### Changed

- **Chats open on recent history**: a chat now opens with its last 80 messages and pages older ones in complete turns through Load older messages, so long conversations open immediately. Search results outside the loaded range jump straight to their message, Undo file changes loads its checkpoint labels on demand (including turns that are not on screen), and model requests still use the complete history.
- **Storage access is the required setup step**: setup now requires device storage access with a clear reason, while notifications, Shizuku and the Linux toolchain stay optional and no longer block finishing.
- **Plan approval shows the full plan**: the card's fixed first-2000-characters block is replaced by a short preview and a Show full plan toggle that opens the complete plan as selectable markdown.
- **Thinking blocks show a token estimate**: live and saved thinking blocks report "≈ N tokens" instead of a character count.

### Fixed

- **CSS class selectors could stall highlighting**: a line starting with a dot (every class selector) sent the editor's CSS tokenizer into a loop that never advanced. The selector's leading dot is now consumed as part of its token, and a lone or repeated dot cannot produce looping or invalid highlight ranges.
- **Older env shebangs are repaired on deploy**: scripts whose shebang was previously rewritten to the busybox env applet now point at the bundled coreutils env, which keeps library paths working for Node subprocesses. Installs that need the repair re-stage even when the package set has not changed.

## 2.1 (2026-10-13)

### Added

- **Telegram remote control**: replaces the setup preview with ongoing conversations in real project chats, the app's model selection and editing tools, Telegram approval/question buttons, project/chat/model menus and simple controls. Pair through a prepared bot link with a ten-minute code and phone confirmation, and only the confirmed private account can send requests or press its buttons. A foreground notification keeps reception active while minimized and offers Pause and Stop. Incoming requests, progress, completed replies and explicit retry/resume/resend controls survive connection failures without silently repeating interrupted tools, and a delayed approval button can only resolve the exact tool call it was shown for. Settings → Telegram now keeps everything behind one status row with an overflow menu, groups the bot, account, workspace, model and permission controls on one screen, lists failed or uncertain replies under Saved work with Resend reply, and includes a Help & commands sheet.

### Fixed

- **Provider URLs that work in chat now export in settings backups**: the export validator used a parser that rejected valid HTTP endpoints with underscores in the hostname (typical local server addresses such as `http://my_server:11434/v1`), uppercase schemes, non-ASCII hostnames, and IPv6 loopback, so those setups still failed with "Export failed" after the 2.0 fix (issue #30). Validation now uses the same URL parser as API requests. A failed export also names the setting that blocks it (run limits, voice, MCP, provider URL, and so on) instead of a generic sentence, a failure to read saved credentials gets its own message, and none of these messages print URLs or secrets. The backup password minimum drops from 12 characters to 6, with show/hide toggles on both fields.
- **The privileged shell connects and reports honestly**: a Shizuku bind that never completed used to sit on "Connecting" forever, and privileged mode could turn on with only a permission grant. The runner now owns one attempt at a time with a timeout, shows "Connection failed" with a Retry action when it cannot connect, ignores late callbacks from an expired attempt, and only enables shell-privileged mode once the runner is actually connected. The Shizuku card and the terminal switch follow the same state.

## 2.0 (2026-10-12)

### Added

- **Desktop mode**: a workspace layout for wide screens such as tablets, desktop-mode phones, and emulators with a keyboard and mouse. Settings → Appearance now offers Workspace layout: Auto uses the desktop workspace only when the window is wide enough, Phone keeps chat and tools as separate screens, and Desktop forces it. In Desktop mode the app is one workspace: a persistent sidebar keeps projects, their chats, search, New chat, Projects, Scheduled tasks and Settings at hand, the conversation sits in a focused column, and Files, Changes and Terminal open in resizable panels beside it, each keeping its state while you move between them. Mouse and keyboard work like you would expect: right-click or long-press a chat for its actions, Tab and Shift+Tab move focus, Ctrl+L focuses the composer, Ctrl+Enter sends, Ctrl+N starts a chat, Ctrl+Shift+B toggles the sidebar, Ctrl+Alt+F/T/G opens Files, Terminal or GitHub, and Ctrl+S saves in the editor, while Enter runs a terminal command and Up/Down recall recent ones.

### Fixed

- **Settings export with local models**: local models are not portable, so the encrypted settings backup leaves them out and clears any model slot that pointed at one, instead of exporting a reference the receiving device cannot resolve, which is what made Export fail with "Export failed" on a device with a local model selected (issue #30). Backup files that still contain a local model have it dropped when they are read, so they still import, and a failed export now shows a plain sentence describing the actual problem instead of raw error text that could include provider URLs or secrets.

## 1.9 (2026-10-11)

### Added

- **Projects**: turn a workspace into a project you can open from the sidebar. The Projects screen lists every workspace with its chat count, and each project gets its own screen with a Chats tab (open a chat or start a new one that belongs to the project) and an Automations tab (the project's schedules, with Run, edit and Add automation already pointed at it), plus its repository and a details dialog where you can rename it. Adding a project from the Projects screen no longer takes over the workspace you are working in, so browsing and adding workspaces stops changing what the current chat uses. Automations opened from a project stay filtered to that project, and a project's run history shows only its own runs.
- **Project memory**: every chat in a project shares one notes file. The memory screen reads and edits it, counts characters against the same limit the agent uses, and keeps Clear explicit. Saving compares against the version you loaded, so a draft written while the agent was updating the notes is refused with a reload prompt instead of overwriting newer work, and saving waits while a task is running in that workspace.
- **Per-chat model choice**: a new switch in the model picker, "Use the same model in every chat", is on by default and keeps the current behaviour. Turn it off and each chat remembers its own provider and model for the active, planning and execution slots plus its own thinking level, with a "This chat" label in the header while you are in per-chat mode. Choices are saved as concrete model IDs, so editing a provider's default model later cannot change an existing chat, and a chat whose provider or model was removed asks you to pick again instead of quietly sending the request somewhere else. Backups and forked chats carry the choice, and the ChatGPT account switch at a usage limit only rewrites the selection that still matches the running request.
- **Donate**: the chat empty state adds a Donate row that opens Buy Me a Coffee, with a fallback message for devices without a browser.

### Changed

- **The model picker scrolls as one list**: the scope switch, current model, search box, filters, model list and the manage-providers row are now a single scrolling sheet, so every control stays reachable in landscape and with large text.
- **Compaction never deletes messages**: automatic and manual compaction take a snapshot, summarize it in bounded chunks (splitting long transcripts, merging partial summaries, retrying a stalled stream), and commit only if the chat has not changed in the meantime. If anything moved while summarizing, the summary is refused and your history is kept. Original messages are never removed; the hidden note and the cutoff only decide what is sent to the model. /compact can be stopped, sending is blocked while it runs, the summary request is counted in usage like any other request, and a failure is saved in the chat as a card with Details and Retry instead of a snackbar that disappears.
- **A nearly full chat summarizes instead of dropping messages**: the old behaviour quietly discarded the oldest part of the conversation before a request. That trimming is gone, so a context near its limit attempts a real summary, and if there is nothing left to summarize the chat says it is too large for the selected model and points at /compact or a model with a larger context.

### Fixed

- **Interrupted tool exchanges no longer break a request**: a history where an assistant tool call has no matching result (a stopped or crashed run, or an imported chat) is repaired for the request only, leaving the saved messages untouched. The call and whatever outcomes were recorded become a text note in the assistant message, orphan results become user messages, and the agent is told to check the current state before repeating anything.
- **`[DONE]` completes a stream**: gateways that end a response with the `[DONE]` sentinel but no finish reason no longer produce an "ended before completion" error, which also stops compaction from failing on an otherwise good summary.
- **Drafts survive a rotation or a recreation**: the composer text, the automation editor's fields and its AI conversation, and the project screens' open tab are saved and restored instead of being reset.

## 1.8 (2026-10-11)

### Added

- **Scheduled messages**: schedule a prompt from the composer through the message actions menu. Pick the date and time, how it repeats (once, every day, every hour, or only when you run it), which chat it runs in (the current one or a new chat), the workspace, and the model. The chat shows a scheduled messages row above the composer, and the Automations screen lists every schedule with Pause, Resume and Cancel. Runs are occurrence based: a worker retry, a resume after an interruption, or a process restart never sends the same prompt twice, completed tools and queued messages stay in place, and a chat that is already busy makes the run wait its turn. If you take over the chat yourself, the schedule stops and asks you to review instead of fighting your changes. A Pause action in the run notification stops a scheduled run without losing it.
- **Wait for unmetered internet**: a schedule can require a connection without a data limit, so a run that would burn metered data waits for Wi-Fi or another unmetered network and starts when one is available. The requirement rides in WorkManager's constraints, and the run also checks the live connection, so dropping off Wi-Fi pauses the run and picks it up when the connection returns.
- **ChatGPT account auto-switching**: connect multiple ChatGPT accounts and turn on Auto-switch at usage limit in Connected accounts. When an account rejects a request with the plan's usage limit before any answer starts streaming, the request moves to the next connected account, refreshing that account's available models and keeping the same model when it is offered, or using the account's fallback model. The switch is noted in the chat, finished tools and queued messages stay exactly as they were, and if every account is limited the task pauses instead of failing. Each account can be included or excluded, and each has its own fallback model choice. Connected accounts now lists every account with its own menu for refreshing models, checking newer models, reconnecting, and signing out.
- **A workspace per chat**: every chat keeps its assigned workspace. Choosing a workspace in one chat applies to that chat, Files, Terminal, Build & Test and Files changed all follow the open chat, and other chats and their running tasks stay in their own projects. New chats start from the workspace that is active when you create them.

### Changed

- **Usage stats count requests by their own date**: Today, 1 week and 1 month now filter by when each request was made instead of when the chat was last touched, so continuing an old chat no longer pulls days of work into Today. The windows follow the local calendar (midnight to midnight, including DST changes), the screen updates live as new requests come in, and Lifetime still keeps older usage that has no per-request date.
- **Folder browsing in Commit & push**: the file list now shows folders. Tap a folder to open it, long-press it to select or deselect everything inside, and use the breadcrumb or back arrow to move around. Selections survive going in and out of folders, and publishing a folder resolves to the exact changed files, so nested renames and deletions stage correctly while unrelated files are left alone. The commit message and publish button stay visible above long lists.
- **ChatGPT error messages name the real problem**: a plain "too many requests" throttle shows its own message instead of claiming the plan usage limit was reached, and unsupported models, unavailable plans and expired sign-ins each get a clear message. Only the confirmed usage limit triggers account switching.

### Fixed

- **Model setup for catalogs with nested context windows**: providers that report a model's context window as an object (llm7.io sends `{"tokens":400000,"chars":null}`) no longer fail setup. Nested context windows and reasoning capability flags are read correctly, and malformed model entries are skipped instead of breaking the whole list.

## 1.7 (2026-10-10)

### Added

- **GitHub built in**: GitHub is now the first settings page. Sign in through GitHub's Device Flow, where the app shows a code to enter in your browser (no auth server involved), or connect a personal access token. Verified credentials are stored encrypted and expiring sign-ins refresh on their own.
- **Import from GitHub**: clone a repository into its own private workspace by picking from your repository list or pasting a public URL. Import is available in Settings, when adding a workspace, and from the GitHub button in the chat header. Missing Git tools install or repair from the same dialogs.
- **Commit & push**: review the repository, branch and changed files before publishing, commit only the files you select, and retry a failed push with Push existing commits. Unrelated staged changes stay staged, app history is excluded, nothing is force-pushed, and the pushed commit is verified against the remote branch.
- **Push presets in Automation**: save a repository, branch and commit message as a preset and run it manually, hourly or daily without an AI model. A preset waits while a task is using that workspace and records blocked or failed runs in history.
- **Auto-continue**: an optional Recovery setting in Context & limits resumes a task from saved progress after a server or connection interruption. The retry limit is 1 to 5 per task (default 3), it is off by default, and stops, task limits and permanent errors still wait for you.

### Changed

- **Harness models refresh from the live catalog**: the built-in provider fetches Kilo's free model list at startup and when the picker opens, so only models that are free right now and support tools are offered. A saved pick that retired is swapped for an available model, and a model that cannot be routed says so instead of falling back to the keyed Zen path.
- **Queued messages wait for the run to finish**: a queued message is sent as a new run after that chat's successful Run finished notification instead of being injected into a momentary gap mid-run, so it can no longer interrupt the agent. Interruptions keep the queue waiting.
- **Chat header on narrow screens**: the model and thinking controls move to a second row when space runs out, so the model name no longer collides with the header actions, and the thinking badge is always labelled and easier to tap.

### Fixed

- **Object tool arguments from compatible gateways**: providers that stream tool-call arguments as a JSON object no longer fail parsing with "JsonObject is not a JsonPrimitive" (seen on llm7.io when continuing a task). Nested values are kept, a later snapshot replaces the earlier one, and odd JSON shapes in usage or finish-reason fields no longer abort the stream.

## 1.6 (2026-10-09)

### Added

- **Fix an element right from the preview**: the web preview menu adds a Select element to fix mode. Tap the element on the page, describe the change, and the agent receives the element's selector, HTML, computed styles, recent console errors, and a screenshot of the page, then edits the source and checks the result. Taps made while picking never reach the page's own handlers.
- **Run and preview your project**: Build & Test detects the project's dev, start, and preview scripts (plus plain `index.html` projects), runs the chosen one, and opens the URL the server reports in the preview sheet on its own, with Stop to shut it down. The saved command list is built from the project's real `package.json` scripts instead of generic guesses, and the chosen script sticks per workspace.
- **Verification cards in chat**: turns that touched files or ran a check now end with a Verification card listing every build, test, or lint command the agent actually ran with its recorded outcome: passed, failed, or unconfirmed. A passed check that came before a later edit is marked as needing a recheck. Only real tool results count, so a reply that claims tests passed without running anything shows nothing.
- **Ask about a change**: the Files changed screen adds an Ask action on whole-file diffs and on single sections. The question carries the diff (or the before and after of the section), tells the agent to explain without editing, and lands in the chat.

### Fixed

- **Errors injected into SSE streams**: an error arriving mid-stream, including the `choices[0].error` and `finish_reason: error` shapes some gateways use, now parses the provider's message and code, stops the attempt at the error instead of consuming trailing deltas, and classifies retries from the real status code instead of guessing from text. Tool-call arguments accumulated before the error are discarded, so a torn stream can never hand the engine an executable half-built call.
- **npm project scripts run on-device**: npm's child shell could not exec the bundled node from app storage, so `npm run` died with a permission error before the script started. Project launches now route npm through the same shim path every other toolchain command uses.
- **Stop really stops the dev server**: stopping a project run kills the whole process tree, so the server no longer survives in the background holding its port.
- **Workspace previews open on the first try**: the preview registers a workspace HTML file as its base document before loading, so opening one no longer depends on the agent having navigated there first.

## 1.5 (2026-10-05)

### Added

- **Legacy storage access for Android 8 to 10**: runtime storage permission support (`READ_EXTERNAL_STORAGE` and `WRITE_EXTERNAL_STORAGE`) and legacy external storage access for Android 8 through 10. Forms a tiered storage ladder alongside Android 11+ all-files access. Private app workspace storage works without broad storage permissions, while the folder picker resolves real filesystem paths for external projects once granted.

### Fixed

- **False missing API key error for local providers**: local endpoints (Ollama, LM Studio, localhost, or LAN IPs) no longer fail with missing credential errors during chat, resumption, compaction, automations, subagents, or catalog browsing. Outgoing requests omit the Authorization header when no key is set so servers do not reject empty bearer tokens, while custom keys still pass through.
- **Provider sheet viewport cutoff and scroll dismissal**: provider configuration and model selection sheets now fit within the visible screen height with navigation bar padding. Provider and model lists scroll independently while Save and action buttons remain pinned above system gesture insets instead of getting pushed off-screen or triggering sheet dismissal gestures.

## 1.4 (2026-10-03)

### Added

- **Continue with ChatGPT**: Settings → Connected accounts → Continue with ChatGPT connects an eligible Plus, Pro or Go plan through the browser and lists the account's models in the chat picker. Choose one and use the app's coding tools with the usual permission prompts. A welcome card introduces the connection after sign-in, and Manage usage opens ChatGPT's plan and app limits. Sign-ins stay on the device and are excluded from settings backups.
- **ChatGPT thinking levels**: account models support the app's thinking ladder. Each model's documented reasoning efforts map onto the scale, the header badge offers only the levels the model supports, and the model's own metadata wins over family fallbacks.
- **Newer model checks for ChatGPT accounts**: a check in Connected accounts probes newer GPT models with a real inference round trip and adds the ones the plan confirms to the picker, reporting how many were added. Verified models survive catalog refreshes.
- **Sub-Agent settings**: action tools and a separate provider/model choice for subagents now live in their own settings screen, independent of the main execution model. A saved subagent model takes priority over the task tool's `model` override; leaving it empty keeps the main agent's model.
- **Safetensors imports**: separate GGUF downloads and safetensors imports in Local models. Supported dense Qwen and Llama BPE weights convert on the device to Q4 GGUF.
- **Local model import and recommendations**: file manager import for GGUF files and safetensors folders, searchable recommendations in both formats, and community abliterated models. Installed models and their settings now appear first in a simpler layout.
- **Local models use the agent**: local models can call agent and MCP tools, use dual planning, and process images with a compatible vision model and matching projector.

### Changed

- **Local model fit warnings**: RAM, storage, training-context and CPU-thread estimates now show warnings with Continue. Models remain visible, fixed context/output/thread caps are removed, and oversized conversations can continue with a larger context.

## 1.3 (2026-10-02)

### Added

- **On-device local models**: download Qwen 2.5 0.5B, 1.5B, Coder 1.5B and 3B GGUF models from Settings → Local models, then select them in the chat provider picker. Replies run on the device's CPU through llama.cpp, with no API key or cloud inference charge. Inference works offline after download on compatible 64-bit devices. Local models support text chat only, without images, agent tools or dual planning.
- **Custom models from links**: paste a public Hugging Face model page, GGUF file page or direct HTTPS `.gguf` link, choose the model file and confirm the download. Hugging Face files use pinned revisions and verified SHA-256 checksums. Direct links are checked for file size and a GGUF header, with a notice when the source provides no checksum. Custom models stay available after app restarts. Safetensors, split GGUF files and vision projectors are not supported.
- **Local model setup and controls**: check available RAM, free storage and estimated model fit, show models outside the available-memory budget, and set context, input, output and CPU thread limits per model. Downloads show progress and support cancellation. Stop generation or remove a model and its saved limits to free storage. Memory is released after each reply; interrupted downloads restart from the beginning. A removed or unavailable local model reports an error instead of silently switching to a cloud provider.
- **Shizuku workspace warning**: when previously granted Shizuku access becomes unavailable because the server stopped or permission was revoked, opening workspace controls or the workspace creation dialog shows “Shizuku is disabled.” A “Never show this message again” checkbox saves the choice across app restarts.

### Fixed

- **Crashes when opening old chats**: initial scrolling waits for pending content updates and snaps gently to the bottom instead of forcing a large remeasure, avoiding the reported deactivated layout-node crash.
- **Crashes while scrolling streamed replies**: chat-list prefetch is disabled, tool-row keys are scoped to their message, and rows use explicit content types to prevent unstable item reuse during streaming.

## 1.2 (2026-09-25)

### Added

- **Subagent action tools**: a Settings switch lets subagents edit files and run commands in Act mode through the same permission prompts as the main agent, so a delegated task is no longer limited to research. Plan mode stays read-only, and subagents still cannot ask questions or spawn subagents of their own.
- **Generation speed and first-token delay**: turn footers report throughput measured over the streaming window instead of the whole request, plus how long the first token took. Both values are stored per message, carried through chat backups, and turns recorded before this release keep their request-inclusive rate, labeled as such.

### Changed

- **Streamed chunks are never dropped**: the SSE reader suspends while the consumer catches up instead of discarding chunks when its channel is full, so fast models cannot silently lose text. Malformed keep-alive lines are still ignored, and the chat UI redraws at 30 fps.
- **`http_request` is public-addresses only**: localhost, private, link-local and other non-public destinations are refused, DNS answers are validated and pinned for the request, proxies are bypassed, and redirects are no longer followed, so a request cannot be bounced into the local network.

### Fixed

- **Chat scroll crashes**: paused-composition prefetch is disabled on LazyColumn, the LayoutNode removal path that crashed while messages streamed in.
- **Staged promise results in browser eval**: a resolved value that cannot be JSON-encoded now reports an eval error instead of leaving the pending promise unresolved.
- **`move_file` overwrites**: the tool refuses to replace an existing destination unless `overwrite=true`, so a mistyped destination no longer destroys the file that was already there.
- **`file_info` line counts**: CR-only and CRLF terminators are counted the same way `read_file` counts them, so the two tools agree on the same file.
- **Unreadable and past-the-end reads**: `file_info` reports an error instead of describing a file it cannot read as empty, and `read_file` says how long the file is when an offset runs past its end.
- **`apply_patch` hunk validation**: hunk headers are parsed strictly and their line counts must match the body, so a malformed patch fails with a clear error instead of editing the wrong lines.
- **Workspace browser root fallback**: blank and root paths serve the workspace index.html instead of reusing whichever document is loaded, which is what made form GET submits land on "/?...".
- **Shell output framing**: stdout and stderr come back as JSON strings, so multi-line output, quotes and escapes cannot be mistaken for the tool's own framing.

## 1.1 (2026-09-20)

### Added

- **SSH workspaces and connection manager**: connect to remote servers and local Termux environments over SSH via pure-Java JSch with Ed25519 support. Includes `SshFs` for SFTP-backed workspace file operations and editing, remote command execution for agent tools and background jobs, a persistent connection bar with connection status, and saved profiles in workspace settings.

### Changed

- **GitHub updater dialog layout**: redesigned the update popup with an expanded surface, dedicated scroll container, and pinned action buttons. Release notes render with full markdown formatting, links, and bullet points without block truncation or line caps.
- **Unobtrusive chat toasts**: moved the "Files changed" undo prompt and checkpoint restore snackbars directly above the message composer, keeping the input field and action buttons unobstructed. The scroll-to-bottom button glides up smoothly when a toast is active.

### Fixed

- **Atomic message retry and edit rewind**: editing and resending a message atomically truncates subsequent chat history and database records, preventing desynced or orphaned messages when rewinding turns.
- **Chat autoscroll stability**: snapping to the bottom during message streaming uses frame-aligned scrolling without forced remeasures, preventing Compose layout crashes during rapid message generation.

## 1.0 (2026-09-19)

### Added

- **CodeGraph semantic code index**: the agent can install and run CodeGraph on-device (bundled Node runtime for the shell tier, installed from release archives instead of npm) and gained five new tools: `codegraph_explore` for symbol lookups with call paths, `codegraph_node`, `codegraph_impact` and `codegraph_affected` for dependency questions, and `codegraph_sync`. The MCP server syncs live with the workspace, the workspace card shows indexing progress, and a settings screen manages install, updates and telemetry.
- **Caveman reply modes**: an optional terse reply style with its own settings screen, driven by an intensity setting and optional skills.
- **Encrypted settings backup**: settings, provider setup and catalogs can be exported and restored as an encrypted file, separate from chat backups, with provider API keys included optionally.
- **Remembered permission management**: a settings section lists every remembered tool permission so they can be reviewed and revoked individually.
- **About page**: license, project links and credits.

### Changed

- **Harness provider is now a keyless community pool**: the built-in provider lists anonymous free models from Kilo (19 models, ~200 requests per hour per IP) and Pollinations (openai-fast, ~4 requests per minute) with each model's rate limit shown right in the picker, and defaults to the kilo-auto/free router. OpenCode Zen was dropped from the list. Zen requests now carry the full identity header set, and a saved Zen key is reused automatically for custom model ids.
- **UI polish**: monospace font for model ids and code, larger tap targets, a quieter header, and syntax highlighting for code blocks in chat.
- **Screenshots are allowed by default** everywhere except credential screens; the old global screen-capture block is gone.
- **Background agent service**: long tasks keep the app alive in the background with a stop action in the notification.
- **Build tooling security lifts**: Bouncy Castle 1.85 (cert name-constraints bypass and ASN.1 depth guard), netty 4.1.137, plus jdom2, jose4j, httpclient and commons-lang3 raised to patched releases, and Kotlin 2.4.10 so CodeQL can compile the project. None of these libraries ship inside the APK.

### Fixed

- **Browser navigation**: going back after a click navigation no longer skips a page, and the settle logic reads the WebView's own history list instead of guessing where a step landed. `git_commit` no longer stages runtime artifacts.
- **Checkpoint undo**: failed checkpoints are kept so a partial undo can be retried.
- **Redaction**: modern API key formats are caught in tool output and every result path is redacted.
- **web_fetch and git**: CommonJS callers resolve with block boundaries kept in text mode, git config operations no longer abort, grep excerpts and HTML fetching are fixed, and browser element targeting is more reliable.
- **explore agent**: no longer answers for a symbol that does not exist in the index.

## 0.11-alpha (2026-09-12)

### Added

- **Prompt cache tracking across providers**: cache reads and cache writes are now captured per turn on Anthropic, OpenAI-compatible, OpenAI Responses, and Gemini, shown in the live usage indicator and kept on the finished turn summary. Turns display tick and cross markers for cache hits and misses, and the stats screen reports an overall cache hit rate. Exact per-model cache prices from the models.dev catalog are stored alongside each usage event so cached turns are costed correctly.
- **Message timestamps**: assistant and user messages show when they were generated.
- **Copy task reason**: interrupted and paused task cards expose the reason text for selection and one-tap copying.
- **Binary response reporting**: web fetch and HTTP request tools detect binary bodies and return a compact `[binary content: type, bytes]` note instead of decoding garbage into the transcript.

### Changed

- **Clearer cache wording**: the stats and context panel label is now "overall cache hit rate" so it reads as a running total rather than the current turn.
- **Web search quality**: results are de-duplicated by URL, an unknown engine name is rejected with the supported list, empty queries short-circuit, and the Brave parser scopes extraction to the results block and drops its own chrome pages.

### Fixed

- **LazyColumn measure crash on send**: the message list no longer throws during measure when a send races the initial load; the view model seeds its loading state from the requested session and item keys stay stable.
- **Anthropic stream usage accounting**: token counts are treated as cumulative snapshots and the final usage event is emitted even when a gateway closes without `message_stop`.
- **Interrupted tasks**: a task restored from disk that was still marked running is now shown as interrupted and can be resumed.
- **Grep hardening**: patterns with nested quantifiers, overlapping repeated alternatives, or oversized repetition counts are rejected before execution, and matching runs under a step budget plus a wall-clock timeout on a worker thread with an explicit result count.
- **Shell process cleanup**: timed-out commands kill their whole process group (including detached grandchildren) instead of leaking orphans, and normally finished commands clean up any orphaned group members. Setsid wrappers now export `PATH` and `LD_LIBRARY_PATH` so privileged commands resolve the bundled toolchain, and Shizuku service restarts are tracked.
- **Browser recovery**: a wedged WebView render process is terminated and the view is rebuilt via `about:blank` without destroying the sheet, `eval` has a timeout that reports hangs instead of freezing the agent, and headless views no longer show blank pages on first use.
- **Apply patch newline fidelity**: patches preserve CRLF and bare CR line endings for both existing files and new files.
- **move_file validation**: refuses to move a path into itself, into a subdirectory, or into an ancestor, rejects a destination that already exists as a directory, and reports over-long filenames with guidance.
- **read_file and file_info robustness**: negative offset and limit are rejected, very long lines are clamped with a visible marker, and over-long filenames get an actionable message instead of a raw error.
- **Database self-repair**: oversized message rows that would throw `SQLiteBlobTooBigException` are truncated on open and clamped on write, so corrupted sessions heal instead of crashing at startup.
- **Logcat filtered history**: tag and text filters are applied through a tail pipeline so the requested number of lines is preserved instead of being consumed by the raw head of the buffer.
- **OpenCode relay headers**: the session and user-agent headers are applied consistently across Anthropic, OpenAI-compatible, and Responses wire formats.

## 0.10-alpha (2026-09-07)

### Added

- **Scheduled background automations**: schedule recurring or interval-based prompt tasks powered by Android WorkManager. Features an AI-assisted planner that translates plain language into schedules, a bottom-sheet editor with preset schedule chips, per-automation model selection, manual run triggers, detailed run history, and completion notifications.
- **Build & Test dashboard**: run development checks, Gradle commands, and test suites with live terminal output and Shizuku status. Build failures include a one-tap agent repair action that routes the failure logs into a new chat session for automated fixes.
- **Built-in keyless Harness provider**: pre-configured out-of-the-box provider via the OpenCode Zen relay, granting immediate model access without API keys, complete with model probing and session tracking headers.
- **Batch file operations**: multi-select files and folders in the file manager with a bottom action bar for batch deletion, copying, and moving via a destination folder picker.
- **In-chat file diff viewer**: tool calls that modify files now offer an in-chat diff sheet to inspect additions and deletions directly without leaving the conversation.
- **Responsive markdown tables**: chat messages format markdown tables into compact preview cards that expand into a dedicated full-sheet viewer with horizontal scrolling.
- **Turn activity rollups and streamlined tool cards**: consecutive tool calls roll up into a compact activity summary with reactive expansion states, status indicators, and distinct tool icons.
- **Response duration and token throughput**: tracks response time and tokens per second for assistant messages in database schema v11, displayed in message stats and included in chat backups.
- **Custom model IDs**: enter arbitrary custom model identifiers directly in the model picker sheet across all supported providers.

### Changed

- **Navigation drawer and provider sheets redesign**: the navigation drawer adds a quick-access tool strip for Files, Terminal, Automations, and Build & Test, alongside a dedicated active provider card. Provider setup and model picker sheets feature refreshed hero cards and cleaner preset styling.

### Fixed

- **Thinking budget and reasoning level wiring**: enforces Claude's 1,024-token minimum reasoning budget, clamps unsupported thinking configs accurately, and maps Gemini thinking levels to API specifications.

## 0.9-alpha (2026-09-06)

### Added

- **Developer repo wiki**: a generated 50-page wiki of the codebase lives under repowiki/, linked from the README.

### Fixed

- **Dual planning survives provider switches**: picking a provider or model from the planning or execution picker routes the choice to that role's slot and keeps dual planning on. A provider change used to reset dual planning or land the pick in the wrong slot, leaving the header showing something other than what would run.
- **The preview sheet stays on the agent's page**: the agent and the web preview share one webview choice, the agent's console logs bridge into the storage the sheet reads, and screenshots keep fractional scroll offsets instead of snapping to whole pixels.
- **Browser navigation keeps queries and anchors**: URLs with ?query or #fragment parts navigate to the full address, and rapid console log arrivals are deduplicated instead of flooding the action trail.
- **search_files matches root files with recursive globs**: a pattern like **/*.py also hits files sitting at the workspace root, and an invalid glob now returns a clear error instead of crashing the tool (search_files and grep include patterns both).
- **git show emits diffs again**: asking for the patch prints it, a log or show on a repository with no commits yet reports "No commits yet" instead of a bare failure, and an empty history answers with a sentence instead of "(no output)".
- **git commit handles real-world messages**: quotes and apostrophes in a message are shell-quoted so the command no longer breaks, .harness/ runtime files are excluded at commit time even when they were staged earlier, and a small locale shim keeps scripts that probe the locale working in the toolchain.
- **git_push records and repairs upstream tracking**: the first push of a new branch now sets its upstream so a later git_pull works, and a branch tracked to the wrong ref (say, cut from origin/main) gets re-pushed with -u to point at itself.
- **Cost estimates price at a real listing**: resellers list the same model id everywhere from free promo to list rate, and the free listing won the lookup, pinning a whole session's estimate near zero. Pricing now skips $0 matches unless every listing is free.
- **grep can't wedge a session with a pathological regex**: a pattern like ^(a+)+$ used to backtrack forever inside the regex engine and hang the run; matching now runs on a step budget and fails cleanly with an exceeded-budget message.

### Changed

- **Non-repo workspaces ask for git init**: git tools answer with "not a Git repository, run git init in the intended folder first" instead of silently creating a repo in place.

## 0.8-alpha (2026-09-04)

### Added

- **Agent browser control**: the agent drives a headless WebView through 13 browser tools (navigate, snapshot, click, type, scroll, eval, screenshot, wait, back/forward/refresh, get URL, open URL). Workspace pages serve over an in-app asset loader, screenshots render inline in the chat and save as JPEG under .harness/screenshots, and a floating bubble with a live action trail expands into the preview sheet pinned to the page the agent is on.
- **Per-chat dual planning**: a Dual planning toggle in the chat menu replaces the old global switch. Plan mode runs on the planning model slot, approval flips to Act on the execution slot, the header shows a fork icon with a model-only subtitle plus Plan/Exec suffix, and the pending plan card survives app process death.
- **read_image tool**: the agent reads images from the workspace or past screenshots by filename, with vision frames fed into every provider and graceful text fallback on text-only models.
- **read_logcat tool**: the agent queries Android logs with package, tag, level, and pattern filters.
- **PDF attachments**: attached PDFs extract their text into the message so the agent can read them.
- **Dynamic package installer**: the agent installs Linux packages through a dedicated tool family with a package manager sheet in Settings.
- **Retry in the message menu**: long-pressing your own message offers Retry alongside Copy and Edit, resending the text as a fresh turn.
- **Total estimated cost in Stats**: the hero line adds the window total at list prices, summed with the same per-row math as the By model card.
- **Tap-to-lock voice recording**: tapping the Groq Whisper mic locks recording open directly, alongside the existing hold, slide-up lock, and slide-left cancel gestures.
- **Model picker reloading state**: the chat model sheet shows a spinner with reloading text on manual refresh and a loading row on auto-fetch, and provider add auto-syncs the catalog with Custom endpoint first.

### Fixed

- **Preview chip only on live servers**: the Open Web Preview button renders only when the mentioned localhost port accepts connections, so dead server mentions show no chip.
- **Plan approval loop resolved**: plan runs resolve the role model through the full fallback chain instead of raw slots, approval questions and mutating tools are rejected in Plan mode, and plan text carries no preview directives.
- **Provider catalog counts agree**: the update toast and the add-provider search hint count the same speakable rows, and the directory updates live on refresh.

## 0.7-alpha (2026-09-01)

### Added

- **Aider-style Repo Map**: an automatic codebase indexer that scans project files and generates a compact hierarchical symbol outline (classes, interfaces, methods, functions, and types for Kotlin, Java, Python, JavaScript, TypeScript, HTML, and Shell scripts). The outline injects directly into the agent's system prompt so the model understands codebase structure without burning tool calls on broad exploration, with an opt-in toggle in Settings under Chat behavior.
- **In-app web preview hub**: a full preview environment for web projects. It auto-probes open localhost ports, previews local workspace HTML files, and opens chat links directly in a bottom sheet with Eruda DevTools, responsive device frames, console logs, and a one-tap "Fix this bug" bridge that sends errors directly back to the agent.
- **Voice input and Groq Whisper transcription**: transcribes speech using either Android's native speech recognizer or cloud-backed Groq Whisper (`whisper-large-v3` and `turbo`). Features a floating input layout with live audio waveforms, hold-to-talk, swipe-to-lock, and slide-to-cancel gestures.
- **Visual diff viewer**: a dedicated diff screen with dual line-number gutters, syntax coloring, additions and deletions stats, and horizontal scrolling for side-by-side review of agent file changes.
- **Fork conversation**: fork any chat from any assistant turn into a fresh session with cloned message history, compaction summary, and token usage records.
- **Resume last active chat**: an option in Settings that automatically reopens your most recent conversation on app launch with a shimmering skeleton loading transition.
- **Biometric auto-lock timeout**: configure how quickly the app locks when backgrounded (Immediately, 1m, 5m, 15m) to avoid repeated authentication prompts during quick app switches.
- **Rich syntax highlighting in code editor**: multi-color token styling for Kotlin, Java, Python, JS, TS, HTML, XML, CSS, JSON, and Shell in the built-in file editor.

### Fixed

- **Chat rendering and streaming performance**: eliminated 60 fps main-thread recomposition bottlenecks during typewriter streaming by optimizing selection layout bounds, precomputing message lookups outside LazyColumn item scopes, and moving infinite animation transitions to hardware graphics layers.
- **Biometric authentication reliability**: require biometric or PIN confirmation before toggling app lock in Settings, and fixed a cold-start initialization race where the app briefly bypassed the lock screen before preferences loaded.
- **Code editor multi-line styling**: fixed span generation in sora-editor where identical consecutive token styles were dropped across line boundaries.
- **Security audit hardening**: hardened scratch directories to 0700, added zip-slip extraction validation, scoped DataStore backup exclusions, validated package file paths, and routed MCP OAuth through Chrome Custom Tabs.

## 0.6-alpha (2026-08-31)

### Added

- **Chat backup and restore**: a Chat backup card in Settings exports every chat with its full message history to a JSON file through the system picker, and imports a file back into the chat list. Import skips chats that already exist by id, so re-importing the same file is a no-op. The file holds chats and messages only: no API keys, providers, or settings ever go in, tool-call payloads ride along as the exact strings from the database so old files keep importing, and imported messages are immediately searchable.
- **Full-text chat search**: the drawer search now searches inside messages, not just titles. Hits come from a Room FTS4 index and render with highlighted snippets under their chat, and a fuzzy switch toggles between word-prefix matching and a substring mode that finds code fragments and partial words. Tapping a hit opens the chat.
- **Separate planning and execution models**: a Settings switch that gives Plan mode its own provider and model slot while execute mode keeps another. Each slot is picked with the same model sheet as the header, falling back to the active provider, and mode changes toast which model will run. A one-time dialog introduces the feature the first time you switch into Plan mode, and its Configure button jumps to Settings scrolled to the card.
- **Share target**: the app appears in Android's share sheet for text and images. Shared text or links prefill the composer, and shared images run through the normal attach pipeline, so sending a stack trace or a browser page to the agent is one tap away.
- **@-file mention picker**: typing @ in the composer suggests files from the current workspace (lazily loaded, ignore rules respected), and picking one inserts the path. The system prompt tells the model that @path means read it.
- **Attachments for every file type**: the attach button now offers Any file next to Photo. Text-like files under 32 KB ride inline as fenced blocks on the message, larger or binary files are copied into the workspace under .harness/attachments and referenced by path, and message bubbles show them as name and size chips.
- **Compaction you can see**: automatic and /compact compaction now show a "Compacting conversation" banner while running and leave a visible notice message where the history was folded, and the context panel updates live right after folding instead of showing a stale number.
- **MCP servers reconnect on their own**: servers that connected successfully once are recorded in a status sidecar and reconnect themselves after the app restarts, remote ones immediately and stdio ones once the Linux toolchain is ready. Failures never disqualify future retries, and removing a server clears its marker.
- **Workspace MCP configs need approval**: a .harness/mcp.json inside a cloned workspace no longer connects its servers silently. The chat shows an approval dialog before the run starts, with Approve remembered per workspace path plus a content hash (so an edited file re-prompts) or Run without them; global servers connect exactly as before.
- **AMOLED theme**: a fourth entry in the theme selector that blacks out every surface role while keeping the accent colors from the dynamic or static palette.
- **Allow screenshots toggle**: a new Privacy section in Settings. Screenshots stay blocked app wide by default; with the toggle on they work everywhere except while a key or token is on screen.

### Fixed

- **Shell tier coreutils work again**: ls -la rejected its own flags and echo/printf emitted nothing. Two causes stacked: the linker shims resolved symlinks to the canonical coreutils target, so the multi-call binary lost the invoked name and its argv[0] dispatch broke, and bash 5.3 stopped sourcing BASH_ENV for -c. Shims now route through the bin entry itself (which also repairs git helpers, xz, setarch and busybox applets) and every spawned command sources the shim explicitly; existing installs regenerate the shims on first shell use or app start.
- **Allowing screenshots really allows them**: with the toggle on, Settings, Providers, and Setup no longer force the whole screen secure; only the surfaces that put a secret on screen raise the policy. Dialogs and bottom sheets are covered too, which they never were before: they live in their own windows that the activity-wide flag could not reach, so a screenshot over a key dialog captured the dialog on a black background.
- **The mention picker no longer eats the next keystrokes**: picking a file from the @ suggestions left the text field's cursor at its old position, so typing landed after the @ instead of after the inserted path. Every programmatic composer write (mention picks, share prefills, slash picks, resets) now places the cursor at the end of what it inserted.
- **The Linux environment install reacts instantly**: a Preparing state with a spinner now covers the package index download and dependency resolution that used to look like a dead button for several seconds before the first package progress appeared, in Settings, the setup screen, the chat environment sheet, and the run-time environment card.

### Changed

- **Unused media permissions removed**: READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, and READ_MEDIA_AUDIO are gone from the manifest. Nothing referenced them; all file and media access goes through the Storage Access Framework and the all-files grant.
- **Credentials stay out of cloud backups**: the encrypted API key preferences, the MCP server config with its plaintext tokens and HMAC sidecar, and the GitHub token copies inside the deployed toolchain are all excluded from Android cloud backups and device transfers, which also keeps the multi-hundred-MB toolchain from blowing the 25 MB backup quota.

## 0.5-alpha (2026-08-29)

### Added

- **MCP servers on every transport**: connect Model Context Protocol servers over stdio, Streamable HTTP, and the legacy HTTP+SSE transport, with session handling, protocol version headers, and both JSON and SSE response modes. Adding a server is paste-first: drop in a Claude mcpServers JSON block, a single server object, or a `claude mcp add` command line and see a live preview before saving. The manual editor covers command, args, env, URL, and headers per transport type, and a workspace `.harness/mcp.json` merges its servers into that workspace's chats.
- **Sign in to MCP servers with OAuth**: when a server answers a request with a 401 challenge, Settings shows an Authenticate button. The flow implements the MCP authorization spec end to end: protected resource and authorization server metadata discovery, dynamic client registration, PKCE S256, the RFC 8707 resource parameter, a deep link redirect back into the app, code exchange, and automatic token refresh, with tokens stored in the encrypted keystore. Verified live against a real OAuth provider (Supabase), where 14 server tools connected and ran.
- **Web search with real API keys**: Brave Search and Tavily join the keyless engines. The compact settings card has a three way provider selector, a Get API key button that opens the provider's key page in the browser, and a save flow that verifies the key with the provider before storing it, so a bad paste never becomes the saved credential. Keys live in per provider slots so switching engines keeps both, and an API failure falls back to the keyless engines with a note.
- **Subagent model override**: the task tool accepts a model id from the provider's live catalog, so parallel subagents can run on different models. Unknown ids are refused with the valid list before anything spawns, the catalog fetch is shared across all parallel tasks in a run, and token usage attributes to the override model. The task title now shows in subagent progress lines.
- **MCP config integrity**: every app-side save of mcp-servers.json writes an HMAC sidecar keyed by a non exportable keystore key. A file edited out of band is refused, its contents are reset, and Settings shows a red banner instead of silently running tampered server definitions.
- **Security hardening from the on-device stress test battery**: the deployed toolchain's git config directories tighten to 0700, memory_write refuses topics that would be silently renamed (reads stay lenient so old names still resolve), and the full access permission mode now states its trust boundary in plain words in Settings.

### Fixed

- **git_show stat mode shows stats again**: `--no-patch` suppresses stat output in any position, so the message plus stats mode now runs `show -s --stat` and really delivers both.
- **Commits stop printing "fatal: cannot exec maintenance"**: every git invocation disables auto gc and maintenance, which the sandbox could never exec anyway.
- **web_search reports what it did**: the `[via]` tag names the engine that actually answered, and an explicitly requested engine while an API backend is active gets a note instead of silently returning different results than asked for.
- **The MCP Test button surfaces sign-in immediately**: Test records the same statuses a real connect does, so a 401 found by Test shows the Authenticate button right away instead of only after a run.
- **The MCP add dialog segmented control lays out correctly**: one short word per segment keeps the type row on a single line.
- **Keystore loss no longer crash-loops the app**: when the encrypted preferences file cannot be decrypted (credential reset, or a backup restored onto a fresh install), the app resets it and starts over empty instead of dying at startup.

### Changed

- Every em-dash is gone from the app's text; string messages got natural punctuation and code comments became commas.
- A stable copy of the debug signing key is kept in the gitignored signing-keys/ folder after a keystore regeneration broke plain reinstalls.

## 0.4-alpha (2026-08-28)

### Added

- **Workspace file manager** — the sidebar "Workspace files" screen is now a full manager. Long-press any file or folder for rename, copy-to, move-to (with a folder picker that refuses dropping a folder into itself), delete, share to other apps (staged through the app cache via FileProvider), and open externally. Header actions create new files and folders; a filter field narrows the current directory. Works identically on app-private, full-access and SAF (picked-folder) workspaces.
- **Real code editor** — the old viewer + plain text field is replaced by a sora-editor screen: gutter line numbers, unlimited undo/redo, incremental keyword highlighting per language, a find bar with match-case and regex toggles plus replace/replace-all in edit mode, word-wrap toggle (on by default), go-to-line, and an explicit save flow (dirty indicator, unsaved-changes prompt on back). Saves restore the file's original BOM/charset/EOL shape instead of silently normalizing it. Binary and 16 MB+ files show an info card with Share / Open-with instead of corrupting as text. Drawer edge-swipe is disabled on the editor screen.
- **Per-chat "Files changed" (GitHub-style)** — every file the agent edits in a conversation is tracked cumulatively per session: the file manager shows green "+N −M" badges per file plus a summary strip, a drawer pill surfaces the open chat's change set, and a Files-changed screen lists every touched file (green dot for new, amber for modified, red for deleted) with tap-to-expand diffs rendered against a gzipped snapshot of the file's content at session start — baselines are pinned on first touch, so diffs stay reviewable even in old chats, and manual editor saves feed the same tracker. At the end of every turn the edited files also render as a collapsible card (total +N −M expanding into a per-file list with tap-to-open).
- **Full access mode** — a fourth permission level (orange, in Settings → Agent and the chat ⋮ → Permission menu) that lifts every sandbox layer at once: no approval prompts, no shell command denylist, no workspace path containment. File tools can read/write any path on the device (absolute paths work), the shell runs from any cwd, and subagents inherit the same freedom. The system prompt tells the model the sandbox is lifted. SAF (picked-folder) workspaces keep their containment — there is no shell root to extend.
- **GitHub auth that survives toolchain reinstalls** — the token's master copy lives in the app's encrypted settings and is re-materialized into both shell tiers on every start and deploy: `~/.gh-token` (0600) for agents, a commit identity, and a `url.*.insteadOf` rewrite that authenticates every `https://github.com` URL. Login lives at the top of Settings: paste an existing token or tap Get access token (browser deep link, with scope toggles for workflow/gist/read:org/delete_repo), and Save-and-check validates it against api.github.com first, showing the verified plan and scopes afterwards. Log out clears the token and every toolchain copy — for real (see Fixed).
- **gh CLI joins the standard toolchain** — GitHub's official CLI (Termux gh 2.98.0, ~10 MB) is part of the package set: fresh installs include it, existing installs pull it via Settings → Terminal & environment → Update, and env_status probes it. With a token set, gh is pre-authenticated automatically via a materialized `~/.config/gh/hosts.yml`.
- **doctor --github diagnostics tool** — one command for the whole GitHub connection: a single API ping (verified login, account type, the real plan name from /user, granted scopes from X-OAuth-Scopes with consequences spelled out for missing delete_repo/gist/read:org/workflow), the token file's real permission bits, git's helper shell (`git var GIT_SHELL_PATH` + exec check) and the insteadOf rewrite, and a deterministic Free-plan trap probe: rulesets/branch-protection return 403 "Upgrade to GitHub Pro" for private repos on free accounts — which reads as "no protection configured" while force-pushes succeed — so it probes the first recently-pushed private repo (GET-only, labeled as such) and one org's, and warns explicitly when protection is paywalled.
- **http_request speaks GitHub** — requests to api.github.com and uploads.github.com automatically carry the stored token as Bearer auth (the 60 req/h anonymous rate limit becomes 5000 and private repos stop 404ing); `github_auth: true` extends auth to other github.com/githubusercontent.com hosts, `github_auth: false` forces anonymous, and the host policy structurally confines the token to GitHub hosts so it can never ride to a third-party URL (listener-probe verified against wildcard-DNS lookalike hostnames). Explicit user Authorization headers win; a 401/403 on an authenticated request hints at doctor --github; missing-token requests get a setup note.
- **Undo becomes a full rewind** — a confirmation preview plus chat roll-back instead of message-level undo.
- **ask_user upgrades** — multi-select options, and bullet-list options baked into question text are recovered into real choices across every model shape, with a redesigned question card. A bundled grill-me skill turns interviews into idea-forcing sessions, and `/plan` stays in the composer.
- **Terminal & environment flow** — a real Check-missing → Update flow in Settings, an `/env` chat command, uninstall confirmation, and a one-time "new packages" popup for existing installs when the package set grows (gh was the first late package).
- **Navigation polish** — the workspace explorer moves to the chat top bar with the workspace switcher inside the file manager, and sidebar search becomes a top-right reveal button.
- **StreamRetrier** — the three inline SSE retry loops consolidate into one shared implementation.

### Fixed

- **Security: the deployed toolchain no longer leaks the GitHub token** — `etc/gitconfig` (which embeds the token in the insteadOf URL) shipped 0644 inside an o+x-traversable /data/local/tmp with 0777 top dirs, readable by ANY installed app that guesses the path. The deploy now chmods the top-level dirs 0700 and all three auth-bearing files 0600, and the staging tarball carries no auth at all — the deployed prefix receives its credentials via a direct privileged-shell write (base64 payloads, 0600) after every deploy.
- **GitHub logout actually logs out** — it used to clear only the app's encrypted copy while `.gh-token`, gh's hosts.yml and the insteadOf rewrite survived in the deployed prefix, so git push and gh api kept working with the supposedly-logged-out token; propagation depended on a full re-stage/redeploy whose failures were silently swallowed. Auth changes now take the direct write path that cannot be skipped, env_status warns "STALE SHELL CREDENTIALS" when the app has no token but the deployed copy still does, and the token file's permission bits are really stat'ed instead of hardcoded ("(0600)" was printed while a 0755 copy shipped).
- **GitHub logout/login no longer ANRs** — the re-stage/redeploy chain gzipped the entire toolchain prefix on the caller's dispatcher and Settings' logout coroutine ran on main, freezing the UI into "input dispatching timed out". The chain switches to Dispatchers.IO internally and the UI updates immediately; token changes also no longer force a 30–60s redeploy (the token fingerprint left the staging hash — auth rides the direct sync, and the tarball is quietly re-staged so it never goes stale).
- **Git can spawn helpers again** — the Termux git ELF's compiled-in SHELL_PATH (/data/data/com.termux/files/usr/bin/sh, dead outside Termux) is patched in place to /system/bin/sh at extract time and self-healed on existing installs, so `gh repo clone`'s credential helper, hooks and aliases work in the privileged tier; the insteadOf rewrite remains the always-works transport in the app-uid tier (W^X). The generated gitconfig sets `init.defaultBranch = main` and no longer writes a `credential.helper` reset.
- **env_status tells the truth** — headline tools are resolved with `command -v` in the exact tier the shell will use (blind File checks reported working tools as missing), a failed probe reports presence UNKNOWN ("redeploying…") instead of "missing: everything", and the GitHub section reports real auth state plus the shebang and HTTPS-transport limits agents need to know.
- **doctor accuracy** — the insteadOf check queried the wrong config key (always reported ABSENT) and the free-plan listing sent type=private together with affiliation=owner, which GitHub rejects with 422 ("state unknown"); both probes are deterministic now.
- **Toolchain repair** — Termux-absolute shebangs are rewritten into the deployed prefix instead of dropping the scripts (python/pip/npm stop vanishing from existing installs), interrupted installs resume and packages whose binaries vanished are reinstalled at startup, installs are serialized against marker races, Termux-absolute symlinks are rewritten as relative links, and dead Android-bridge shim scripts (pm/cmd/am…) are purged so they stop shadowing system binaries.
- **git tools on foreign-owned and non-repo workspaces** — every git invocation carries `-c safe.directory=*` so repositories owned by another uid stop failing with "dubious ownership" (persisted into ~/.gitconfig once as a fallback), `git_status`/`git_diff`/`git_commit` auto-init non-repo workspaces and retry, and git_commit no longer sweeps `.harness/` runtime artifacts into commits.
- **Raw tokens leaked into the chat DB inside URLs** — the redactor caught header-style secrets but missed tokens embedded in clone URLs printed by GIT_CURL_VERBOSE. It now strips `ghp_/gho_/ghu_/ghs_/ghr_` and `github_pat_` tokens anywhere, the `user:password@` userinfo of https URLs, and long token-shaped values behind credential-ish keys.
- **On-device QA batch** — atomic apply_patch (a failed hunk writes nothing), per-session background-process log isolation, case-collision warnings before creating names that alias on case-insensitive mounts, read_file strips UTF-8 BOMs, file_info streams procfs files instead of trusting st_size 0, write_file preserves interior CR bytes (SSE line framing no longer tears CRLF content), and the asstest skill is added for harness self-checks.

## 0.3-alpha (2026-08-27)

### Added
- **In-app updater** — checks GitHub Releases automatically on launch and manually from Settings → Updates. When a newer release is published, a dialog shows the release notes with every markdown link (and bare URLs) tappable, so changelog.md-style bodies open in the browser. Update installs silently through Shizuku (`pm install -r`) when granted, otherwise it hands the APK to the system installer with an unknown-sources retry path. Download shows animated MB/percent progress. Version matching understands Alpha-v0.3-style tags.
- **Hermes-style global thinking ladder** — one canonical scale on every model: Off, Minimal, Low, Medium, High, X-High, Max, Ultra. The raw pick is stored verbatim and resolved at request time: non-native rungs fall down the chain (ultra → max → xhigh → high …) to the closest tier the model supports, floor fallback keeps an enabled ask enabled, and a stronger request never silently bills less than a weaker one.
- **Slash plan** — typing `/plan [instruction]` flips the header into Plan mode automatically AND loads the bundled planning skill into the run prompt (built-in fallback instructions when no plan skill is installed).
- **Stats screen sort + filter** — per-model attribution can be ordered by tokens, estimated price, or request count; price estimates render on every row.
- **file_info tool** for subagents: byte/newline inspection without loading content.
- Active running indicator in sidebar chats, thinking-level badge with model switcher in the header, universal B/M/K token formatting.

### Fixed


- **Timeout process-group leaks and zombie accumulation**: setsid execution plus descendant-tree killing, PR_SET_CHILD_SUBREAPER reparenting, waitpid reaping, Shizuku-shell procfd scanning for orphans.
- **Shell sandbox escapes** (Bug G family): variable/command substitution bypasses ($PWD/../, $(echo ...), $'\x2e\x2e') are blocked; echo/printf/grep literal patterns remain allowed.
- **Background process handling**: logs materialize under .harness/bg in the workspace, all shell redirections handled, fsync on file writes, git index lock backoff retries, cleaner stderr parsing.
- **Prompt caching**: deterministic schema sorting, Anthropic 4-point cache breakpoints, OpenRouter ephemeral cache control, session cluster user affinity, prompt_cache_key for remote hosts, full OpenAI/RunInfra caching JSON field variants, richer cache token extraction across providers.
- **Cost tracking**: exact multi-model cost sums and breakdowns per session, live model costs fetched from the models.dev catalog.
- **Crashes**: IllegalStateException on launch when the Shizuku client is unattached; LazyColumn duplicate-key crash when streaming commits to a message.
- UI polish: static top-left picker/title without running/writing flashes, thinking badge repositioned next to the context button, turn-update helper text, streamlined cache-status calculation.

### Changed

- Thinking level selection removed from the model picker — it lives only in the header badge menus, which offer the full ladder for every model without fallback captions.
- README credits inspirations: Hermes Agent (Nous Research), pi by Mario Zechner, and OpenCode.
