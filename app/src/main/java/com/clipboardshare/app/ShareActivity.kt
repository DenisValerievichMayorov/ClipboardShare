package com.clipboardshare.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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

        if (mimeType == "text/plain" && intent.hasExtra(Intent.EXTRA_TEXT)) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!text.isNullOrEmpty()) {
                copyToClipboard(text, "Текст скопирован!")
                return
            }
        }

        val fileUri: Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)
        if (fileUri != null) {
            val result = processFile(fileUri, mimeType)
            copyToClipboard(result.text, result.message)
        } else {
            val fallbackText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!fallbackText.isNullOrEmpty()) {
                copyToClipboard(fallbackText, "Текст скопирован!")
            } else {
                showToast("❌ Нет данных для копирования")
                finish()
            }
        }
    }

    private fun handleSendMultiple(intent: Intent) {
        val texts = intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT)
        if (texts != null && texts.isNotEmpty()) {
            val combined = texts.filter { !it.isNullOrBlank() }.joinToString("\n\n---\n\n")
            if (combined.isNotBlank()) {
                copyToClipboard(combined, "✅ Скопировано текстовых фрагментов: ${texts.size}")
                return
            }
        }

        val uris: ArrayList<Uri>? = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        if (uris != null && uris.isNotEmpty()) {
            val stringBuilder = java.lang.StringBuilder()
            var processedCount = 0
            for (uri in uris) {
                val mimeType = contentResolver.getType(uri) ?: intent.type ?: "*/*"
                val result = processFile(uri, mimeType)
                
                val textToAppend = result.text.takeIf { it.isNotBlank() } ?: uri.toString()
                
                if (processedCount > 0) {
                    stringBuilder.append("\n\n---\n\n")
                }
                stringBuilder.append(textToAppend)
                processedCount++
            }
            copyToClipboard(stringBuilder.toString(), "✅ Скопировано файлов: $processedCount")
        } else {
            showToast("❌ Нет файлов для копирования")
            finish()
        }
    }

    private data class ProcessResult(val text: String, val message: String)

    private fun processFile(uri: Uri, mimeType: String): ProcessResult {
        var fileName = getDisplayName(uri)
        if (fileName.isNullOrEmpty()) fileName = uri.lastPathSegment
        if (fileName.isNullOrEmpty()) fileName = uri.toString()

        try {
            val isTextFile = isTextMimeType(mimeType) || isTextByExtension(uri)

            if (!isTextFile) {
                return ProcessResult(fileName!!, "📁 Имя файла скопировано!")
            }

            val content = contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader(Charsets.UTF_8).readText()
            }

            if (content != null) {
                if (content.length > 1_000_000) {
                    return ProcessResult(fileName!!, "⚠️ Файл слишком большой, скопировано имя")
                } else if (content.isBlank()) {
                    return ProcessResult(fileName!!, "⚠️ Файл пуст, скопировано имя")
                } else {
                    return ProcessResult(content, "✅ Содержимое файла скопировано!\n(${content.length} символов)")
                }
            } else {
                return ProcessResult(fileName!!, "❌ Не удалось прочитать файл, скопировано имя")
            }
        } catch (e: Exception) {
            return ProcessResult(fileName!!, "⚠️ Ошибка чтения, скопировано имя")
        }
    }

    private fun isTextMimeType(mimeType: String): Boolean {
        return mimeType.startsWith("text/") ||
                mimeType == "application/json" ||
                mimeType == "application/xml" ||
                mimeType == "application/javascript" ||
                mimeType == "application/x-sh" ||
                mimeType == "application/x-python" ||
                mimeType == "application/x-yaml" ||
                mimeType == "application/toml" ||
                mimeType == "application/sql"
    }

    private fun isTextByExtension(uri: Uri): Boolean {
        val path = getDisplayName(uri) ?: uri.path ?: uri.lastPathSegment ?: return false
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
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in textExtensions
    }

    private fun getDisplayName(uri: Uri): String? {
        try {
            if (uri.scheme == "content") {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) {
                            val name = cursor.getString(idx)
                            if (!name.isNullOrEmpty()) return name
                        }
                    }
                }
            } else if (uri.scheme == "file") {
                val name = uri.lastPathSegment
                if (!name.isNullOrEmpty()) return name
            }
        } catch (e: Exception) {
            // Ignore
        }
        return null
    }

    private fun copyToClipboard(text: String, message: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("ClipboardShare", text)
        clipboard.setPrimaryClip(clip)
        showToast(message)
        finish()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
