package com.clipboardshare.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast

class ShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        when (intent?.action) {
            Intent.ACTION_SEND -> handleSend(intent)
            Intent.ACTION_SEND_MULTIPLE -> handleSendMultiple(intent)
            else -> {
                showToast("❌ Неизвестный тип данных")
                finish()
            }
        }
    }

    private fun handleSend(intent: Intent) {
        val mimeType = intent.type ?: ""

        // Plain text
        if (mimeType == "text/plain") {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!text.isNullOrEmpty()) {
                copyToClipboard(text, "✅ Текст скопирован!")
                return
            }
        }

        // File URI
        val fileUri = getStreamUri(intent)
        if (fileUri != null) {
            val result = processUri(fileUri, mimeType)
            copyToClipboard(result.text, result.message)
            return
        }

        // Fallback to EXTRA_TEXT
        val fallback = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (!fallback.isNullOrEmpty()) {
            copyToClipboard(fallback, "✅ Текст скопирован!")
            return
        }

        showToast("❌ Нет данных для копирования")
        finish()
    }

    private fun handleSendMultiple(intent: Intent) {
        // Collect all URIs from every possible source
        val uris = mutableListOf<Uri>()

        // 1) EXTRA_STREAM (ArrayList<Uri>) — works on all Android versions
        val streamUris = getStreamUriList(intent)
        uris.addAll(streamUris)

        // 2) ClipData — backup source used by some apps/launchers
        if (uris.isEmpty()) {
            val clip = intent.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) {
                    val item = clip.getItemAt(i)
                    item?.uri?.let { uris.add(it) }
                }
            }
        }

        if (uris.isEmpty()) {
            // Maybe it's text items
            val texts = intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT)
            if (texts != null && texts.isNotEmpty()) {
                val combined = texts.filter { !it.isNullOrBlank() }.map { "[TEXT] $it" }.joinToString("\n\n---\n\n")
                if (combined.isNotBlank()) {
                    copyToClipboard(combined, "✅ Скопировано: ${texts.size} текст. (V4)")
                    return
                }
            }
            showToast("❌ Нет файлов для копирования")
            finish()
            return
        }

        val sb = StringBuilder()
        var count = 0
        for (uri in uris) {
            val mime = contentResolver.getType(uri) ?: intent.type ?: "*/*"

            val result = processUri(uri, mime)
            if (count > 0) sb.append("\n\n---\n\n")
            sb.append(result.text)
            count++
        }

        copyToClipboard(sb.toString(), "✅ Скопировано путей: $count (V7)")
    }

    // ─── URI helpers ────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun getStreamUri(intent: Intent): Uri? {
        return try {
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        } catch (e: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun getStreamUriList(intent: Intent): List<Uri> {
        return try {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ─── File processing ─────────────────────────────────────────────────────────

    private data class ProcessResult(val text: String, val message: String)

    private fun processUri(uri: Uri, mimeType: String): ProcessResult {
        return try {
            val name = getBestName(uri)
            val copiedFile = copyFileToDownloads(uri, name)
            if (copiedFile != null) {
                ProcessResult(copiedFile.absolutePath, "✅ Файл скопирован для Termux")
            } else {
                // If copying fails, fallback to real path extraction
                val path = getRealAbsolutePath(uri)
                ProcessResult(path, "⚠️ Не удалось скопировать, возвращён путь")
            }
        } catch (e: Exception) {
            ProcessResult(uri.toString(), "❌ Ошибка")
        }
    }

    private fun copyFileToDownloads(uri: Uri, fileName: String): java.io.File? {
        try {
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            val appDir = java.io.File(downloadsDir, "ClipboardShare")
            if (!appDir.exists()) {
                appDir.mkdirs()
            }

            // Sanitize filename to prevent path traversal
            val safeName = fileName.replace(Regex("[^a-zA-Z0-9.\\-_ ()]"), "_")
            val destFile = java.io.File(appDir, safeName)

            contentResolver.openInputStream(uri)?.use { input ->
                java.io.FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }

            if (destFile.exists()) {
                if (destFile.length() == 0L) {
                    showToast("⚠️ Внимание: Файл ${destFile.name} пустой (0 байт) из источника!")
                } else {
                    // Check magic bytes for PDF
                    try {
                        val magic = ByteArray(4)
                        java.io.FileInputStream(destFile).use { it.read(magic) }
                        if (magic[0] == 0x25.toByte() && magic[1] == 0x50.toByte() && magic[2] == 0x44.toByte() && magic[3] == 0x46.toByte()) {
                            if (!destFile.name.endsWith(".pdf", true)) {
                                val newFile = java.io.File(appDir, destFile.nameWithoutExtension + ".pdf")
                                destFile.renameTo(newFile)
                                return newFile
                            }
                        }
                    } catch (e: Exception) {}
                }
            }

            return destFile
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    /** Returns the best human-readable name for the URI. Never blank. */
    private fun getBestName(uri: Uri, index: Int = -1): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx)?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        } catch (_: Exception) {}

        uri.lastPathSegment?.takeIf { it.isNotBlank() }?.let { return it }
        return if (index >= 0) "File_$index" else "SharedFile_${System.currentTimeMillis()}"
    }

    private fun getRealAbsolutePath(uri: Uri): String {
        if (uri.scheme == "file") return uri.path ?: uri.toString()

        try {
            contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                    if (idx >= 0) {
                        val path = c.getString(idx)
                        if (!path.isNullOrBlank()) return path
                    }
                }
            }
        } catch (_: Exception) {}

        return uri.toString()
    }

    private fun uriToFallbackString(uri: Uri, index: Int = -1): String = getRealAbsolutePath(uri)

    // ─── Clipboard ───────────────────────────────────────────────────────────────

    private fun copyToClipboard(text: String, message: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ClipboardShare", text))
        showToast(message)
        finish()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
