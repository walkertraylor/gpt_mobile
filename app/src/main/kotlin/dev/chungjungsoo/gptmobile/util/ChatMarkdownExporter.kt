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

    /**
     * Splits a flat message list into the parallel user/assistant shape the exporter expects.
     *
     * Mirrors ChatViewModel.fetchGroupedMessages: sorts by createdAt, starts a new user turn on
     * every platformType == null row, and orders each assistant bucket by the chat's enabled
     * platform list. Assistant rows that appear before any user row are dropped — the chat
     * screen never persists such state, so this branch only matters for defence in depth.
     */
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

    suspend fun writeZip(target: java.io.File, block: suspend (ZipWriter) -> Unit) {
        var success = false
        try {
            target.outputStream().use { fos ->
                java.util.zip.ZipOutputStream(fos).use { zos ->
                    block(ZipWriter(zos))
                }
            }
            success = true
        } finally {
            if (!success) target.delete()
        }
    }
}

class ZipWriter internal constructor(private val zos: java.util.zip.ZipOutputStream) {
    fun writeEntry(name: String, content: String) {
        zos.putNextEntry(java.util.zip.ZipEntry(name))
        zos.write(content.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }
}
