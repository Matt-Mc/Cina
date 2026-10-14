package dev.pocketmuse

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.Writer

internal class InboxCapture(private val context: Context) {
    fun text(value: String, origin: String = "Written in Cina"): CapturedSource {
        CapturePolicy.text(value)
        return CapturedSource(value.lineSequence().first().take(100).ifBlank { "Saved text" },
            "text/plain", value.toByteArray(Charsets.UTF_8), value, origin)
    }
    @Suppress("DEPRECATION")
    fun share(intent: Intent): CapturedSource {
        require(intent.action == Intent.ACTION_SEND) { "Share one text item or document at a time." }
        require((intent.clipData?.itemCount ?: 0) <= 1) { "Share one document at a time." }
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: intent.clipData?.let {
            require(it.itemCount <= 1) { "Share one document at a time." }
            if (it.itemCount == 1) it.getItemAt(0).uri else null
        }
        val caption = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        if (uri == null) return text(caption, "Android share")
        require(uri.scheme == "content") { "Share a document from a file app." }
        require(caption.length <= 20_000) { "The accompanying text is too long. Share the document separately." }
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }?.take(160) ?: "Shared document"
        val mime = resolver.getType(uri) ?: intent.type.orEmpty()
        val pdf = mime == "application/pdf" || name.endsWith(".pdf", true)
        require(pdf || mime.startsWith("text/") || mime in setOf("application/json", "application/xml") || name.substringAfterLast('.', "").lowercase() in
            setOf("txt", "md", "csv", "json", "log", "html", "xml")) { "Choose a text document or PDF. Images and audio are not supported yet." }
        val bytes = resolver.openInputStream(uri)?.use(CapturePolicy::readBounded) ?: error("Could not open this document. Please share it again.")
        return document(name, mime, bytes, caption)
    }

    internal fun document(name: String, mime: String, bytes: ByteArray, caption: String = ""): CapturedSource {
        require(bytes.size <= CapturePolicy.MAX_BYTES) { "Choose a file no larger than 8 MB." }
        require(caption.length <= 20_000)
        val pdf = mime == "application/pdf" || name.endsWith(".pdf", true)
        if (!pdf) {
            val decoded = try { CapturePolicy.decode(bytes) } catch (_: java.nio.charset.CharacterCodingException) {
                error("This document is not UTF-8 text. Convert it to UTF-8 and share it again.")
            }
            CapturePolicy.text(decoded)
            return CapturedSource(name, mime.ifBlank { "text/plain" }, bytes, decoded, "Android share", accompanyingText = caption)
        }
        PDFBoxResourceLoader.init(context.applicationContext)
        val preview = StringBuilder()
        var status = "ready"
        try {
            PDDocument.load(bytes).use { document ->
                if (document.numberOfPages > 100) status = "preview_limited"
                val writer = object : Writer() {
                    override fun write(chars: CharArray, offset: Int, length: Int) {
                        val remaining = CapturePolicy.MAX_TEXT - preview.length
                        preview.append(chars, offset, minOf(length, remaining))
                        if (length > remaining) throw PreviewLimit()
                    }
                    override fun flush() = Unit
                    override fun close() = Unit
                }
                PDFTextStripper().apply { endPage = 100 }.writeText(document, writer)
            }
            if (preview.isBlank()) status = "needs_text"
        } catch (_: PreviewLimit) { status = "preview_limited"
        } catch (_: Exception) { status = "extraction_failed"; preview.clear() }
        return CapturedSource(name, "application/pdf", bytes, preview.toString(), "Android share", status, caption)
    }
    private class PreviewLimit : java.io.IOException()
}
