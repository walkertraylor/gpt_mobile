# Streaming Bulk Chat Export Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stream bulk chat exports directly to disk so peak memory is bounded by one chat's markdown rather than all of them.

**Architecture:** Replace `ChatMarkdownExporter.buildZip(List<Pair>): ByteArray` with a callback-shaped `writeZip(File, (ZipWriter) -> Unit)` that opens a `ZipOutputStream` over the target file and lets callers stream entries in. `ExportArtifact` becomes file-backed (`file: File, mimeType: String`) so `shareExport` can hand the file to `FileProvider` directly. `HomeViewModel.exportSelectedChats(outputDir: File)` walks selected chats one at a time inside a single `withContext(Dispatchers.IO)` block, letting each chat's markdown go out of scope before the next iteration starts.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Room, coroutines, JUnit 4 (`TemporaryFolder` rule for file-backed tests), ktlint 1.3.1, `java.util.zip`, `androidx.core.content.FileProvider`.

---

## File Structure

### Modified files

| Path | Change |
|------|--------|
| `app/src/main/kotlin/dev/chungjungsoo/gptmobile/util/ChatMarkdownExporter.kt` | Replace `buildZip(List<Pair<String,String>>): ByteArray` with `writeZip(target: File, block: (ZipWriter) -> Unit)`. Add a nested `class ZipWriter` exposing `writeEntry(name, content)`. Delete-on-failure cleanup. |
| `app/src/test/kotlin/dev/chungjungsoo/gptmobile/util/ChatMarkdownExporterTest.kt` | Migrate the two zip round-trip tests off `ByteArray` and onto `TemporaryFolder` files. Add a delete-on-throw test. |
| `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/common/ExportArtifact.kt` | `bytes: ByteArray` → `file: File`. Drop the custom `equals`/`hashCode` (default `data class` over `File` + `String` is fine). Add `val fileName: String get() = file.name`. |
| `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/common/ChatExportShare.kt` | Drop `file.writeBytes(...)` — file already exists on disk. Use `artifact.file` directly with `FileProvider`. |
| `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatViewModel.kt` | `exportChat(): ExportArtifact` → `exportChat(outputDir: File): ExportArtifact`. Write markdown to file instead of returning bytes. |
| `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatScreen.kt` | Pass `context.getExternalFilesDir(null)!!` to `chatViewModel.exportChat(...)`. |
| `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/home/HomeViewModel.kt` | `exportSelectedChats(): ExportArtifact?` → `exportSelectedChats(outputDir: File): ExportArtifact?`. Replace `selected.map { ... }` + `buildZip` with single `withContext(Dispatchers.IO)` block that streams chats through `writeZip`. |
| `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/home/HomeScreen.kt` | Pass `context.getExternalFilesDir(null)!!` to `homeViewModel.exportSelectedChats(...)`. |

No new files created. No string resources changed. No DB schema changed.

---

## Git / Commit Conventions

- Each task commits on its own.
- Conventional-commit prefixes (`refactor:`, `test:`, etc.) matching upstream style.
- HEREDOC-style commit messages, co-authored:
  `Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>`
- Pre-commit ktlint hook runs on staged Kotlin. Do NOT bypass with `--no-verify`.
- Branch: `feat/streaming-bulk-export` (already pushed; PR #2 open against `feat/export-all-chats`).

---

## Task 1: Replace `buildZip` with streaming `writeZip` + migrate tests

**Files:**
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/util/ChatMarkdownExporter.kt`
- Modify: `app/src/test/kotlin/dev/chungjungsoo/gptmobile/util/ChatMarkdownExporterTest.kt`

This task swaps the API. Tests are migrated in the same commit because the old `buildZip` signature goes away.

- [ ] **Step 1: Rewrite the zip tests against the new API**

In `app/src/test/kotlin/dev/chungjungsoo/gptmobile/util/ChatMarkdownExporterTest.kt`, replace the existing two zip tests and the `readZipEntries` / `readZipEntryOrder` helpers with this block. Also add a `TemporaryFolder` rule and a delete-on-throw test:

```kotlin
@get:Rule
val tempFolder = TemporaryFolder()

@Test
fun `writeZip writes one entry per call to writeEntry`() {
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
fun `writeZip preserves insertion order of entries`() {
    val target = tempFolder.newFile("ordered.zip")

    ChatMarkdownExporter.writeZip(target) { writer ->
        writer.writeEntry("zeta.md", "z")
        writer.writeEntry("alpha.md", "a")
    }

    assertEquals(listOf("zeta.md", "alpha.md"), readZipEntryOrder(target))
}

@Test
fun `writeZip deletes the target file when the block throws`() {
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
```

Add the necessary imports (alphabetical within their groups, per `AGENTS.md`):

```kotlin
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.rules.TemporaryFolder
```

Remove the OLD `readZipEntries(bytes: ByteArray)` and `readZipEntryOrder(bytes: ByteArray)` helpers (the `ByteArray` overloads). The two old tests `buildZip contains one entry per input...` and `buildZip preserves insertion order...` are fully replaced — delete them.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.chungjungsoo.gptmobile.util.ChatMarkdownExporterTest"`

Expected: FAIL — `writeZip` and `ZipWriter` symbols don't resolve.

- [ ] **Step 3: Replace `buildZip` with `writeZip` + `ZipWriter`**

In `app/src/main/kotlin/dev/chungjungsoo/gptmobile/util/ChatMarkdownExporter.kt`, find the existing `buildZip` function:

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

Replace it with:

```kotlin
fun writeZip(target: java.io.File, block: (ZipWriter) -> Unit) {
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

class ZipWriter internal constructor(private val zos: java.util.zip.ZipOutputStream) {
    fun writeEntry(name: String, content: String) {
        zos.putNextEntry(java.util.zip.ZipEntry(name))
        zos.write(content.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }
}
```

`internal constructor` keeps callers from instantiating `ZipWriter` directly without going through `writeZip`, so the `ZipOutputStream` lifetime stays bounded by the `use { }` blocks.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.chungjungsoo.gptmobile.util.ChatMarkdownExporterTest"`

Expected: PASS — 11 tests green (8 existing buildMarkdown / grouping tests + 3 new writeZip tests).

- [ ] **Step 5: Run ktlint and full unit-test suite**

```bash
./tools/ktlint.sh --format
./gradlew :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL on both. The full suite passes because no other production code references `buildZip` yet — `HomeViewModel` still calls the old API, but the old API's removal hasn't broken compilation because `HomeViewModel` won't change until Task 4. **CRITICAL:** if compilation fails because `HomeViewModel.kt` still calls `buildZip`, that's expected — proceed anyway. Tasks 4 fixes it. If you want a clean intermediate build, skip Step 5 and rely on Task 5's verification pass.

Actually — the safer flow: do Step 5 only as `:app:testDebugUnitTest --tests "dev.chungjungsoo.gptmobile.util.ChatMarkdownExporterTest"` since the rest of the app may not compile in this intermediate state. That's fine; Task 4 restores it.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/dev/chungjungsoo/gptmobile/util/ChatMarkdownExporter.kt \
        app/src/test/kotlin/dev/chungjungsoo/gptmobile/util/ChatMarkdownExporterTest.kt

git commit -m "$(cat <<'EOF'
refactor(util): stream zip output to a File instead of returning bytes

Replaces ChatMarkdownExporter.buildZip(List<Pair>): ByteArray with
writeZip(File, (ZipWriter) -> Unit). Callers can now stream entries
into the zip without materialising every entry's contents in memory
first. Migrates the existing zip round-trip tests to file-backed
assertions and adds a delete-on-throw test.

Intermediate state: HomeViewModel still references the old API and
will not compile until task 4 lands. The unit tests for this util
pass standalone via the --tests filter.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: `ExportArtifact` becomes file-backed + `shareExport` simplifies

**Files:**
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/common/ExportArtifact.kt`
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/common/ChatExportShare.kt`

`shareExport` no longer writes anything — the file is already on disk by the time the artifact reaches it. Both files change in the same commit because their contracts are coupled.

- [ ] **Step 1: Rewrite `ExportArtifact` as file-backed**

Replace the entire contents of `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/common/ExportArtifact.kt` with:

```kotlin
package dev.chungjungsoo.gptmobile.presentation.common

import java.io.File

data class ExportArtifact(
    val file: File,
    val mimeType: String
) {
    val fileName: String get() = file.name
}
```

The custom `equals` / `hashCode` is no longer needed: `File` and `String` both have correct value semantics.

- [ ] **Step 2: Simplify `shareExport`**

Replace the entire contents of `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/common/ChatExportShare.kt` with:

```kotlin
package dev.chungjungsoo.gptmobile.presentation.common

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.FileProvider

private const val TAG = "ChatExportShare"

fun shareExport(context: Context, artifact: ExportArtifact) {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", artifact.file)
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

The deletions vs. the prior file: the `import java.io.File` is gone (no more `File(...)` construction inline); `file.writeBytes(artifact.bytes)` is gone (file already exists).

- [ ] **Step 3: Verify there are no other consumers of `ExportArtifact.bytes`**

Run: `grep -rn "artifact\.bytes\|\.bytes\b" app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/`

Expected: no hits beyond the `bytes` deletion in `ChatExportShare.kt` we just rewrote, and no other places that read `.bytes`. If a hit appears in `ChatViewModel.kt` or `HomeViewModel.kt`, that is also expected (those still construct `ExportArtifact(fileName, bytes, mimeType)` with the old constructor and will fail to compile until tasks 3 and 4). That's the intermediate state.

- [ ] **Step 4: Commit**

The intermediate state: `ChatViewModel` and `HomeViewModel` still construct `ExportArtifact` with the old positional arguments and assign `bytes`, so the app does not compile yet. Tasks 3 and 4 fix this.

```bash
git add app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/common/ExportArtifact.kt \
        app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/common/ChatExportShare.kt

git commit -m "$(cat <<'EOF'
refactor(presentation): make ExportArtifact a File reference

ExportArtifact now wraps a File on disk plus its mime type instead
of carrying bytes in memory. shareExport drops the writeBytes step
since callers are now responsible for the file existing before
they call share.

Intermediate state: ChatViewModel and HomeViewModel still construct
ExportArtifact with the old (fileName, bytes, mimeType) signature
and will not compile until tasks 3 and 4 land.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: `ChatViewModel.exportChat(outputDir)` writes markdown to a file + `ChatScreen` call site

**Files:**
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatViewModel.kt`
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatScreen.kt`

Both files change together because the function signature changes.

- [ ] **Step 1: Update `ChatViewModel.exportChat`**

In `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatViewModel.kt`, replace the existing `exportChat` function (the one that returns `ExportArtifact` from in-memory bytes) with:

```kotlin
    fun exportChat(outputDir: java.io.File): ExportArtifact {
        val markdown = ChatMarkdownExporter.buildMarkdown(
            chat = _chatRoom.value,
            userMessages = _groupedMessages.value.userMessages,
            assistantMessages = _groupedMessages.value.assistantMessages,
            platforms = _platformsInApp.value,
            exportedOn = formatCurrentDateTime()
        )

        val file = java.io.File(
            outputDir,
            ExportFilenames.buildSingleChatFileName(_chatRoom.value, System.currentTimeMillis())
        )
        file.writeText(markdown)

        return ExportArtifact(file = file, mimeType = "text/markdown")
    }
```

`java.io.File` is referenced inline rather than imported because the existing file does not import it elsewhere; consistent with how `java.text.SimpleDateFormat` etc. are referenced inline in `formatCurrentDateTime()`.

- [ ] **Step 2: Update the `ChatScreen` call site**

In `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatScreen.kt`, find the `onExportChatItemClick` lambda and replace it with:

```kotlin
                onExportChatItemClick = {
                    try {
                        val outputDir = context.getExternalFilesDir(null)
                            ?: error("external files dir unavailable")
                        shareExport(context, chatViewModel.exportChat(outputDir))
                    } catch (e: Exception) {
                        Toast.makeText(context, "Failed to export chat", Toast.LENGTH_SHORT).show()
                    }
                }
```

The `?: error(...)` is consistent with how the existing code expected this dir to be present and how Android's docs describe the only failure mode (no external storage at all).

- [ ] **Step 3: Build and run unit tests**

```bash
./tools/ktlint.sh --format
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

Expected for `:app:assembleDebug`: still fails because `HomeViewModel.kt` still uses the old `exportSelectedChats` shape. That gets fixed in Task 4. If a clean build is required between Tasks 3 and 4, defer Step 3's `:app:assembleDebug` to after Task 4.

For `:app:testDebugUnitTest`: should pass — the markdown produced by `exportChat()` is byte-equivalent to what `ChatMarkdownExporterTest`'s golden test covers.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatViewModel.kt \
        app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatScreen.kt

git commit -m "$(cat <<'EOF'
refactor(chat): write single-chat export markdown directly to file

ChatViewModel.exportChat now takes an outputDir and writes its
markdown to a file before returning an ExportArtifact. The
ChatScreen call site supplies context.getExternalFilesDir(null).
No behavioural change beyond skipping the intermediate
String.toByteArray copy and the shareExport file.writeBytes call.

Intermediate state: HomeViewModel still uses the prior
exportSelectedChats shape and will not compile until task 4.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: `HomeViewModel.exportSelectedChats(outputDir)` streams chat-by-chat + `HomeScreen` call site

**Files:**
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/home/HomeViewModel.kt`
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/home/HomeScreen.kt`

This is the streaming win — peak memory drops from "all chats' markdown" to "one chat's markdown."

- [ ] **Step 1: Replace `HomeViewModel.exportSelectedChats`**

In `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/home/HomeViewModel.kt`, replace the entire `suspend fun exportSelectedChats(): ExportArtifact?` body with:

```kotlin
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
```

Why this is the streaming win: in the multi-chat branch, each `markdown` string is local to a single iteration of `selected.forEach`. After `writer.writeEntry` returns, the iteration ends and `markdown` becomes eligible for GC before the next chat's markdown is built. Peak retained markdown is one chat at a time.

- [ ] **Step 2: Update the `HomeScreen` call site**

In `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/home/HomeScreen.kt`, find the `exportOnClick` lambda inside the `HomeTopAppBar(...)` invocation and replace it with:

```kotlin
                exportOnClick = {
                    scope.launch {
                        try {
                            val outputDir = context.getExternalFilesDir(null)
                                ?: error("external files dir unavailable")
                            val artifact = homeViewModel.exportSelectedChats(outputDir) ?: return@launch
                            shareExport(context, artifact)
                        } catch (e: Exception) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.export_chats_failed),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                },
```

- [ ] **Step 3: Run ktlint, build, and test**

```bash
./tools/ktlint.sh --format
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL on all three. The intermediate-state note in earlier tasks no longer applies — the whole compilation chain is restored.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/home/HomeViewModel.kt \
        app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/home/HomeScreen.kt

git commit -m "$(cat <<'EOF'
refactor(home): stream bulk export chat-by-chat to bound peak memory

HomeViewModel.exportSelectedChats now takes an outputDir and walks
selected chats one at a time inside a single Dispatchers.IO block,
fetching → grouping → formatting → writing one zip entry, then
letting the per-chat markdown string become collectable before the
next iteration. Single-chat case writes directly to a .md without
zipping. Peak memory drops from O(all chats' markdown) to O(one
chat's markdown).

User-visible behaviour unchanged: same filenames, same markdown
contents, same share-sheet flow.

Closes #1.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Full verification

**Files:** none modified.

This task is a checklist — no code changes, no new commit. Confirms the streaming branch is shipping-ready before anyone reviews the PR.

- [ ] **Step 1: Full build and lint pass**

```bash
./gradlew clean
./tools/ktlint.sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

All four must succeed.

- [ ] **Step 2: Manual emulator smoke test**

Boot the emulator with the seeded platforms (see `tools/seed-platforms.sh` workflow), install the rebuilt debug APK, and re-run the bulk-export smoke test from the parent feature plan's Task 9 manual checklist:

1. **In-chat Export Chat (regression):** open an existing chat → three-dot → Export Chat. Confirm the share sheet shows a `.md` named `export_<sanitized title>_<millis>.md` with content matching the pre-streaming format.
2. **Chat list — export one:** long-press a chat to enter selection mode, tap the download icon. Confirm same `.md` shape.
3. **Chat list — export multiple:** long-press → Select All → download. Confirm `.zip` named `gpt_mobile_chats_<ts>.zip`. Pull from the device with `adb pull /storage/emulated/0/Android/data/dev.chungjungsoo.gptmobile/files/<filename>` and `unzip -l` to confirm one entry per selected chat.
4. **Select All toggle:** in selection mode, tap Select All twice. First tap selects every row; second tap exits selection mode.

- [ ] **Step 3: Check the pushed branch only contains streaming work**

```bash
git log feat/export-all-chats..HEAD --oneline
```

Expected output: the spec commit (`docs: add streaming bulk export design spec`) plus four implementation commits (Tasks 1-4). Five commits total. No drift into unrelated paths.

- [ ] **Step 4: Push and confirm draft PR diff**

```bash
git push origin feat/streaming-bulk-export
gh pr view 2 --repo walkertraylor/gpt_mobile --json state,baseRefName,headRefName,additions,deletions,changedFiles
```

The PR (#2) should still be a draft, base `feat/export-all-chats`, head `feat/streaming-bulk-export`. The diff should touch only:
- `docs/superpowers/specs/2026-04-27-streaming-bulk-export-design.md`
- `docs/superpowers/plans/2026-04-27-streaming-bulk-export.md`
- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/util/ChatMarkdownExporter.kt`
- `app/src/test/kotlin/dev/chungjungsoo/gptmobile/util/ChatMarkdownExporterTest.kt`
- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/common/ExportArtifact.kt`
- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/common/ChatExportShare.kt`
- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatViewModel.kt`
- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatScreen.kt`
- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/home/HomeViewModel.kt`
- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/home/HomeScreen.kt`

10 files. No drift.

- [ ] **Step 5: Mark the PR ready for review**

If the diff looks clean and Step 1-3 succeeded, mark PR #2 ready for review:

```bash
gh pr ready 2 --repo walkertraylor/gpt_mobile
```

If anything is off, leave the PR in draft and report findings.
