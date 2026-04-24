# Bulk Chat Export Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users export one or many chats from the chat list using the existing selection-mode UX — a single `.md` when one chat is selected, a `.zip` of `.md`s when two or more are selected.

**Architecture:** Extract the markdown-building logic that today lives inline in `ChatViewModel.exportChat()` into pure utility functions. Reuse those utilities for both the existing in-chat Export Chat path and a new bulk export path on `HomeViewModel`. Share the file-write + share-intent plumbing between the chat screen and the chat list via a small helper in `presentation/common/`.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Room, coroutines, JUnit 4, ktlint 1.3.1, `java.util.zip`, `androidx.compose.material:material-icons-extended`.

---

## File Structure

### New files

| Path | Responsibility |
|---|---|
| `app/src/main/kotlin/dev/chungjungsoo/gptmobile/util/ExportFilenames.kt` | Pure functions for sanitizing chat titles into safe file names, disambiguating duplicates, and formatting the zip archive name. |
| `app/src/main/kotlin/dev/chungjungsoo/gptmobile/util/ChatMarkdownExporter.kt` | Pure functions: build per-chat markdown from a `ChatRoomV2` + grouped messages + platform list, and zip multiple markdown entries to a `ByteArray`. |
| `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/common/ExportArtifact.kt` | Thin data class carrying `(fileName, bytes, mimeType)` between ViewModel and screen. |
| `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/common/ChatExportShare.kt` | `shareExport(context, artifact)` — writes bytes to `context.getExternalFilesDir(null)` and fires the existing share-chooser intent. |
| `app/src/test/kotlin/dev/chungjungsoo/gptmobile/util/ExportFilenamesTest.kt` | Unit tests for `ExportFilenames`. |
| `app/src/test/kotlin/dev/chungjungsoo/gptmobile/util/ChatMarkdownExporterTest.kt` | Unit tests for markdown building and zip assembly. |

### Modified files

| Path | Change |
|---|---|
| `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatViewModel.kt` | Replace the inline markdown string builder in `exportChat()` with a call into `ChatMarkdownExporter`; change return type from `Pair<String, String>` to `ExportArtifact`. |
| `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatScreen.kt` | Remove the private `exportChat(context, chatViewModel)` helper and call the shared `shareExport(context, artifact)` helper instead. |
| `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/home/HomeViewModel.kt` | Add `selectAllChats()` and `suspend fun exportSelectedChats(): ExportArtifact?`. |
| `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/home/HomeScreen.kt` | In selection-mode `actions`, add a `SelectAll` icon (always visible in selection mode) and a `FileDownload` icon (visible whenever ≥ 1 chat is selected). Wire both to the ViewModel and the shared share helper. |
| `app/src/main/res/values/strings.xml` | Add `select_all`, `export_chats`, and `export_chats_failed`. |

---

## Git / Commit Conventions

- Each task commits on its own.
- Commit messages follow the upstream style observed on `main` (`feat:`, `refactor:`, `test:`, `docs:`, etc.).
- Commit with HEREDOC-style messages (see `.claude/contributing.md`).
- Co-author the commit: `Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>`.
- The pre-commit hook runs ktlint on staged Kotlin — do not bypass with `--no-verify`.

Before pushing to upstream, the spec commit (`docs/superpowers/specs/...`) and plan commit (`docs/superpowers/plans/...`) must be dropped. That step is covered by `.claude/contributing.md` and is out of scope for this plan.

---

## Task 1: `ExportFilenames` utility + tests

**Files:**
- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/util/ExportFilenames.kt`
- Test: `app/src/test/kotlin/dev/chungjungsoo/gptmobile/util/ExportFilenamesTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/dev/chungjungsoo/gptmobile/util/ExportFilenamesTest.kt`:

```kotlin
package dev.chungjungsoo.gptmobile.util

import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import org.junit.Assert.assertEquals
import org.junit.Test

class ExportFilenamesTest {

    @Test
    fun `illegal filename characters are replaced with underscore`() {
        assertEquals("a_b_c", ExportFilenames.sanitizeChatTitle("a/b:c"))
        assertEquals("a_b_c", ExportFilenames.sanitizeChatTitle("a*b?c"))
        assertEquals("a_b_c_d", ExportFilenames.sanitizeChatTitle("a<b>c|d"))
        assertEquals("a_b", ExportFilenames.sanitizeChatTitle("a\\b"))
        assertEquals("a_b", ExportFilenames.sanitizeChatTitle("a\"b"))
    }

    @Test
    fun `control characters are stripped`() {
        assertEquals("hello world", ExportFilenames.sanitizeChatTitle("hello world"))
    }

    @Test
    fun `trailing dots and spaces are trimmed`() {
        assertEquals("title", ExportFilenames.sanitizeChatTitle("title..."))
        assertEquals("title", ExportFilenames.sanitizeChatTitle("title   "))
        assertEquals("title", ExportFilenames.sanitizeChatTitle("title. "))
    }

    @Test
    fun `windows reserved names get a trailing underscore`() {
        assertEquals("CON_", ExportFilenames.sanitizeChatTitle("CON"))
        assertEquals("PRN_", ExportFilenames.sanitizeChatTitle("prn"))
        assertEquals("COM1_", ExportFilenames.sanitizeChatTitle("COM1"))
        assertEquals("LPT9_", ExportFilenames.sanitizeChatTitle("LPT9"))
    }

    @Test
    fun `empty or whitespace only titles fall back to chat`() {
        assertEquals("chat", ExportFilenames.sanitizeChatTitle(""))
        assertEquals("chat", ExportFilenames.sanitizeChatTitle("   "))
        assertEquals("chat", ExportFilenames.sanitizeChatTitle("..."))
    }

    @Test
    fun `very long titles are truncated to the configured cap`() {
        val long = "a".repeat(500)
        val sanitized = ExportFilenames.sanitizeChatTitle(long)
        assertEquals(ExportFilenames.MAX_TITLE_LENGTH, sanitized.length)
    }

    @Test
    fun `buildChatFileNames assigns unique names when titles differ`() {
        val chats = listOf(
            chat(id = 1, title = "Hello"),
            chat(id = 2, title = "World")
        )

        val names = ExportFilenames.buildChatFileNames(chats)

        assertEquals("Hello.md", names[1])
        assertEquals("World.md", names[2])
    }

    @Test
    fun `buildChatFileNames disambiguates duplicate titles by chat id`() {
        val chats = listOf(
            chat(id = 1, title = "Hello"),
            chat(id = 2, title = "Hello"),
            chat(id = 7, title = "Hello")
        )

        val names = ExportFilenames.buildChatFileNames(chats)

        assertEquals("Hello_1.md", names[1])
        assertEquals("Hello_2.md", names[2])
        assertEquals("Hello_7.md", names[7])
    }

    @Test
    fun `buildChatFileNames disambiguates when sanitized forms collide`() {
        val chats = listOf(
            chat(id = 1, title = "a/b"),
            chat(id = 2, title = "a:b")
        )

        val names = ExportFilenames.buildChatFileNames(chats)

        assertEquals("a_b_1.md", names[1])
        assertEquals("a_b_2.md", names[2])
    }

    @Test
    fun `buildSingleChatFileName uses export prefix and timestamp`() {
        val chat = chat(id = 1, title = "Hello")
        assertEquals("export_Hello_1714000000000.md", ExportFilenames.buildSingleChatFileName(chat, 1714000000000L))
    }

    @Test
    fun `buildArchiveFileName formats the timestamp`() {
        val millis = 1745478423000L // 2025-04-24 05:47:03 UTC
        val name = ExportFilenames.buildArchiveFileName(millis, java.util.TimeZone.getTimeZone("UTC"))
        assertEquals("gpt_mobile_chats_20250424_054703.zip", name)
    }

    private fun chat(id: Int, title: String): ChatRoomV2 =
        ChatRoomV2(id = id, title = title, enabledPlatform = emptyList())
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "dev.chungjungsoo.gptmobile.util.ExportFilenamesTest"`

Expected: FAIL — `ExportFilenames` symbol does not resolve.

- [ ] **Step 3: Implement `ExportFilenames`**

Create `app/src/main/kotlin/dev/chungjungsoo/gptmobile/util/ExportFilenames.kt`:

```kotlin
package dev.chungjungsoo.gptmobile.util

import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object ExportFilenames {

    const val MAX_TITLE_LENGTH = 100

    private val illegalChars = Regex("""[\\/:*?"<>|]""")
    private val controlChars = Regex("""\p{Cntrl}""")
    private val windowsReservedNames = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    )

    fun sanitizeChatTitle(title: String): String {
        val stripped = title
            .replace(controlChars, "")
            .replace(illegalChars, "_")
            .trimEnd { it == '.' || it.isWhitespace() }
            .trim()

        val capped = if (stripped.length > MAX_TITLE_LENGTH) stripped.take(MAX_TITLE_LENGTH) else stripped

        if (capped.isBlank()) return "chat"

        return if (windowsReservedNames.contains(capped.uppercase(Locale.ROOT))) "${capped}_" else capped
    }

    fun buildChatFileNames(chats: List<ChatRoomV2>): Map<Int, String> {
        val byBase = chats.groupBy { sanitizeChatTitle(it.title) }

        return chats.associate { chat ->
            val base = sanitizeChatTitle(chat.title)
            val name = if ((byBase[base]?.size ?: 0) > 1) "${base}_${chat.id}.md" else "$base.md"
            chat.id to name
        }
    }

    fun buildSingleChatFileName(chat: ChatRoomV2, nowMillis: Long): String =
        "export_${sanitizeChatTitle(chat.title)}_$nowMillis.md"

    fun buildArchiveFileName(nowMillis: Long, timeZone: TimeZone = TimeZone.getDefault()): String {
        val format = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).apply { this.timeZone = timeZone }
        return "gpt_mobile_chats_${format.format(Date(nowMillis))}.zip"
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "dev.chungjungsoo.gptmobile.util.ExportFilenamesTest"`

Expected: PASS — all 10 tests green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/chungjungsoo/gptmobile/util/ExportFilenames.kt \
        app/src/test/kotlin/dev/chungjungsoo/gptmobile/util/ExportFilenamesTest.kt

git commit -m "$(cat <<'EOF'
feat(util): add export filename helpers

Pure utilities to sanitize chat titles for filesystem safety,
disambiguate collisions across a chat list, and format the bulk
archive name. Used by both single-chat and bulk export paths.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: `ChatMarkdownExporter.buildMarkdown` + tests

**Files:**
- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/util/ChatMarkdownExporter.kt`
- Test: `app/src/test/kotlin/dev/chungjungsoo/gptmobile/util/ChatMarkdownExporterTest.kt`

The exporter's markdown format must match today's in-chat export byte-for-byte. That format (from `ChatViewModel.exportChat()` in the current `main`):

```
# Chat Export: "{title}"

**Exported on:** {exportedOn}

---

## Chat History

**User:**
{userContent}

**Assistant ({platformName}):**
{assistantContent}

```

One `User:` turn per entry in `userMessages`; one `Assistant ({platformName}):` block per assistant message in the parallel `assistantMessages[i]` list. Platform name resolves from the platform list via `getPlatformName(uid)` (existing util in `util/PlatformName.kt`); missing platforms fall back to `Unknown`.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/dev/chungjungsoo/gptmobile/util/ChatMarkdownExporterTest.kt`:

```kotlin
package dev.chungjungsoo.gptmobile.util

import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMarkdownExporterTest {

    @Test
    fun `header includes chat title and exported on timestamp`() {
        val md = ChatMarkdownExporter.buildMarkdown(
            chat = chat(title = "Trip planning"),
            userMessages = emptyList(),
            assistantMessages = emptyList(),
            platforms = emptyList(),
            exportedOn = "2026-04-24 09:07 AM"
        )

        assertTrue(md.contains("""# Chat Export: "Trip planning""""))
        assertTrue(md.contains("**Exported on:** 2026-04-24 09:07 AM"))
        assertTrue(md.contains("## Chat History"))
    }

    @Test
    fun `empty chat produces header only output`() {
        val md = ChatMarkdownExporter.buildMarkdown(
            chat = chat(title = "Untouched"),
            userMessages = emptyList(),
            assistantMessages = emptyList(),
            platforms = emptyList(),
            exportedOn = "2026-04-24 09:07 AM"
        )

        assertTrue(md.contains("""# Chat Export: "Untouched""""))
        assertTrue(md.contains("**Exported on:** 2026-04-24 09:07 AM"))
        assertTrue(md.contains("## Chat History"))
        assertFalse(md.contains("**User:"))
        assertFalse(md.contains("**Assistant"))
    }

    @Test
    fun `single user turn renders with one assistant block per platform`() {
        val openai = platform(uid = "openai-uid", name = "OpenAI")
        val anthropic = platform(uid = "anthropic-uid", name = "Anthropic")

        val md = ChatMarkdownExporter.buildMarkdown(
            chat = chat(title = "Multi"),
            userMessages = listOf(userMessage("Hi")),
            assistantMessages = listOf(
                listOf(
                    assistantMessage("Hello from OpenAI", "openai-uid"),
                    assistantMessage("Hello from Anthropic", "anthropic-uid")
                )
            ),
            platforms = listOf(openai, anthropic),
            exportedOn = "2026-04-24 09:07 AM"
        )

        assertTrue(md.contains("**User:**\nHi"))
        assertTrue(md.contains("**Assistant (OpenAI):**\nHello from OpenAI"))
        assertTrue(md.contains("**Assistant (Anthropic):**\nHello from Anthropic"))
    }

    @Test
    fun `unknown platform uid falls back to Unknown`() {
        val md = ChatMarkdownExporter.buildMarkdown(
            chat = chat(title = "Mystery"),
            userMessages = listOf(userMessage("Hi")),
            assistantMessages = listOf(
                listOf(assistantMessage("Whispers", "ghost-uid"))
            ),
            platforms = emptyList(),
            exportedOn = "2026-04-24 09:07 AM"
        )

        assertTrue(md.contains("**Assistant (Unknown):**"))
    }

    @Test
    fun `groupMessagesForExport splits user and assistant turns by createdAt`() {
        val messages = listOf(
            userMessage(id = 1, content = "Hi", createdAt = 10),
            assistantMessage(id = 2, content = "Hello", platformUid = "openai-uid", createdAt = 11),
            assistantMessage(id = 3, content = "Hey", platformUid = "anthropic-uid", createdAt = 12),
            userMessage(id = 4, content = "Next", createdAt = 20),
            assistantMessage(id = 5, content = "Sure", platformUid = "openai-uid", createdAt = 21)
        )

        val (users, assistants) = ChatMarkdownExporter.groupMessagesForExport(
            messages = messages,
            enabledPlatformOrder = listOf("openai-uid", "anthropic-uid")
        )

        assertEquals(listOf("Hi", "Next"), users.map { it.content })
        assertEquals(listOf("Hello", "Hey"), assistants[0].map { it.content })
        assertEquals(listOf("Sure"), assistants[1].map { it.content })
    }

    @Test
    fun `groupMessagesForExport orders assistants by enabled platform order`() {
        val messages = listOf(
            userMessage(id = 1, content = "Hi", createdAt = 10),
            assistantMessage(id = 2, content = "Claude", platformUid = "anthropic-uid", createdAt = 11),
            assistantMessage(id = 3, content = "GPT", platformUid = "openai-uid", createdAt = 12)
        )

        val (_, assistants) = ChatMarkdownExporter.groupMessagesForExport(
            messages = messages,
            enabledPlatformOrder = listOf("openai-uid", "anthropic-uid")
        )

        assertEquals(listOf("GPT", "Claude"), assistants[0].map { it.content })
    }

    @Test
    fun `groupMessagesForExport sorts by createdAt before grouping`() {
        val messages = listOf(
            assistantMessage(id = 2, content = "Hello", platformUid = "openai-uid", createdAt = 11),
            userMessage(id = 1, content = "Hi", createdAt = 10),
            userMessage(id = 3, content = "Next", createdAt = 20),
            assistantMessage(id = 4, content = "Sure", platformUid = "openai-uid", createdAt = 21)
        )

        val (users, assistants) = ChatMarkdownExporter.groupMessagesForExport(
            messages = messages,
            enabledPlatformOrder = listOf("openai-uid")
        )

        assertEquals(listOf("Hi", "Next"), users.map { it.content })
        assertEquals(listOf("Hello"), assistants[0].map { it.content })
        assertEquals(listOf("Sure"), assistants[1].map { it.content })
    }

    private fun chat(title: String): ChatRoomV2 =
        ChatRoomV2(id = 1, title = title, enabledPlatform = emptyList())

    private fun userMessage(content: String, id: Int = 0, createdAt: Long = 0): MessageV2 =
        MessageV2(id = id, chatId = 1, content = content, platformType = null, createdAt = createdAt)

    private fun assistantMessage(content: String, platformUid: String, id: Int = 0, createdAt: Long = 0): MessageV2 =
        MessageV2(id = id, chatId = 1, content = content, platformType = platformUid, createdAt = createdAt)

    private fun platform(uid: String, name: String): PlatformV2 =
        PlatformV2(uid = uid, name = name, compatibleType = ClientType.OPENAI, apiUrl = "", model = "")
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "dev.chungjungsoo.gptmobile.util.ChatMarkdownExporterTest"`

Expected: FAIL — `ChatMarkdownExporter` symbol does not resolve.

- [ ] **Step 3: Implement `ChatMarkdownExporter.buildMarkdown`**

Create `app/src/main/kotlin/dev/chungjungsoo/gptmobile/util/ChatMarkdownExporter.kt`:

```kotlin
package dev.chungjungsoo.gptmobile.util

import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2

object ChatMarkdownExporter {

    fun buildMarkdown(
        chat: ChatRoomV2,
        userMessages: List<MessageV2>,
        assistantMessages: List<List<MessageV2>>,
        platforms: List<PlatformV2>,
        exportedOn: String
    ): String = buildString {
        appendLine("# Chat Export: \"${chat.title}\"")
        appendLine()
        appendLine("**Exported on:** $exportedOn")
        appendLine()
        appendLine("---")
        appendLine()
        appendLine("## Chat History")
        appendLine()
        userMessages.forEachIndexed { i, user ->
            appendLine("**User:**")
            appendLine(user.content)
            appendLine()

            assistantMessages.getOrNull(i)?.forEach { assistant ->
                val name = assistant.platformType?.let { platforms.getPlatformName(it) } ?: "Unknown"
                appendLine("**Assistant ($name):**")
                appendLine(assistant.content)
                appendLine()
            }
        }
    }

    fun groupMessagesForExport(
        messages: List<MessageV2>,
        enabledPlatformOrder: List<String>
    ): Pair<List<MessageV2>, List<List<MessageV2>>> {
        val sorted = messages.sortedBy { it.createdAt }
        val platformOrder = enabledPlatformOrder.withIndex().associate { (idx, uuid) -> uuid to idx }

        val users = mutableListOf<MessageV2>()
        val assistants = mutableListOf<MutableList<MessageV2>>()

        sorted.forEach { message ->
            if (message.platformType == null) {
                users.add(message)
                assistants.add(mutableListOf())
            } else if (assistants.isNotEmpty()) {
                assistants.last().add(message)
            }
        }

        val orderedAssistants = assistants.map { group ->
            group.sortedWith(
                compareBy(
                    { platformOrder[it.platformType] ?: Int.MAX_VALUE },
                    { it.platformType }
                )
            )
        }

        return users to orderedAssistants
    }
}
```

The grouping logic mirrors `ChatViewModel.fetchGroupedMessages` (sort by `createdAt`, split on `platformType == null`, order each assistant group by the chat's enabled-platform list). Orphan assistant messages with no preceding user turn are dropped — matches the current chat-screen behaviour where such rows would never appear.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "dev.chungjungsoo.gptmobile.util.ChatMarkdownExporterTest"`

Expected: PASS — 4 tests green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/chungjungsoo/gptmobile/util/ChatMarkdownExporter.kt \
        app/src/test/kotlin/dev/chungjungsoo/gptmobile/util/ChatMarkdownExporterTest.kt

git commit -m "$(cat <<'EOF'
feat(util): add chat markdown exporter

Pure builder that produces the same markdown format as the
existing in-chat Export Chat path. Next commits move both the
chat screen and the new bulk export path onto this shared
formatter.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: `ChatMarkdownExporter.buildZip` + tests

**Files:**
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/util/ChatMarkdownExporter.kt`
- Modify: `app/src/test/kotlin/dev/chungjungsoo/gptmobile/util/ChatMarkdownExporterTest.kt`

`buildZip` takes a list of `(fileName, content)` entries and returns a zip as a `ByteArray`. Tests read the zip back with `ZipInputStream` and assert the entry names and contents.

- [ ] **Step 1: Extend the test file with zip tests**

Append to `app/src/test/kotlin/dev/chungjungsoo/gptmobile/util/ChatMarkdownExporterTest.kt` (below the existing tests, before the closing brace of the class):

```kotlin
    @Test
    fun `buildZip contains one entry per input with expected names and bodies`() {
        val bytes = ChatMarkdownExporter.buildZip(
            listOf(
                "alpha.md" to "alpha body",
                "beta.md" to "beta body"
            )
        )

        val readBack = readZipEntries(bytes)

        assertEquals(setOf("alpha.md", "beta.md"), readBack.keys)
        assertEquals("alpha body", readBack["alpha.md"])
        assertEquals("beta body", readBack["beta.md"])
    }

    @Test
    fun `buildZip preserves insertion order of entries`() {
        val bytes = ChatMarkdownExporter.buildZip(
            listOf(
                "zeta.md" to "z",
                "alpha.md" to "a"
            )
        )

        val order = readZipEntryOrder(bytes)

        assertEquals(listOf("zeta.md", "alpha.md"), order)
    }

    private fun readZipEntries(bytes: ByteArray): Map<String, String> {
        val out = mutableMapOf<String, String>()
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                out[entry.name] = zis.readBytes().toString(Charsets.UTF_8)
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return out
    }

    private fun readZipEntryOrder(bytes: ByteArray): List<String> {
        val out = mutableListOf<String>()
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                out.add(entry.name)
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return out
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "dev.chungjungsoo.gptmobile.util.ChatMarkdownExporterTest"`

Expected: FAIL — `buildZip` not defined.

- [ ] **Step 3: Implement `buildZip`**

Add to `app/src/main/kotlin/dev/chungjungsoo/gptmobile/util/ChatMarkdownExporter.kt` inside the `object ChatMarkdownExporter { ... }` body, below `buildMarkdown`:

```kotlin
    fun buildZip(entries: List<Pair<String, String>>): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(buffer).use { zos ->
            entries.forEach { (name, content) ->
                zos.putNextEntry(java.util.zip.ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return buffer.toByteArray()
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "dev.chungjungsoo.gptmobile.util.ChatMarkdownExporterTest"`

Expected: PASS — 6 tests green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/chungjungsoo/gptmobile/util/ChatMarkdownExporter.kt \
        app/src/test/kotlin/dev/chungjungsoo/gptmobile/util/ChatMarkdownExporterTest.kt

git commit -m "$(cat <<'EOF'
feat(util): add chat zip builder for bulk export

Wraps java.util.zip to produce a ByteArray archive from a list of
(filename, markdown) pairs. Caller is responsible for filename
uniqueness — see ExportFilenames.buildChatFileNames.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: `ExportArtifact` + `shareExport` helper

**Files:**
- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/common/ExportArtifact.kt`
- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/common/ChatExportShare.kt`

`shareExport` does exactly what `ChatScreen.exportChat(context, chatViewModel)` does today (write file to `getExternalFilesDir(null)`, grant URI permission, launch share chooser), but parameterized over `ExportArtifact` instead of hardcoded to markdown. There is no unit test for `shareExport`; it is exercised by downstream compilation and manual verification (Task 9).

- [ ] **Step 1: Create `ExportArtifact`**

Create `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/common/ExportArtifact.kt`:

```kotlin
package dev.chungjungsoo.gptmobile.presentation.common

data class ExportArtifact(
    val fileName: String,
    val bytes: ByteArray,
    val mimeType: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExportArtifact) return false
        return fileName == other.fileName &&
            mimeType == other.mimeType &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = fileName.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + mimeType.hashCode()
        return result
    }
}
```

The custom `equals` / `hashCode` is the idiomatic pattern for data classes that hold a `ByteArray`.

- [ ] **Step 2: Create `ChatExportShare`**

Create `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/common/ChatExportShare.kt`:

```kotlin
package dev.chungjungsoo.gptmobile.presentation.common

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

private const val TAG = "ChatExportShare"

fun shareExport(context: Context, artifact: ExportArtifact) {
    try {
        val file = File(context.getExternalFilesDir(null), artifact.fileName)
        file.writeBytes(artifact.bytes)

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = artifact.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(shareIntent, "Share Chat Export").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val resInfo = context.packageManager.queryIntentActivities(chooser, PackageManager.MATCH_DEFAULT_ONLY)
        resInfo.forEach { res ->
            context.grantUriPermission(res.activityInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(chooser)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to share export", e)
        throw e
    }
}
```

Rationale for rethrowing: callers already show context-appropriate toasts on failure (chat screen and chat list have different copy). Rethrow keeps the helper free of a `Context` dependency for string resources.

- [ ] **Step 3: Verify compilation**

Run: `./gradlew assembleDebug`

Expected: BUILD SUCCESSFUL. Nothing references the new files yet; the task is about making them available for Tasks 5 and 7.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/common/ExportArtifact.kt \
        app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/common/ChatExportShare.kt

git commit -m "$(cat <<'EOF'
feat(presentation): add ExportArtifact and share helper

Small data class and Android share-intent helper used by both the
chat screen's Export Chat menu item and the new chat-list bulk
export.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Refactor `ChatViewModel.exportChat` + `ChatScreen` to use the shared code

**Files:**
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatViewModel.kt`
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatScreen.kt`

Goal: the chat screen's Export Chat menu item produces the same content as today, with the markdown formatter now delegated to `ChatMarkdownExporter`, filename through `ExportFilenames`, and sharing through `shareExport`.

The two files must be changed in the same commit because `ChatScreen` consumes `ChatViewModel.exportChat`'s return type and that type is changing.

- [ ] **Step 1: Replace `ChatViewModel.exportChat`**

In `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatViewModel.kt`, replace the existing `exportChat` function (the one that returns `Pair<String, String>`) with:

```kotlin
    fun exportChat(): ExportArtifact {
        val markdown = ChatMarkdownExporter.buildMarkdown(
            chat = _chatRoom.value,
            userMessages = _groupedMessages.value.userMessages,
            assistantMessages = _groupedMessages.value.assistantMessages,
            platforms = _platformsInApp.value,
            exportedOn = formatCurrentDateTime()
        )

        return ExportArtifact(
            fileName = ExportFilenames.buildSingleChatFileName(_chatRoom.value, System.currentTimeMillis()),
            bytes = markdown.toByteArray(Charsets.UTF_8),
            mimeType = "text/markdown"
        )
    }
```

Add these imports near the top of `ChatViewModel.kt` (alphabetical order within the project-import group, per `AGENTS.md`):

```kotlin
import dev.chungjungsoo.gptmobile.presentation.common.ExportArtifact
import dev.chungjungsoo.gptmobile.util.ChatMarkdownExporter
import dev.chungjungsoo.gptmobile.util.ExportFilenames
```

Leave `formatCurrentDateTime()` in place — it is the source of the timestamp string that Task 2's tests asserted against.

- [ ] **Step 2: Replace `ChatScreen`'s private `exportChat` call site**

In `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatScreen.kt`:

- Remove the entire `private fun exportChat(context: Context, chatViewModel: ChatViewModel) { ... }` helper (lines `578`–`601` in current `main`).
- Replace the call site at line `183` from:

  ```kotlin
  onExportChatItemClick = { exportChat(context, chatViewModel) }
  ```

  to:

  ```kotlin
  onExportChatItemClick = {
      try {
          shareExport(context, chatViewModel.exportChat())
      } catch (e: Exception) {
          Toast.makeText(context, "Failed to export chat", Toast.LENGTH_SHORT).show()
      }
  }
  ```

- Add the import (project-import group):

  ```kotlin
  import dev.chungjungsoo.gptmobile.presentation.common.shareExport
  ```

- Run `./tools/ktlint.sh --format` (or the IDE's Optimize Imports) to drop any imports that became unused when the private `exportChat` helper was deleted. Likely candidates include `android.content.Intent`, `android.content.pm.PackageManager`, `android.util.Log`, `androidx.core.content.FileProvider.getUriForFile`, and `java.io.File`, but verify by greping the remaining file contents before removing any.

- [ ] **Step 3: Run the full unit-test suite**

Run: `./gradlew test`

Expected: PASS. The markdown produced by `exportChat()` is byte-equivalent to what `ChatMarkdownExporterTest` already covers.

- [ ] **Step 4: Run ktlint and build**

```bash
./tools/ktlint.sh --format
./gradlew assembleDebug
```

Expected: both succeed.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatViewModel.kt \
        app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatScreen.kt

git commit -m "$(cat <<'EOF'
refactor(chat): route single-chat export through shared helpers

ChatViewModel.exportChat now delegates to ChatMarkdownExporter and
returns an ExportArtifact; ChatScreen hands it straight to the
shared shareExport. Behaviour is unchanged except that the output
filename uses the sanitized chat title, so illegal characters no
longer reach the filesystem.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: New string resources

**Files:**
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Add the three new strings**

Append to the end of `<resources>` (before the closing tag) in `app/src/main/res/values/strings.xml`:

```xml
    <string name="select_all">Select all</string>
    <string name="export_chats">Export</string>
    <string name="export_chats_failed">Failed to export chats</string>
```

Match the existing indentation in that file.

- [ ] **Step 2: Build to verify**

Run: `./gradlew assembleDebug`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/res/values/strings.xml

git commit -m "$(cat <<'EOF'
feat(i18n): add strings for bulk chat export

select_all, export_chats, and export_chats_failed are used by the
new chat-list selection-mode export affordance. Non-English
locales update in follow-up PRs.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: `HomeViewModel.selectAllChats` + `exportSelectedChats`

**Files:**
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/home/HomeViewModel.kt`

No new unit tests. `HomeViewModel` has no coroutine test infrastructure in this repo; adding it is out of scope per the spec. The pure logic inside (markdown building, zip assembly, filename generation) is already covered by Tasks 1–3.

- [ ] **Step 1: Add `selectAllChats`**

In `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/home/HomeViewModel.kt`, add this function anywhere above `selectChat` (e.g., just before `disableSelectionMode`):

```kotlin
    fun selectAllChats() {
        val anyUnselected = _chatListState.value.selectedChats.any { !it }

        if (anyUnselected) {
            _chatListState.update {
                it.copy(selectedChats = List(it.chats.size) { true })
            }
        } else {
            disableSelectionMode()
        }
    }
```

- [ ] **Step 2: Add `exportSelectedChats`**

Add this suspend function to the same file, just below `selectAllChats`:

```kotlin
    suspend fun exportSelectedChats(): ExportArtifact? {
        val selected = _chatListState.value.chats.filterIndexed { idx, _ ->
            _chatListState.value.selectedChats[idx]
        }

        if (selected.isEmpty()) return null

        val exportedOn = formatCurrentDateTime()
        val platforms = _platformState.value

        val perChatMarkdown = withContext(Dispatchers.IO) {
            selected.map { chat ->
                val messages = chatRepository.fetchMessagesV2(chat.id)
                val grouped = ChatMarkdownExporter.groupMessagesForExport(
                    messages = messages,
                    enabledPlatformOrder = chat.enabledPlatform
                )
                chat to ChatMarkdownExporter.buildMarkdown(
                    chat = chat,
                    userMessages = grouped.first,
                    assistantMessages = grouped.second,
                    platforms = platforms,
                    exportedOn = exportedOn
                )
            }
        }

        val artifact = if (perChatMarkdown.size == 1) {
            val (chat, markdown) = perChatMarkdown.single()
            ExportArtifact(
                fileName = ExportFilenames.buildSingleChatFileName(chat, System.currentTimeMillis()),
                bytes = markdown.toByteArray(Charsets.UTF_8),
                mimeType = "text/markdown"
            )
        } else {
            val fileNames = ExportFilenames.buildChatFileNames(selected)
            val entries = perChatMarkdown.map { (chat, markdown) ->
                (fileNames[chat.id] ?: "chat_${chat.id}.md") to markdown
            }
            val zipBytes = withContext(Dispatchers.IO) { ChatMarkdownExporter.buildZip(entries) }
            ExportArtifact(
                fileName = ExportFilenames.buildArchiveFileName(System.currentTimeMillis()),
                bytes = zipBytes,
                mimeType = "application/zip"
            )
        }

        disableSelectionMode()
        return artifact
    }

    private fun formatCurrentDateTime(): String {
        val currentDate = java.util.Date()
        val format = java.text.SimpleDateFormat("yyyy-MM-dd hh:mm a", java.util.Locale.getDefault())
        return format.format(currentDate)
    }
```

`ChatMarkdownExporter.groupMessagesForExport` ports the grouping logic from `ChatViewModel.fetchGroupedMessages` verbatim (see Task 2), so the markdown this path produces is identical to what the chat screen produces for the same chat.

Add these imports to `HomeViewModel.kt` (alphabetical within groups per `AGENTS.md`):

```kotlin
import dev.chungjungsoo.gptmobile.presentation.common.ExportArtifact
import dev.chungjungsoo.gptmobile.util.ChatMarkdownExporter
import dev.chungjungsoo.gptmobile.util.ExportFilenames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
```

- [ ] **Step 3: Build and run existing tests**

```bash
./tools/ktlint.sh --format
./gradlew test
./gradlew assembleDebug
```

Expected: all green.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/home/HomeViewModel.kt

git commit -m "$(cat <<'EOF'
feat(home): bulk chat export from selection mode

Adds selectAllChats to the chat-list selection toggle and
exportSelectedChats, which returns a single-chat ExportArtifact
when one chat is selected and a zip of per-chat markdown when two
or more are selected. Runs off Dispatchers.IO.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: `HomeScreen` UI — Select All and Export

**Files:**
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/home/HomeScreen.kt`

No unit tests; verified via compilation and manual check in Task 9. The two new icons slot into the existing selection-mode `actions` block. The Export button uses the shared `shareExport` helper and surfaces failures via the existing toast pattern.

- [ ] **Step 1: Wire new callbacks into `HomeScreen`**

In `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/home/HomeScreen.kt`, find the `HomeTopAppBar(...)` call inside `HomeScreen`'s `Scaffold(topBar = { ... })` block. Update that call to pass three new callbacks:

```kotlin
            HomeTopAppBar(
                isSelectionMode = chatListState.isSelectionMode,
                isSearchMode = chatListState.isSearchMode,
                selectedChats = chatListState.selectedChats.count { it },
                allChatsCount = chatListState.chats.size,
                scrollBehavior = scrollBehavior,
                actionOnClick = {
                    if (chatListState.isSelectionMode) {
                        homeViewModel.openDeleteWarningDialog()
                    } else {
                        settingOnClick()
                    }
                },
                duplicateOnClick = {
                    homeViewModel.duplicateSelectedChat()
                    Toast.makeText(context, context.getString(R.string.duplicated_chat), Toast.LENGTH_SHORT).show()
                },
                selectAllOnClick = homeViewModel::selectAllChats,
                exportOnClick = {
                    scope.launch {
                        try {
                            val artifact = homeViewModel.exportSelectedChats() ?: return@launch
                            shareExport(context, artifact)
                        } catch (e: Exception) {
                            Toast.makeText(context, context.getString(R.string.export_chats_failed), Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                navigationOnClick = {
                    if (chatListState.isSelectionMode) {
                        homeViewModel.disableSelectionMode()
                        return@HomeTopAppBar
                    }

                    if (chatListState.isSearchMode) {
                        homeViewModel.disableSearchMode()
                    } else {
                        homeViewModel.enableSearchMode()
                    }
                },
                onSearchQueryChanged = homeViewModel::updateSearchQuery,
                searchQuery = searchQuery
            )
```

Add a `rememberCoroutineScope` near the top of `HomeScreen` (alongside the other `val x by ...` declarations):

```kotlin
    val scope = rememberCoroutineScope()
```

Add these imports to `HomeScreen.kt` (grouped per `AGENTS.md`):

```kotlin
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.runtime.rememberCoroutineScope
import dev.chungjungsoo.gptmobile.presentation.common.shareExport
import kotlinx.coroutines.launch
```

- [ ] **Step 2: Extend `HomeTopAppBar` with the two new actions**

In the same file, update the `HomeTopAppBar` composable signature and its `actions = { ... }` block:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeTopAppBar(
    isSelectionMode: Boolean,
    isSearchMode: Boolean,
    selectedChats: Int,
    allChatsCount: Int,
    scrollBehavior: TopAppBarScrollBehavior,
    actionOnClick: () -> Unit,
    duplicateOnClick: () -> Unit,
    selectAllOnClick: () -> Unit,
    exportOnClick: () -> Unit,
    navigationOnClick: () -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    searchQuery: String
) {
```

Inside `actions = { ... }`, update the `isSelectionMode ->` branch to:

```kotlin
                isSelectionMode -> {
                    IconButton(
                        modifier = Modifier.padding(4.dp),
                        enabled = allChatsCount > 0,
                        onClick = selectAllOnClick
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.SelectAll,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            contentDescription = stringResource(R.string.select_all)
                        )
                    }
                    if (selectedChats == 1) {
                        IconButton(
                            modifier = Modifier.padding(4.dp),
                            enabled = true,
                            onClick = duplicateOnClick
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ContentCopy,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                contentDescription = stringResource(R.string.duplicate)
                            )
                        }
                    }
                    if (selectedChats >= 1) {
                        IconButton(
                            modifier = Modifier.padding(4.dp),
                            onClick = exportOnClick
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.FileDownload,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                contentDescription = stringResource(R.string.export_chats)
                            )
                        }
                    }
                    IconButton(
                        modifier = Modifier.padding(4.dp),
                        onClick = actionOnClick
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            contentDescription = stringResource(R.string.delete)
                        )
                    }
                }
```

- [ ] **Step 3: Build and lint**

```bash
./tools/ktlint.sh --format
./gradlew assembleDebug
./gradlew test
```

Expected: all green.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/home/HomeScreen.kt

git commit -m "$(cat <<'EOF'
feat(home): add select-all and export icons to selection mode

Long-press a chat, optionally tap Select All, then tap Export. One
chat produces a .md; two or more produce a .zip of per-chat .md
files. Failures surface via the existing toast pattern.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: Full verification

**Files:** none modified.

This task is a checklist — no code changes, no new commit.

- [ ] **Step 1: Full build and lint pass**

```bash
./gradlew clean
./tools/ktlint.sh
./gradlew assembleDebug
./gradlew test
```

All must succeed.

- [ ] **Step 2: Manual smoke test on device or emulator**

Install the debug APK. Run through each path:

1. **In-chat Export Chat (regression):** open an existing chat, tap the three-dot menu, tap Export Chat. Confirm the share sheet opens with a `.md` attachment and the content matches the pre-change format.
2. **Chat list — export one:** return to the chat list, long-press a chat to enter selection mode, tap the download icon. Confirm the share sheet offers a `.md` file named `export_{title}_{millis}.md`.
3. **Chat list — export multiple:** long-press a chat, tap Select All, tap the download icon. Confirm the share sheet offers a `.zip`; save it and open locally. Confirm there is one `.md` per chat with the expected names (duplicate-titled chats disambiguate with `_{chatId}`).
4. **Select All toggle:** in selection mode, tap Select All twice. First tap selects every row; second tap exits selection mode.
5. **Illegal-title sanitization:** create a chat whose title contains `/`, `:`, or `?`. Export it. Confirm the resulting filename (single or inside the zip) has those characters replaced with `_`.
6. **Failure path:** (optional, hard to trigger without code change) verify that an induced failure in the share helper produces the `export_chats_failed` toast without crashing the app.

- [ ] **Step 3: Confirm no skip-worktree'd or local-only files are staged for upstream**

```bash
git log main..HEAD --stat | grep -E "superpowers|^\.claude|^\.githooks|^\.claudeignore|^\.mcp\.json"
```

Expect: the `superpowers` hits are the spec and plan commits from this branch. Those must be dropped before opening the upstream PR, per `.claude/contributing.md`. The other paths should produce no output.

- [ ] **Step 4: Report completion**

Summarize to the user: tasks done, tests passing, manual-smoke checklist completed, next step is to drop the `docs/superpowers/` commits and open the upstream PR (or first a Discussions post, per `.claude/contributing.md`).
