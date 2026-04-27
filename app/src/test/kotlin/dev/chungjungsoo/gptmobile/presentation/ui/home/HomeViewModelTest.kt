package dev.chungjungsoo.gptmobile.presentation.ui.home

import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.repository.FakeChatRepository
import dev.chungjungsoo.gptmobile.data.repository.FakeSettingRepository
import dev.chungjungsoo.gptmobile.util.MainDispatcherRule
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var chatRepo: FakeChatRepository
    private lateinit var settingRepo: FakeSettingRepository

    @Before
    fun setUp() {
        chatRepo = FakeChatRepository()
        settingRepo = FakeSettingRepository()
    }

    @Test
    fun `selectAllChats sets all selected when any unselected`() = runTest {
        chatRepo.chats = listOf(chat(1, "alpha"), chat(2, "beta"), chat(3, "gamma"))
        val vm = HomeViewModel(chatRepo, settingRepo)
        vm.fetchChats()
        advanceUntilIdle()
        vm.enableSelectionMode()
        vm.selectChat(0) // one selected

        vm.selectAllChats()

        val state = vm.chatListState.value
        assertEquals(listOf(true, true, true), state.selectedChats)
        assertTrue(state.isSelectionMode)
    }

    @Test
    fun `selectAllChats exits selection mode when all already selected`() = runTest {
        chatRepo.chats = listOf(chat(1, "alpha"), chat(2, "beta"))
        val vm = HomeViewModel(chatRepo, settingRepo)
        vm.fetchChats()
        advanceUntilIdle()
        vm.enableSelectionMode()
        vm.selectChat(0)
        vm.selectChat(1) // both selected

        vm.selectAllChats()

        val state = vm.chatListState.value
        assertEquals(listOf(false, false), state.selectedChats)
        assertEquals(false, state.isSelectionMode)
    }

    @Test
    fun `exportSelectedChats returns null when nothing is selected`() = runTest {
        chatRepo.chats = listOf(chat(1, "alpha"))
        val vm = HomeViewModel(chatRepo, settingRepo)
        vm.fetchChats()
        advanceUntilIdle()

        val artifact = vm.exportSelectedChats(tempFolder.newFolder("out"))

        assertNull(artifact)
    }

    @Test
    fun `exportSelectedChats writes a markdown file when one chat is selected`() = runTest {
        val c = chat(1, "alpha")
        chatRepo.chats = listOf(c)
        chatRepo.messagesByChatId = mapOf(1 to listOf(userMessage(1, "hello", createdAt = 10)))
        val vm = HomeViewModel(chatRepo, settingRepo)
        vm.fetchChats()
        advanceUntilIdle()
        vm.enableSelectionMode()
        vm.selectChat(0)

        val outputDir = tempFolder.newFolder("out")
        val artifact = vm.exportSelectedChats(outputDir)
        advanceUntilIdle()

        checkNotNull(artifact)
        assertEquals("text/markdown", artifact.mimeType)
        assertTrue("filename should match export_<title>_<millis>.md", artifact.fileName.matches(Regex("""export_alpha_\d+\.md""")))
        assertTrue(artifact.file.exists())
        val body = artifact.file.readText()
        assertTrue(body.contains("""# Chat Export: "alpha""""))
        assertTrue(body.contains("**User:**\nhello"))
        assertEquals(false, vm.chatListState.value.isSelectionMode)
    }

    @Test
    fun `exportSelectedChats writes a zip with one entry per chat when many selected`() = runTest {
        val a = chat(1, "alpha")
        val b = chat(2, "beta")
        chatRepo.chats = listOf(a, b)
        chatRepo.messagesByChatId = mapOf(
            1 to listOf(userMessage(1, "from-alpha", createdAt = 10)),
            2 to listOf(userMessage(2, "from-beta", createdAt = 11))
        )
        val vm = HomeViewModel(chatRepo, settingRepo)
        vm.fetchChats()
        advanceUntilIdle()
        vm.enableSelectionMode()
        vm.selectChat(0)
        vm.selectChat(1)

        val outputDir = tempFolder.newFolder("out")
        val artifact = vm.exportSelectedChats(outputDir)
        advanceUntilIdle()

        checkNotNull(artifact)
        assertEquals("application/zip", artifact.mimeType)
        assertTrue("zip filename should match gpt_mobile_chats_<ts>.zip", artifact.fileName.matches(Regex("""gpt_mobile_chats_\d{8}_\d{6}\.zip""")))
        val entries = readZipEntries(artifact.file.readBytes())
        assertEquals(setOf("alpha.md", "beta.md"), entries.keys)
        assertTrue(entries.getValue("alpha.md").contains("from-alpha"))
        assertTrue(entries.getValue("beta.md").contains("from-beta"))
    }

    @Test
    fun `exportSelectedChats disambiguates filenames when chat titles collide inside a zip`() = runTest {
        val a = chat(1, "same")
        val b = chat(2, "same")
        chatRepo.chats = listOf(a, b)
        chatRepo.messagesByChatId = mapOf(
            1 to listOf(userMessage(1, "first", createdAt = 10)),
            2 to listOf(userMessage(2, "second", createdAt = 11))
        )
        val vm = HomeViewModel(chatRepo, settingRepo)
        vm.fetchChats()
        advanceUntilIdle()
        vm.enableSelectionMode()
        vm.selectChat(0)
        vm.selectChat(1)

        val artifact = vm.exportSelectedChats(tempFolder.newFolder("out"))
        advanceUntilIdle()

        checkNotNull(artifact)
        val entries = readZipEntries(artifact.file.readBytes())
        assertEquals(setOf("same_1.md", "same_2.md"), entries.keys)
    }

    private fun chat(id: Int, title: String): ChatRoomV2 =
        ChatRoomV2(id = id, title = title, enabledPlatform = emptyList())

    private fun userMessage(id: Int, content: String, createdAt: Long): MessageV2 =
        MessageV2(id = id, chatId = id, content = content, platformType = null, createdAt = createdAt)

    private fun readZipEntries(bytes: ByteArray): Map<String, String> {
        val out = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                out[entry.name] = zis.readBytes().toString(Charsets.UTF_8)
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return out
    }
}
