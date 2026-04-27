package dev.chungjungsoo.gptmobile.util

import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatMarkdownExporterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `buildMarkdown produces exact byte-for-byte format`() {
        val openai = platform(uid = "openai-uid", name = "OpenAI")

        val md = ChatMarkdownExporter.buildMarkdown(
            chat = chat(title = "Hello"),
            userMessages = listOf(userMessage("Hi")),
            assistantMessages = listOf(
                listOf(assistantMessage("Greetings", "openai-uid"))
            ),
            platforms = listOf(openai),
            exportedOn = "2026-04-24 09:07 AM"
        )

        val expected = "# Chat Export: \"Hello\"\n" +
            "\n" +
            "**Exported on:** 2026-04-24 09:07 AM\n" +
            "\n" +
            "---\n" +
            "\n" +
            "## Chat History\n" +
            "\n" +
            "**User:**\n" +
            "Hi\n" +
            "\n" +
            "**Assistant (OpenAI):**\n" +
            "Greetings\n" +
            "\n"

        assertEquals(expected, md)
    }

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

    @Test
    fun `writeZip writes one entry per call to writeEntry`() = runBlocking {
        val target = tempFolder.newFile("test.zip")

        ChatMarkdownExporter.writeZip(target) { writer ->
            writer.writeEntry("alpha.md", "alpha body")
            writer.writeEntry("beta.md", "beta body")
        }

        val readBack = readZipEntries(target)

        assertEquals(setOf("alpha.md", "beta.md"), readBack.keys)
        assertEquals("alpha body", readBack["alpha.md"])
        assertEquals("beta body", readBack["beta.md"])
    }

    @Test
    fun `writeZip preserves insertion order of entries`() = runBlocking {
        val target = tempFolder.newFile("ordered.zip")

        ChatMarkdownExporter.writeZip(target) { writer ->
            writer.writeEntry("zeta.md", "z")
            writer.writeEntry("alpha.md", "a")
        }

        assertEquals(listOf("zeta.md", "alpha.md"), readZipEntryOrder(target))
    }

    @Test
    fun `writeZip deletes the target file when the block throws`() = runBlocking {
        val target = tempFolder.newFile("doomed.zip")

        try {
            ChatMarkdownExporter.writeZip(target) { writer ->
                writer.writeEntry("first.md", "first")
                error("simulated failure")
            }
            fail("expected IllegalStateException")
        } catch (expected: IllegalStateException) {
            // expected
        }

        assertFalse("target file should have been deleted on failure", target.exists())
    }

    private fun readZipEntries(file: java.io.File): Map<String, String> {
        val out = mutableMapOf<String, String>()
        java.util.zip.ZipInputStream(file.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                out[entry.name] = zis.readBytes().toString(Charsets.UTF_8)
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return out
    }

    private fun readZipEntryOrder(file: java.io.File): List<String> {
        val out = mutableListOf<String>()
        java.util.zip.ZipInputStream(file.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                out.add(entry.name)
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return out
    }
}
