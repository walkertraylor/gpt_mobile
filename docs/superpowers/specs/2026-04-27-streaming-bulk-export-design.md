# Streaming Bulk Chat Export Design

## Goal
Reduce the peak memory footprint of bulk chat export so that exporting hundreds or thousands of chats does not risk OOM, while keeping the user-facing behaviour byte-identical.

Tracks fork issue #1.

## Problem
The current `HomeViewModel.exportSelectedChats` materialises every chat's markdown into an in-memory list before zipping, then `ChatMarkdownExporter.buildZip` writes them into a `ByteArrayOutputStream` and returns a `ByteArray`. `shareExport` then calls `file.writeBytes(...)` on that array. Peak heap usage is roughly the sum of:

1. all selected chats' markdown strings,
2. the in-memory zip buffer,
3. the final `ByteArray`,
4. plus another transient copy through the file write.

For 1000 chats × 500 KB of markdown each, peak is around 500 MB — OOM territory on a 256-512 MB heap.

## Requirements
- Peak memory during a bulk export is bounded by the size of a single chat's markdown plus the standard `ZipOutputStream` deflater buffers.
- The user-facing artifact (filename, contents, share-sheet flow) is unchanged.
- The single-chat path (in-chat Export Chat and chat-list export of one) is also moved to the file-backed model so there is one shared mechanism.
- Existing tests are migrated to the new API rather than kept as legacy parallel paths.

## Recommended Architecture
Streaming write to a destination `File` with a callback-shaped API on `ChatMarkdownExporter`. `ExportArtifact` becomes a `File` reference instead of carrying bytes. `shareExport` simplifies to a `FileProvider.getUriForFile` call.

### `ExportArtifact` shape change
Replace:

```kotlin
data class ExportArtifact(val fileName: String, val bytes: ByteArray, val mimeType: String) { ... }
```

with:

```kotlin
data class ExportArtifact(val file: File, val mimeType: String) {
    val fileName: String get() = file.name
}
```

The custom `equals` / `hashCode` for the `ByteArray` field can be deleted along with the `bytes` field — `data class` defaults are fine for a `File` + `String` pair.

### Streaming zip writer on `ChatMarkdownExporter`
Replace `fun buildZip(entries: List<Pair<String, String>>): ByteArray` with:

```kotlin
fun writeZip(target: File, block: (ZipWriter) -> Unit) {
    target.outputStream().use { fos ->
        ZipOutputStream(fos).use { zos ->
            block(ZipWriter(zos))
        }
    }
}

class ZipWriter internal constructor(private val zos: ZipOutputStream) {
    fun writeEntry(name: String, content: String) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(content.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }
}
```

The `ZipWriter` wrapper exists so callers can't accidentally hold onto the raw `ZipOutputStream` outside the `use` block. The block is called with a writer whose lifetime is tied to the underlying stream.

### `HomeViewModel.exportSelectedChats` becomes a per-chat loop
The function takes the destination directory as a parameter (rather than reaching for a `Context`):

```kotlin
suspend fun exportSelectedChats(outputDir: File): ExportArtifact?
```

Implementation walks selected chats one at a time inside a single `withContext(Dispatchers.IO) { ... }` block, fetching → grouping → formatting → writing one zip entry, then letting the per-chat markdown string become collectable before the next iteration starts.

Single-chat case stays as direct `.md` write (no zip) but still file-backed via `outputDir`. The destination filename comes from `ExportFilenames.buildSingleChatFileName` (markdown) or `ExportFilenames.buildArchiveFileName` (zip), unchanged.

### `ChatViewModel.exportChat` mirrors the change
For symmetry and code reuse, `exportChat()` also gains an `outputDir: File` parameter, writes its markdown directly to a file, and returns an `ExportArtifact` pointing at that file. Loses the intermediate `String → toByteArray() → bytes` round trip.

### `shareExport` simplifies
With `ExportArtifact.file`, the helper drops `file.writeBytes(...)` entirely:

```kotlin
fun shareExport(context: Context, artifact: ExportArtifact) {
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
}
```

### Screen wiring
Both `ChatScreen` and `HomeScreen` pass `context.getExternalFilesDir(null)!!` as the destination directory. The non-null assertion is acceptable here — `getExternalFilesDir(null)` only returns null in extreme device states (no external storage), and the existing code already assumed that path was usable.

## User Experience
Unchanged. Same filenames, same markdown, same share sheet, same toast on failure. Multi-chat exports of moderate size will be imperceptibly faster (one less full-buffer copy through `writeBytes`); only very large bulk exports show a difference, and the difference is "no longer crashes."

## Testing
Migrate existing tests rather than add parallel coverage:

- `ChatMarkdownExporterTest` zip cases switch from "build a `ByteArray` and read it back" to "write to a temp `File` (use JUnit's `TemporaryFolder` rule) and read the file back with `ZipInputStream`." Same assertions on entry names/order/contents.
- The byte-for-byte golden test for `buildMarkdown` is unchanged (markdown formatter signature didn't change).
- `groupMessagesForExport` tests are unchanged.
- `ExportArtifact`'s equals/hashCode tests, if present, can be deleted with the field — the default `data class` impl over `File` + `String` is sufficient.

No new instrumented tests. The streaming behaviour is exercised by the same unit tests; the production memory benefit is confirmed by inspection plus the existing manual smoke-test on the emulator.

## Out of Scope
- Progress UI for very large exports. The spec explicitly punts on this (YAGNI). Once streaming is in, a future iteration can wrap the per-chat loop in a `Flow<ExportProgress>` if usage warrants it.
- Caching strategy for very large attachment bodies. Attachments aren't included in markdown today and that hasn't changed.
- Deflater tuning. The default `ZipOutputStream` compression is fine; revisiting if export latency becomes a real complaint.
