package dev.chungjungsoo.gptmobile.presentation.common

import java.io.File

data class ExportArtifact(
    val file: File,
    val mimeType: String
) {
    val fileName: String get() = file.name
}
