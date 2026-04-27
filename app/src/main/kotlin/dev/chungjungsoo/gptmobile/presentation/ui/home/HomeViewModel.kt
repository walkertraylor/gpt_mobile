package dev.chungjungsoo.gptmobile.presentation.ui.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.presentation.common.ExportArtifact
import dev.chungjungsoo.gptmobile.util.ChatMarkdownExporter
import dev.chungjungsoo.gptmobile.util.ExportFilenames
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(FlowPreview::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val settingRepository: SettingRepository
) : ViewModel() {

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 300L
    }

    data class ChatListState(
        val chats: List<ChatRoomV2> = listOf(),
        val isSelectionMode: Boolean = false,
        val isSearchMode: Boolean = false,
        val selectedPlatforms: List<Boolean> = listOf(),
        val selectedChats: List<Boolean> = listOf()
    )

    private val _chatListState = MutableStateFlow(ChatListState())
    val chatListState: StateFlow<ChatListState> = _chatListState.asStateFlow()

    private val _platformState = MutableStateFlow(listOf<PlatformV2>())
    val platformState = _platformState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _showSelectModelDialog = MutableStateFlow(false)
    val showSelectModelDialog: StateFlow<Boolean> = _showSelectModelDialog.asStateFlow()

    private val _showDeleteWarningDialog = MutableStateFlow(false)
    val showDeleteWarningDialog: StateFlow<Boolean> = _showDeleteWarningDialog.asStateFlow()

    init {
        // Set up debounced search
        _searchQuery
            .debounce(SEARCH_DEBOUNCE_MS)
            .distinctUntilChanged()
            .onEach { query -> searchChats(query) }
            .launchIn(viewModelScope)
    }

    fun updatePlatformCheckedState(idx: Int) {
        if (idx < 0 || idx >= _chatListState.value.selectedPlatforms.size) return

        _chatListState.update {
            it.copy(
                selectedPlatforms = it.selectedPlatforms.mapIndexed { index, b ->
                    if (index == idx) {
                        !b
                    } else {
                        b
                    }
                }
            )
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.update { query }
    }

    private fun searchChats(query: String) {
        viewModelScope.launch {
            val chats = chatRepository.searchChatsV2(query)
            _chatListState.update {
                it.copy(
                    chats = chats,
                    selectedChats = List(chats.size) { false }
                )
            }
        }
    }

    fun openDeleteWarningDialog() {
        closeSelectModelDialog()
        _showDeleteWarningDialog.update { true }
    }

    fun closeDeleteWarningDialog() {
        _showDeleteWarningDialog.update { false }
    }

    fun openSelectModelDialog() {
        _showSelectModelDialog.update { true }
        disableSelectionMode()
    }

    fun closeSelectModelDialog() {
        _showSelectModelDialog.update { false }
        _chatListState.update { it.copy(selectedPlatforms = List(it.selectedPlatforms.size) { false }) }
    }

    fun deleteSelectedChats() {
        viewModelScope.launch {
            val selectedChats = _chatListState.value.chats.filterIndexed { index, _ ->
                _chatListState.value.selectedChats[index]
            }

            chatRepository.deleteChatsV2(selectedChats)
            _chatListState.update { it.copy(chats = chatRepository.fetchChatListV2()) }
            disableSelectionMode()
        }
    }

    fun duplicateSelectedChat() {
        viewModelScope.launch {
            val selectedChats = _chatListState.value.chats.filterIndexed { index, _ ->
                _chatListState.value.selectedChats[index]
            }
            val selectedChat = selectedChats.singleOrNull() ?: return@launch

            chatRepository.duplicateChatV2(selectedChat)
            _chatListState.update { it.copy(chats = chatRepository.fetchChatListV2()) }
            disableSelectionMode()
        }
    }

    fun disableSelectionMode() {
        _chatListState.update {
            it.copy(
                selectedChats = List(it.chats.size) { false },
                isSelectionMode = false
            )
        }
    }

    fun disableSearchMode() {
        _chatListState.update { it.copy(isSearchMode = false) }
        _searchQuery.update { "" }
    }

    fun enableSelectionMode() {
        disableSearchMode()
        _chatListState.update { it.copy(isSelectionMode = true) }
    }

    fun enableSearchMode() {
        disableSelectionMode()
        _chatListState.update { it.copy(isSearchMode = true) }
    }

    fun fetchChats() {
        viewModelScope.launch {
            val chats = chatRepository.fetchChatListV2()

            _chatListState.update {
                it.copy(
                    chats = chats,
                    selectedChats = List(chats.size) { false },
                    isSelectionMode = false
                )
            }

            Log.d("chats", "${_chatListState.value.chats}")
        }
    }

    fun fetchPlatformStatus() {
        viewModelScope.launch {
            val platforms = settingRepository.fetchPlatformV2s()
            _platformState.update { platforms }

            if (_chatListState.value.selectedPlatforms.size != platforms.size) {
                _chatListState.update { it.copy(selectedPlatforms = List(platforms.size) { false }) }
            }
        }
    }

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

    suspend fun exportSelectedChats(outputDir: java.io.File): ExportArtifact? {
        val selected = _chatListState.value.chats.filterIndexed { idx, _ ->
            _chatListState.value.selectedChats[idx]
        }

        if (selected.isEmpty()) return null

        val exportedOn = formatCurrentDateTime()
        val platforms = _platformState.value
        val nowMillis = System.currentTimeMillis()

        val artifact = withContext(Dispatchers.IO) {
            if (selected.size == 1) {
                val chat = selected.single()
                val messages = chatRepository.fetchMessagesV2(chat.id)
                val grouped = ChatMarkdownExporter.groupMessagesForExport(
                    messages = messages,
                    enabledPlatformOrder = chat.enabledPlatform
                )
                val markdown = ChatMarkdownExporter.buildMarkdown(
                    chat = chat,
                    userMessages = grouped.first,
                    assistantMessages = grouped.second,
                    platforms = platforms,
                    exportedOn = exportedOn
                )
                val file = java.io.File(outputDir, ExportFilenames.buildSingleChatFileName(chat, nowMillis))
                file.writeText(markdown)
                ExportArtifact(file = file, mimeType = "text/markdown")
            } else {
                val fileNames = ExportFilenames.buildChatFileNames(selected)
                val zipFile = java.io.File(outputDir, ExportFilenames.buildArchiveFileName(nowMillis))
                ChatMarkdownExporter.writeZip(zipFile) { writer ->
                    selected.forEach { chat ->
                        val messages = chatRepository.fetchMessagesV2(chat.id)
                        val grouped = ChatMarkdownExporter.groupMessagesForExport(
                            messages = messages,
                            enabledPlatformOrder = chat.enabledPlatform
                        )
                        val markdown = ChatMarkdownExporter.buildMarkdown(
                            chat = chat,
                            userMessages = grouped.first,
                            assistantMessages = grouped.second,
                            platforms = platforms,
                            exportedOn = exportedOn
                        )
                        writer.writeEntry(fileNames[chat.id] ?: "chat_${chat.id}.md", markdown)
                    }
                }
                ExportArtifact(file = zipFile, mimeType = "application/zip")
            }
        }

        disableSelectionMode()
        return artifact
    }

    fun selectChat(chatRoomIdx: Int) {
        if (chatRoomIdx < 0 || chatRoomIdx > _chatListState.value.chats.size) return

        _chatListState.update {
            it.copy(
                selectedChats = it.selectedChats.mapIndexed { index, b ->
                    if (index == chatRoomIdx) {
                        !b
                    } else {
                        b
                    }
                }
            )
        }

        if (_chatListState.value.selectedChats.count { it } == 0) {
            disableSelectionMode()
        }
    }

    private fun formatCurrentDateTime(): String {
        val currentDate = java.util.Date()
        val format = java.text.SimpleDateFormat("yyyy-MM-dd hh:mm a", java.util.Locale.getDefault())
        return format.format(currentDate)
    }
}
