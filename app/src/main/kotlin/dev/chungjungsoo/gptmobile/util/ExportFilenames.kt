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
