package dev.pocketmuse

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

/** Extracts text into bounded, locally searchable passages. No file bytes leave the phone. */
class AttachmentImporter(private val context: Context, private val store: LocalStore) {
    fun import(chatId: Long, uri: Uri): ChatAttachment {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) null else {
                val size = cursor.getLong(1)
                require(size <= 8_000_000L || size < 0) { "Choose a file smaller than 8 MB." }
                cursor.getString(0)
            }
        } ?: "Attachment"
        val mime = resolver.getType(uri).orEmpty()
        val pdf = mime == "application/pdf" || name.endsWith(".pdf", true)
        val supportedText = mime.startsWith("text/") || name.substringAfterLast('.', "").lowercase() in setOf("txt", "md", "csv", "json", "log", "html", "xml")
        require(pdf || supportedText) { "Choose a PDF or text document." }
        val bytes = resolver.openInputStream(uri)?.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                require(output.size() <= 8_000_000) { "Choose a file smaller than 8 MB." }
            }
            output.toByteArray()
        } ?: error("Could not open the file.")
        require(bytes.size <= 8_000_000) { "Choose a file smaller than 8 MB." }
        val extracted = if (pdf) {
            PDFBoxResourceLoader.init(context.applicationContext)
            PDDocument.load(bytes).use { document ->
                require(document.numberOfPages <= 100) { "Choose a PDF with at most 100 pages." }
                PDFTextStripper().getText(document)
            }
        } else bytes.toString(Charsets.UTF_8)
        val clean = extracted.replace('\u0000', ' ').trim().take(250_000)
        require(clean.isNotBlank()) { "No selectable text found. Scanned PDFs need OCR, which Cina does not support yet." }
        val chunks = buildList {
            var start = 0
            while (start < clean.length) {
                val end = (start + 1200).coerceAtMost(clean.length)
                add(clean.substring(start, end))
                if (end == clean.length) break
                start = end - 100
            }
        }
        val id = store.addAttachment(chatId, name.take(160), if (pdf) "application/pdf" else "text/plain", chunks)
        return store.attachments(chatId).first { it.id == id }
    }
}
