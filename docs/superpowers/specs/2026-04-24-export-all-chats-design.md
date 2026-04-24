# Bulk Chat Export Design

## Goal
Let users export one or many chat histories from the chat list using the existing selection-mode UX, producing a shareable archive alongside the current single-chat export.

## Requirements
- From the chat list, a user can enter selection mode (long-press, existing behaviour), select any number of chats, and export them.
- A "select all" action is available during selection mode.
- Exporting exactly one chat produces a single `.md` whose format is identical to the current in-chat Export Chat output.
- Exporting two or more chats produces a `.zip` containing one `.md` per selected chat.
- Sharing uses the existing Android share-sheet flow via `FileProvider`, matching today's single-chat export.
- Failures surface via a toast, matching the existing export failure path.
- Exported content is limited to text (message `content`), matching today's single-chat export. Attachments are not embedded.
- The markdown produced for a given chat must be byte-identical whether the user exported it from the chat screen or from the chat list, so both code paths share the same formatter.

## Recommended Architecture
Extract the markdown builder out of `ChatViewModel.exportChat()` into a shared utility, add a bulk export path on `HomeViewModel`, and wire a shared share-artifact helper used by both screens.

### Shared markdown builder
Introduce `util/ChatMarkdownExporter.kt` exposing a pure function taking a `ChatRoomV2`, its `List<MessageV2>` grouped into user/assistant turns, and the app-wide `List<PlatformV2>` for platform-name resolution. It returns the markdown string.

`ChatViewModel.exportChat()` is refactored to delegate to this function using its existing grouped message state, producing the same output it does today.

### Filename helpers
Introduce `util/ExportFilenames.kt` exposing:
- `sanitizeChatTitle(title: String): String` — strips or replaces `/ \ : * ? " < > |`, control characters, and trailing dots/spaces; substitutes Windows reserved names (`CON`, `PRN`, `AUX`, `NUL`, `COM1..9`, `LPT1..9`); caps length to a reasonable base-name bound; falls back to `chat` when the result is empty.
- `buildChatFileNames(chats: List<ChatRoomV2>): Map<Int, String>` — assigns a unique `<safeTitle>.md` per chat id, disambiguating collisions by appending `_<chatId>` before the extension.
- `buildArchiveFileName(now: LocalDateTime): String` — returns `gpt_mobile_chats_YYYYMMDD_HHmmss.zip`.

The existing single-chat export adopts `sanitizeChatTitle` for the file it emits today, making the current filename handling safer as a side effect.

### Bulk export on `HomeViewModel`
Introduce a small data class:

```
data class ExportArtifact(val fileName: String, val bytes: ByteArray, val mimeType: String)
```

Add `suspend fun exportSelectedChats(): ExportArtifact?` with this behaviour:
- Returns `null` if no chats are selected.
- Resolves platform names from `platformState` (already loaded).
- For each selected chat, calls `chatRepository.fetchMessagesV2(chatId)` and builds markdown via `ChatMarkdownExporter`.
- If exactly one chat is selected, returns an artifact with the markdown's UTF-8 bytes and `text/markdown`.
- If two or more are selected, assembles a zip using `java.util.zip.ZipOutputStream` on `Dispatchers.IO`, writing each chat's markdown as a flat entry named per `buildChatFileNames`, and returns an artifact with the zip bytes and `application/zip`.
- On success, leaves selection mode via the existing `disableSelectionMode()`.

No `Context` is injected into the ViewModel; the screen handles file writing and sharing.

### Shared share helper
Extract the current `exportChat(context, chatViewModel)` file-write + share-intent logic in `ChatScreen.kt` into `presentation/common/ShareExportedChat.kt`:

```
fun shareExport(context: Context, artifact: ExportArtifact)
```

Both `ChatScreen` and `HomeScreen` use this helper. `ChatViewModel.exportChat()` is refactored to return an `ExportArtifact` with `text/markdown`, so the chat-screen path stays behaviourally identical.

### Chat-list wiring
`HomeScreen` gains two new actions in the selection-mode top bar, left of the existing Delete icon:
- **Select All** (`Icons.Outlined.SelectAll`): always shown while `isSelectionMode`. Tapping toggles between all-selected and none-selected. When any unselected chats exist, it selects all; otherwise it clears the selection.
- **Export** (`Icons.Outlined.FileDownload`): shown whenever `selectedChats >= 1`. Tapping calls the VM's bulk export in a `rememberCoroutineScope()` launched coroutine, then hands the resulting artifact to the shared share helper.

Duplicate's existing rule (shown only when exactly one chat is selected) is unchanged.

### State updates
Add to `HomeViewModel`:
- `selectAllChats()` — sets all `selectedChats` to `true` if any are `false`, otherwise calls `disableSelectionMode()`.
- `exportSelectedChats(): ExportArtifact?` described above.

## User Experience
- Long-press a chat (existing) → chat list enters selection mode.
- Optional: tap Select All to grab every chat.
- Tap Export. A share sheet opens for the produced artifact, and selection mode exits.
- On failure, a toast says "Failed to export chats" and selection mode stays active so the user can retry. This matches today's single-chat export, which toasts on failure only and has no explicit success toast (the share sheet itself is the confirmation).
- Exporting a single chat from the list produces the same `.md` as today's in-chat Export Chat, so the single-chat flow is externally unchanged.

## Internationalization
Add English strings to `app/src/main/res/values/strings.xml`:
- `select_all`
- `export_chats` (icon content description / action label)
- `export_chats_failed` (failure toast)

Non-English locales are updated by translators in follow-up PRs, matching how prior feature strings like `duplicated_chat` and `chats_selected` were introduced.

## Testing
JUnit 4 unit tests matching `FileUtilsTest` style, under `app/src/test/kotlin/.../util/`:

`ChatMarkdownExporterTest`
- header content includes the chat title and an exported-on timestamp
- user and assistant turns render with correct labels
- multi-platform assistant turns emit one assistant block per platform with the platform name resolved from the provided `List<PlatformV2>`
- a chat with no messages produces header-only output
- platform name unknown to the list falls back to `Unknown`

`ExportFilenamesTest`
- sanitization replaces illegal characters, trims trailing dots/spaces, handles Windows reserved names, and truncates long titles
- empty or whitespace-only titles fall back to `chat`
- `buildChatFileNames` disambiguates collisions by appending `_<chatId>`
- `buildArchiveFileName` formats the timestamp as expected

`ChatBulkZipTest` (new, or folded into `ChatMarkdownExporterTest`)
- zipping N chats produces a zip with N entries, each entry name comes from `buildChatFileNames`, and each entry body matches the markdown builder output for that chat.

No new instrumented tests.

## Out of Scope
- Embedding attachments or images in the export (today's single-chat export also skips these; adding them is a larger format change).
- JSON or machine-readable backup format / restore / import.
- Progress UI for large selections. Typical chat counts are tens to low hundreds; a toast on completion suffices.
- Changes to the in-chat Export Chat flow beyond delegating to the shared markdown builder and adopting safer filenames.
