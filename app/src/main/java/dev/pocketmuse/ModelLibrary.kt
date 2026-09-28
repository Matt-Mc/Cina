package dev.pocketmuse

import android.content.Context
import android.net.Uri
import android.os.StatFs
import com.arm.aichat.gguf.GgufMetadataReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

data class RemoteModel(val repo: String, val file: String, val revision: String = "main", val size: Long = -1L) {
    val name get() = file.removeSuffix(".gguf")
    val url get() = "https://huggingface.co/$repo/resolve/$revision/${file.split('/').joinToString("/") { Uri.encode(it) }}"
}

object CuratedModels {
    val entries = listOf(
        RemoteModel("Qwen/Qwen3-0.6B-GGUF", "Qwen3-0.6B-Q8_0.gguf", size = 639_000_000),
        RemoteModel("Qwen/Qwen3-1.7B-GGUF", "Qwen3-1.7B-Q8_0.gguf", size = 1_830_000_000),
        RemoteModel("Qwen/Qwen3-4B-GGUF", "Qwen3-4B-Q4_K_M.gguf", size = 2_500_000_000)
    )
}

class ModelLibrary(private val context: Context, private val store: LocalStore, private val secrets: SecretStore) {
    private val directory = File(context.filesDir, "models").apply { mkdirs() }
    private val reader = GgufMetadataReader.create()

    fun freeBytes(): Long = StatFs(directory.path).availableBytes
    fun totalRam(): Long = (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).let { manager -> android.app.ActivityManager.MemoryInfo().also(manager::getMemoryInfo).totalMem }
    fun suitability(bytes: Long): String = when {
        bytes <= 0 -> "Size unknown; check storage and RAM before loading."
        freeBytes() < bytes * 12 / 10 -> "Not enough free storage."
        bytes > 2_000_000_000L && totalRam() < 8_000_000_000L -> "An 8 GB or higher-memory phone is recommended."
        totalRam() < bytes * 2 + 1_000_000_000L -> "May not fit in this phone's memory."
        else -> "Likely suitable; performance depends on the phone."
    }

    suspend fun search(query: String): List<String> = withContext(Dispatchers.IO) {
        require(query.isNotBlank())
        val url = "https://huggingface.co/api/models?search=${Uri.encode(query)}&filter=gguf&limit=20"
        val array = JSONArray(request(url))
        (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("id")?.takeIf(String::isNotBlank) }
    }

    suspend fun files(repo: String): List<RemoteModel> = withContext(Dispatchers.IO) {
        require(Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$").matches(repo))
        val array = JSONArray(request("https://huggingface.co/api/models/$repo/tree/main?recursive=true", token = secrets.get("hf")))
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val path = item.optString("path")
            if (!path.endsWith(".gguf", true)) null else RemoteModel(repo, path, size = item.optLong("size", -1))
        }
    }

    suspend fun import(uri: Uri): LocalModel = withContext(Dispatchers.IO) {
        require(reader.ensureSourceFileFormat(context, uri)) { "Selected file is not GGUF." }
        val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "imported.gguf"
        val file = uniqueFile(name)
        try {
            context.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
            validate(file)
            store.addModel(file.nameWithoutExtension, file.absolutePath, "import", file.length())
            store.models().first { it.path == file.absolutePath }
        } catch (e: Exception) { file.delete(); throw e }
    }

    suspend fun download(url: String, displayName: String, onProgress: (DownloadState) -> Unit): LocalModel = withContext(Dispatchers.IO) {
        val parsed = URL(normalizeUrl(url))
        require(parsed.protocol == "https") { "Only HTTPS model downloads are allowed." }
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100).let { if(it.endsWith(".gguf")) it else "$it.gguf" }
        val file = File(directory, safeName)
        val part = File(directory, "$safeName.part")
        val meta = File(directory, "$safeName.meta")
        if(file.exists()) {
            validate(file)
            store.addModel(file.nameWithoutExtension, file.absolutePath, parsed.toString(), file.length())
            return@withContext store.models().first { it.path == file.absolutePath }
        }
        val token = if(parsed.host == "huggingface.co") secrets.get("hf") else null
        val isHub = parsed.host == "huggingface.co"
        val head = connection(parsed.toString(), "HEAD", token, followRedirects = !isHub)
        val total = if(isHub) head.getHeaderFieldLong("X-Linked-Size", head.getHeaderFieldLong("Content-Length", -1)) else head.getHeaderFieldLong("Content-Length", -1)
        val linkedHash = if(isHub) head.getHeaderField("X-Linked-Etag")?.trim('"')?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) } else null
        val etag = linkedHash ?: head.getHeaderField("ETag")?.trim('"') ?: ""
        head.disconnect()
        require(total > 0) { "The server did not provide a file size." }
        require(freeBytes() > (total - part.length()).coerceAtLeast(0) + 100_000_000L) { "Not enough free storage." }
        if (part.exists() && (part.length() > total || (meta.readTextOrEmpty() != "$total\n$etag" && !(part.length() == total && linkedHash != null && sha256(part).equals(linkedHash, true))))) part.delete()
        meta.writeText("$total\n$etag")
        var received = part.length()
        if(received == total) {
            if (linkedHash != null) require(sha256(part).equals(linkedHash, true)) { "SHA-256 did not match the model file." }
            validate(part)
            require(part.renameTo(file)) { "Could not save the model." }
            meta.delete()
            store.addModel(file.nameWithoutExtension, file.absolutePath, parsed.toString(), file.length())
            onProgress(DownloadState())
            return@withContext store.models().first { it.path == file.absolutePath }
        }
        var get = connection(parsed.toString(), "GET", token, if(received > 0) received else null)
        if (received > 0 && get.responseCode != 206) {
            get.disconnect(); part.delete(); received = 0
            get = connection(parsed.toString(), "GET", token)
        }
        require(get.responseCode in 200..299) { "Download failed: HTTP ${get.responseCode}" }
        try {
            get.inputStream.use { input -> java.io.FileOutputStream(part, received > 0).buffered().use { output ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if(count < 0) break
                    output.write(buffer, 0, count)
                    received += count
                    onProgress(DownloadState(safeName, received, total, true))
                }
            } }
            require(part.length() == total) { "Download incomplete; it can be resumed." }
            if (linkedHash != null) require(sha256(part).equals(linkedHash, true)) { "SHA-256 did not match the model file." }
            validate(part)
            require(part.renameTo(file)) { "Could not save the model." }
            meta.delete()
            store.addModel(file.nameWithoutExtension, file.absolutePath, parsed.toString(), file.length())
            onProgress(DownloadState())
            store.models().first { it.path == file.absolutePath }
        } finally { get.disconnect() }
    }

    fun remove(model: LocalModel) { File(model.path).delete(); store.deleteModel(model.id) }

    private suspend fun validate(file: File) {
        require(reader.ensureSourceFileFormat(file)) { "Downloaded file is not GGUF." }
        file.inputStream().buffered().use { reader.readStructuredMetadata(it) }
    }
    private fun uniqueFile(rawName: String): File {
        val base = rawName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100).ifBlank { "imported.gguf" }
        var file = File(directory, base)
        var n = 1
        while(file.exists()) file = File(directory, "${base.removeSuffix(".gguf")}-${n++}.gguf")
        return file
    }
    private fun normalizeUrl(text: String): String = text.trim().replace(Regex("https://huggingface.co/([^/]+/[^/]+)/blob/"), "https://huggingface.co/$1/resolve/")
    private fun request(url: String, token: String? = null): String = connection(url, "GET", token).run { try { require(responseCode in 200..299) { "HTTP $responseCode" }; inputStream.bufferedReader().use { it.readText() } } finally { disconnect() } }
    private fun connection(url: String, method: String, token: String?, range: Long? = null, followRedirects: Boolean = true): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method; connectTimeout = 20_000; readTimeout = 30_000; instanceFollowRedirects = followRedirects
        if(token != null && URL(url).host == "huggingface.co") setRequestProperty("Authorization", "Bearer $token")
        if(range != null) setRequestProperty("Range", "bytes=$range-")
    }
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val b = ByteArray(256 * 1024); while(true) { val n = input.read(b); if(n < 0) break; digest.update(b, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun File.readTextOrEmpty() = if(exists()) readText() else ""
}
