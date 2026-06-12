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
                val combined = texts.filter { !it.isNullOrBlank() }.joinToString("\n\n---\n\n")
                if (combined.isNotBlank()) {
                    copyToClipboard(combined, "✅ Скопировано: ${texts.size} фрагм.")
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
            val textToAppend = result.text.takeIf { it.isNotBlank() } ?: uriToFallbackString(uri)
            if (count > 0) sb.append("\n\n---\n\n")
            sb.append(textToAppend)
            count++
        }

        copyToClipboard(sb.toString(), "✅ Скопировано файлов: $count")
    }

    // ─── URI helpers ────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun getStreamUri(intent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    @Suppress("DEPRECATION", "UNCHECKED_CAST")
    private fun getStreamUriList(intent: Intent): List<Uri> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
            } else {
                (intent.getParcelableArrayListExtra<android.os.Parcelable>(Intent.EXTRA_STREAM)
                    ?.filterIsInstance<Uri>()) ?: emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ─── File processing ─────────────────────────────────────────────────────────

    private data class ProcessResult(val text: String, val message: String)

    private fun processUri(uri: Uri, mimeType: String): ProcessResult {
        val name = getBestName(uri)

        return try {
            val isText = isTextMimeType(mimeType) || isTextByExtension(name)

            if (!isText) {
                return ProcessResult(name, "📁 Имя файла скопировано!")
            }

            val content = contentResolver.openInputStream(uri)?.use { it.bufferedReader(Charsets.UTF_8).readText() }

            when {
                content == null -> ProcessResult(name, "❌ Не удалось прочитать файл")
                content.length > 1_000_000 -> ProcessResult(name, "⚠️ Файл слишком большой, скопировано имя")
                content.isBlank() -> ProcessResult(name, "⚠️ Файл пуст, скопировано имя")
                else -> ProcessResult(content, "✅ Содержимое скопировано (${content.length} симв.)")
            }
        } catch (e: Exception) {
            ProcessResult(name, "⚠️ Ошибка: скопировано имя")
        }
    }

    /** Returns the best human-readable name/path we can get for the URI. Never blank. */
    private fun getBestName(uri: Uri): String {
        // 1) OpenableColumns.DISPLAY_NAME
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx)?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        } catch (_: Exception) {}

        // 2) MediaStore._DISPLAY_NAME / _DATA
        try {
            contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    for (col in listOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATA)) {
                        val idx = c.getColumnIndex(col)
                        if (idx >= 0) c.getString(idx)?.takeIf { it.isNotBlank() }?.let { return it.substringAfterLast('/') }
                    }
                }
            }
        } catch (_: Exception) {}

        // 3) lastPathSegment
        uri.lastPathSegment?.takeIf { it.isNotBlank() }?.let { return it }

        // 4) toString as last resort
        return uri.toString()
    }

    private fun uriToFallbackString(uri: Uri): String = getBestName(uri)

    // ─── MIME / extension helpers ────────────────────────────────────────────────

    private fun isTextMimeType(mimeType: String) =
        mimeType.startsWith("text/") ||
        mimeType in setOf(
            "application/json", "application/xml", "application/javascript",
            "application/x-sh", "application/x-python", "application/x-yaml",
            "application/toml", "application/sql"
        )

    private fun isTextByExtension(name: String): Boolean {
        val textExtensions = setOf(
            "txt", "md", "markdown", "log", "csv", "tsv",
            "json", "xml", "html", "htm", "css", "js", "ts",
            "py", "rb", "java", "kt", "kts", "go", "rs",
            "c", "cpp", "h", "hpp", "cs", "php", "swift",
            "sh", "bash", "zsh", "fish", "yaml", "yml",
            "toml", "ini", "cfg", "conf", "properties",
            "sql", "graphql", "vue", "jsx", "tsx", "env",
            "gitignore", "dockerfile", "makefile", "gradle"
        )
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in textExtensions
    }

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
