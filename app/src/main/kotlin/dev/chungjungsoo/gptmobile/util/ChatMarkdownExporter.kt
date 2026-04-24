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
