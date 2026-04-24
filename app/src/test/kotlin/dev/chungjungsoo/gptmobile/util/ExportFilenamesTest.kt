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
        assertEquals("abc", ExportFilenames.sanitizeChatTitle("abc"))
        assertEquals("hello world", ExportFilenames.sanitizeChatTitle("hello world"))
    }

    @Test
    fun `trailing dots and spaces are trimmed`() {
        assertEquals("title", ExportFilenames.sanitizeChatTitle("title..."))
        assertEquals("title", ExportFilenames.sanitizeChatTitle("title   "))
        assertEquals("title", ExportFilenames.sanitizeChatTitle("title. "))
    }

    @Test
    fun `windows reserved names get a trailing underscore and preserve case`() {
        assertEquals("CON_", ExportFilenames.sanitizeChatTitle("CON"))
        assertEquals("prn_", ExportFilenames.sanitizeChatTitle("prn"))
        assertEquals("COM1_", ExportFilenames.sanitizeChatTitle("COM1"))
        assertEquals("LPT9_", ExportFilenames.sanitizeChatTitle("LPT9"))
        assertEquals("Con_", ExportFilenames.sanitizeChatTitle("Con"))
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
        val millis = 1745473623000L // 2025-04-24 05:47:03 UTC
        val name = ExportFilenames.buildArchiveFileName(millis, java.util.TimeZone.getTimeZone("UTC"))
        assertEquals("gpt_mobile_chats_20250424_054703.zip", name)
    }

    private fun chat(id: Int, title: String): ChatRoomV2 =
        ChatRoomV2(id = id, title = title, enabledPlatform = emptyList())
}
