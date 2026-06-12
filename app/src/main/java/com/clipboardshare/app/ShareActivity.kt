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

            // DEBUG: collect all raw info about URI
            val debugInfo = buildString {
                append("[URI] ${uri}\n")
                append("[scheme] ${uri.scheme}\n")
                append("[path] ${uri.path}\n")
                append("[lastSeg] ${uri.lastPathSegment}\n")
                append("[mime] $mime\n")
                try {
                    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            append("[DISPLAY_NAME] ${if (idx >= 0) c.getString(idx) else "no column"}\n")
                        } else append("[DISPLAY_NAME] cursor empty\n")
                    } ?: append("[DISPLAY_NAME] query returned null\n")
                } catch (e: Exception) { append("[DISPLAY_NAME] exception: ${e.message}\n") }
            }

            if (count > 0) sb.append("\n\n---\n\n")
            sb.append(debugInfo)
            count++
        }

        copyToClipboard(sb.toString(), "✅ Скопировано путей: $count (V4)")
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
        val path = getRealAbsolutePath(uri)
        return ProcessResult(path, "✅ Путь скопирован")
    }

    private fun getRealAbsolutePath(uri: Uri): String {
        // 1) File scheme
        if (uri.scheme == "file") {
            return uri.path ?: uri.toString()
        }

        // 2) DocumentsContract (SAF) - e.g. from standard file managers
        try {
            if (android.provider.DocumentsContract.isDocumentUri(this, uri)) {
                val docId = android.provider.DocumentsContract.getDocumentId(uri)
                val split = docId.split(":")
                if (split.size >= 2) {
                    val type = split[0]
                    val id = split[1]

                    if ("primary".equals(type, ignoreCase = true)) {
                        return android.os.Environment.getExternalStorageDirectory().toString() + "/" + id
                    } else if ("raw".equals(type, ignoreCase = true)) {
                        return id
                    } else {
                        // Might be SD card or other volume, harder to map reliably without more context,
                        // but usually "primary" is the internal storage (/storage/emulated/0).
                    }
                } else {
                    // Sometimes docId is just the raw path
                    if (docId.startsWith("/")) return docId
                }
            }
        } catch (_: Exception) {}

        // 3) MediaStore _data column
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
        
        // 4) Fallback to decoded URI path if it looks like a file path
        try {
            val decodedPath = java.net.URLDecoder.decode(uri.toString(), "UTF-8")
            if (decodedPath.contains("/storage/")) {
                val extracted = "/storage/" + decodedPath.substringAfter("/storage/")
                return extracted
            }
        } catch (_: Exception) {}

        // 5) Absolute last fallback: the original URI string
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
