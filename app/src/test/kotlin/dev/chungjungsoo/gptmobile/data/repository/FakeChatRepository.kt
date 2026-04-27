package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoom
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.Message
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.dto.ApiState
import kotlinx.coroutines.flow.Flow

/**
 * Test-only fake. Only the methods exercised by HomeViewModel's
 * fetchChats / exportSelectedChats / selectAllChats paths are functional;
 * the rest throw to surface accidental dependencies on unstubbed behaviour.
 */
class FakeChatRepository : ChatRepository {
    var chats: List<ChatRoomV2> = emptyList()
    var messagesByChatId: Map<Int, List<MessageV2>> = emptyMap()

    override suspend fun fetchChatListV2(): List<ChatRoomV2> = chats

    override suspend fun fetchMessagesV2(chatId: Int): List<MessageV2> =
        messagesByChatId[chatId] ?: emptyList()

    override suspend fun searchChatsV2(query: String): List<ChatRoomV2> =
        chats.filter { it.title.contains(query, ignoreCase = true) }

    override suspend fun completeChat(
        userMessages: List<MessageV2>,
        assistantMessages: List<List<MessageV2>>,
        platform: PlatformV2
    ): Flow<ApiState> = error("not used in HomeViewModelTest")

    override suspend fun fetchChatList(): List<ChatRoom> = error("not used in HomeViewModelTest")
    override suspend fun fetchMessages(chatId: Int): List<Message> = error("not used in HomeViewModelTest")
    override suspend fun fetchChatPlatformModels(chatId: Int): Map<String, String> =
        error("not used in HomeViewModelTest")

    override suspend fun saveChatPlatformModels(chatId: Int, models: Map<String, String>) =
        error("not used in HomeViewModelTest")

    override suspend fun migrateToChatRoomV2MessageV2() = error("not used in HomeViewModelTest")
    override fun generateDefaultChatTitle(messages: List<MessageV2>): String? =
        error("not used in HomeViewModelTest")

    override suspend fun updateChatTitle(chatRoom: ChatRoomV2, title: String) =
        error("not used in HomeViewModelTest")

    override suspend fun saveChat(
        chatRoom: ChatRoomV2,
        messages: List<MessageV2>,
        chatPlatformModels: Map<String, String>
    ): ChatRoomV2 = error("not used in HomeViewModelTest")

    override suspend fun duplicateChatV2(chatRoom: ChatRoomV2): ChatRoomV2 =
        error("not used in HomeViewModelTest")

    override suspend fun deleteChats(chatRooms: List<ChatRoom>) = error("not used in HomeViewModelTest")
    override suspend fun deleteChatsV2(chatRooms: List<ChatRoomV2>) {
        chats = chats - chatRooms.toSet()
    }
}
