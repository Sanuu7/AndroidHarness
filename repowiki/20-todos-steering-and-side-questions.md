# Todos, Steering, and Side Questions

> Tracks the agent's live task list, controls what happens to messages typed while a run is working, and answers `/btw` questions on the running task's cached prefix without touching history.

Three mechanisms share a common theme: work that happens alongside the main turn. `TodoStore` holds a session-owned task list that `TodoWriteTool` replaces and `TodoPrompt` injects into every round. `SteeringMode` and `SteeringInbox` decide whether a message typed during a run is read at the agent's next step or queued for a new turn. `SideQuestions.kt` and the `/btw` sheet answer a quick question over the exact request the main loop last sent, so the provider can serve it from cache.

## Module Responsibilities

- **`TodoStore`** (`agent/TodoStore.kt`): app-global `StateFlow` of `TodoItem` plus an owner session id. `beginRun` clears the list and claims ownership, so a finished list never bleeds into the next prompt or another chat.
- **`TodoPrompt`** (`agent/TodoPrompt.kt`): renders the list for the system prompt and appends a stale-round progress check.
- **`TodoWriteTool`** (`tools/MoreTools.kt`): the `todo_write` tool. It replaces the whole list and returns completed and total counts.
- **`SteeringMode`** (`data/SteeringMode.kt`): `AH_AUTO` reads at the next step; `AFTER_TASK` waits for a new turn. `fromStored` defaults to `AH_AUTO`.
- **`Steering`** (`agent/Steering.kt`): id prefix, detection, and the `<steering_message>` wrapper applied to the model view only.
- **`SideContext` / `SideContexts`** (`agent/SideQuestions.kt`): the captured main-loop request and a 4-entry LRU holder per chat.
- **`SideQuestion`**: matching and prompt rules, pending-call closing, warm and cold message assembly.
- **`AgentEngine.askAside` / `streamAside`**: the read-only side loop, up to six model rounds.
- **`SideQuestionState` and `SideQuestionSheet`**: in-memory UI state, reducer, and the sheet UI.

## Architecture and Flow

```mermaid
flowchart TD
    A[User types while run is busy] --> B[ChatViewModel.send]
    B --> C[RunManager.inject: append QueuedPrompt]
    C --> D[TaskRecord.queue persisted to JSON]
    D --> E{steeringMode}
    E -->|AH_AUTO| F[steeringInbox.take at next iteration]
    F --> G[Save message id steer-* to chat, remove from queue]
    G --> H[AgentEngine wraps in steering_message and emits UserMessageInjected]
    E -->|AFTER_TASK| I[RunFollowUps.next after run finishes]
    I --> J[startRun with queuedPromptId]
    K[/btw text] --> L[ChatViewModel.askAside]
    L --> M{Warm SideContext?}
    M -->|yes| N[Replay exact prefix plus question]
    M -->|no| O[coldSideContext: transcript, no tools]
    N --> P[streamAside, max 6 rounds, read-only]
    O --> P
    P --> Q[SideQuestionReducer updates sheet only]
```

- Todo path: `TaskProgressCard` and `TodoCard` render `state.todos`; the engine reads the store each round to rebuild the system prompt.
- Steering path: the queue is durable before any follow-up decision, and the steering inbox joins pending writes before reading it (`agent/RunManager.kt` lines 1180-1193).
- Side path: `publishResults` refreshes the warm `SideContext` as tool results land, so a question opened mid-round sees finished results (`agent/AgentEngine.kt` lines 509-512, 789).

## Invocation Chain

1. `ChatViewModel.send` short-circuits `/btw` before any queue logic (`ui/chat/ChatViewModel.kt` lines 816-818).
2. When the chat is running, `send` dispatches the expanded text through `runManager.inject(sid, target.text)` (`ui/chat/ChatViewModel.kt` lines 921-930).
3. `inject` appends a `QueuedPrompt` and records the write job in `queueWrites` (`agent/RunManager.kt` lines 1204-1216).
4. In `AH_AUTO`, `RunManager` builds a `SteeringInbox` that saves each message as `steer-<prompt id>` with the current `turnId`, removes those ids from the queue, and returns the messages (`agent/RunManager.kt` lines 1180-1193).
5. `AgentEngine` calls `takeSteering()` after the first iteration and again when a round ends with no tool calls (`agent/AgentEngine.kt` lines 430, 768). Each message becomes `Steering.forModel(message)` plus a `UserMessageInjected` event.
6. After a run ends, `RunFollowUps.next` returns `Action.Send(queue.first())` when the run finished normally and the chat is idle, and `startRunLocked` starts the next turn with `queuedPromptId` (`agent/RunFollowUps.kt` lines 30-46; `agent/RunManager.kt` lines 738-748).
7. For `/btw`, `askAside` obtains `runManager.sideContext(sid)` or builds a cold context, then streams through `runManager.askAside` into `SideQuestionReducer` (`ui/chat/ChatViewModel.kt` lines 1087-1148).

## Key State and Data Structures

- **`TodoItem`**: `content` plus `Status` of `PENDING`, `IN_PROGRESS`, `COMPLETED` (`agent/TodoStore.kt` lines 8-15).
- **`TodoStore`**: `todos: StateFlow<List<TodoItem>>`, `owner: StateFlow<String?>`. `beginRun(sessionId)` sets both; `setAll` replaces the list (`agent/TodoStore.kt` lines 25-40).
- **`QueuedPrompt`**: `id` (random UUID) and `text` (`agent/TaskControl.kt` line 17). `TaskRecord.queue` holds the list and is written atomically to `<sessionId>.json` with a `.tmp`, `fd.sync()`, rename sequence (`TaskControl.kt` lines 49, 80-90).
- **`SideContext`**: `sessionId`, `turnId`, `config`, `apiKey`, `systemPrompt`, `tools`, `messages`, `options`, `registry`, `workspace`, `sandboxOff`, `permissionMode`, `runContext`, `warm`, `capturedAt` (`agent/SideQuestions.kt` lines 19-37).
- **`SideContexts`**: access-ordered `LinkedHashMap` with capacity 4, so image-bearing messages cannot accumulate (`agent/SideQuestions.kt` lines 40-48).
- **`SideQuestionUi`**: question, running, committed messages, streaming text and thinking, running calls, usage rows, warm flag, model, timestamps, retrying, notice, error, stopped (`ui/chat/SideQuestionState.kt` lines 13-41).

## Todo Prompting and Stale-Round Reminders

The system prompt receives the list only for Code chats where the store owner matches the session (`agent/AgentEngine.kt` lines 472-475). `TodoPrompt.format` emits `- [status] content` lines. When `roundsWithoutUpdate >= 3` and any item is not completed, it appends a progress check that asks for an update before more work and warns not to mark unverified work complete (`agent/TodoPrompt.kt` lines 5-19).

`roundsWithoutTodoUpdate` starts at zero (`agent/AgentEngine.kt` line 396). After a round, a successful `todo_write` resets it; any other call that is not `todo_write` increments it (`agent/AgentEngine.kt` lines 857-858). The counter lives only for the run. The base rules also instruct the model to mark the current task in progress before starting or delegating and never to defer updates to the final answer (`agent/AgentEngine.kt` lines 2232).

`TodoWriteTool` accepts `todos` as either a JSON array value or a JSON string, tries strict parsing first, and falls back to regex pairing of `content` and `status` when commas are missing. Status aliases include `complete`, `done`, `finished`, `inprogress`, `active`, `started`, `working`, `doing`, `waiting`, and `todo` (`tools/MoreTools.kt` lines 587-668). The UI shows the card only in Code chats (`ui/chat/ChatScreen.kt` lines 2437-2438), and `ChatViewModel` filters by owner to avoid showing another chat's list (`ui/chat/ChatViewModel.kt` lines 420-426).

## Queue Semantics

- Messages typed during a run always enter the queue. The mode only changes who consumes them.
- `AH_AUTO` consumes at tool-round boundaries. The row is written to chat before it leaves the queue, so the transcript and the queue cannot both hold it.
- `AFTER_TASK` leaves the queue untouched. The next turn starts after the run finishes, one queued prompt per run.
- A crash between save and dequeue is repaired by `dropDeliveredSteering`, which removes queue entries whose `steer-` id already exists in chat (`agent/RunManager.kt` lines 1196-1202).
- The queue card supports expand, edit, move up or down, remove, and Send now, and shows a "read at next step" badge only when the chat is busy in `AH_AUTO` (`ui/chat/TaskControls.kt` lines 85-120; `ui/chat/ChatScreen.kt` line 2469).
- `sendQueuedNow` can stop the run and immediately start the selected prompt; `cancelQueued` clears the whole queue (`ui/chat/ChatViewModel.kt` lines 942-963; `agent/RunManager.kt` lines 1218-1234).

## /btw Replay and Cache Behavior

A warm context is the request the main loop last sent: same system prompt, tool schemas, message list, `RequestOptions` (including `cacheKey = sessionId`), workspace, and permission mode. `SideQuestion.requestMessages` closes any open tool calls with a `PENDING_RESULT` placeholder so the request is valid, then appends the `<side_question>` user message (`agent/SideQuestions.kt` lines 93-127). The prefix before the pending calls is byte-identical, so the provider cache stays warm.

A cold context is used when the process no longer holds a live request. `RunManager.coldSideContext` rebuilds a transcript from saved history, drops subagent turns, sets `COLD_SYSTEM_PROMPT`, exposes no tools, and marks `warm = false` (`agent/RunManager.kt` lines 1415-1443). `SideQuestion.coldMessages` streams fixed 16,000-character chunks and keeps only the newest that fit the character budget, derived from `(maxContextTokens - outputTokens - 2048) * 3` and clamped to 8,000 through 600,000 (`agent/SideQuestions.kt` lines 129-151).

On a warm context the question is appended; on a cold one it is merged into the last user message to avoid two consecutive user turns (`agent/SideQuestions.kt` lines 120-127). `streamAside` allows up to `MAX_ROUNDS = 6` requests including the final answer. On the last round it appends `ROUND_LIMIT_NOTE` and refuses tool execution (`agent/SideQuestions.kt` line 67; `agent/AgentEngine.kt` lines 902-905, 990-994).

Nothing is persisted except usage. `RunManager.askAside` records usage under `SIDE_TURN_PREFIX = "btw:"` plus a UUID, so the main turn's cache footer is untouched (`agent/RunManager.kt` lines 1445-1462, 1580). The sheet is ephemeral: `dismissAside` cancels the job and sets the state to null, and the reducer keeps text that was streaming when the run ended as the answer (`ui/chat/SideQuestionState.kt` lines 68-78).

## Boundary Conditions

- `SideQuestion.allows` requires `isReadOnly` and excludes `ask_user`, `create_artifact`, `offer_code_handoff`, `visual_guide`, `doctor`, `http_request`, `github_api`, `codegraph_sync`, `task`, `todo_write`, and every `subagent_*` tool, plus all `browser_*` and `mcp__*` tools (`agent/SideQuestions.kt` lines 77-84).
- A refused tool returns the standard message telling the model to answer from context or suggest the main chat (`agent/SideQuestions.kt` lines 86-88).
- A local model provider refuses `/btw` while its chat is running (`agent/RunManager.kt` lines 1449-1451).
- `askAside` with blank text opens an empty sheet instead of starting a request (`ui/chat/ChatViewModel.kt` lines 1095-1101).
- Todo writes are blocked in Plan mode by the generic non-read-only guard; the UI still updates only when the call succeeds.
- Queue writes are joined before follow-up decisions, so a message accepted at the moment of completion is not lost (`agent/RunManager.kt` lines 733-737).

## Extension Points

- Settings: `steering_mode` (`AH_AUTO` or `AFTER_TASK`) and the `/btw`-specific UI strings and actions (`data/SettingsRepository.kt` lines 163, 456, 545).
- `SteeringInbox` is a `fun interface`; the engine accepts any implementation through its `steering` parameter (`agent/Steering.kt` lines 11-13; `agent/AgentEngine.kt` lines 278-279).
- `TodoStore` is injected into `RunManager` and `AgentEngine`, so alternative stores can be supplied in tests.
- `SideQuestionSheet` receives plain callbacks (`onAsk`, `onStop`, `onDismiss`, `onOpenUrl`), so another surface can host the same state machine.

Sources: [app/src/main/java/com/androidharness/app/agent/SideQuestions.kt](../app/src/main/java/com/androidharness/app/agent/SideQuestions.kt)#L19-L151)

## Source files

- `app/src/main/java/com/androidharness/app/agent/TodoStore.kt`
- `app/src/main/java/com/androidharness/app/agent/TodoPrompt.kt`
- `app/src/main/java/com/androidharness/app/tools/MoreTools.kt`
- `app/src/main/java/com/androidharness/app/agent/Steering.kt`
- `app/src/main/java/com/androidharness/app/data/SteeringMode.kt`
- `app/src/main/java/com/androidharness/app/agent/SideQuestions.kt`
- `app/src/main/java/com/androidharness/app/agent/AgentEngine.kt`
- `app/src/main/java/com/androidharness/app/agent/RunManager.kt`
- `app/src/main/java/com/androidharness/app/agent/RunFollowUps.kt`
- `app/src/main/java/com/androidharness/app/agent/TaskControl.kt`
- `app/src/main/java/com/androidharness/app/ui/chat/SideQuestionState.kt`
- `app/src/main/java/com/androidharness/app/ui/chat/components/SideQuestionSheet.kt`
- `app/src/main/java/com/androidharness/app/ui/chat/ChatViewModel.kt`
- `app/src/main/java/com/androidharness/app/ui/chat/TaskControls.kt`
