package dev.pocketmuse

import org.json.JSONArray
import java.net.URLEncoder

internal enum class ModelSlot(val title: String, val description: String) {
    PHONE("Phone model", "Everyday replies and small tasks. Works offline."),
    HEAVY_PHONE("Heavy phone model", "More detailed work when you need to stay on your phone."),
    HOME("Home model", "Larger analysis on your own server. Work can keep running.")
}

internal data class ModelFilesState(val repo: String? = null, val loading: Boolean = false,
    val files: List<RemoteModel> = emptyList(), val error: String = "")

internal object ModelChoices {
    private val hubName = Regex("hf\\.co/[A-Za-z0-9_][A-Za-z0-9_-]{0,79}/[A-Za-z0-9_][A-Za-z0-9_.-]{0,79}:[A-Za-z0-9_][A-Za-z0-9_.-]{0,79}\\.gguf", RegexOption.IGNORE_CASE)
    fun validHomeName(name: String): Boolean = if(name.startsWith("hf.co/")) hubName.matches(name) && name.substringAfterLast(':').length <= 80
        else name.length <= 120 && name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]*(?::[A-Za-z0-9_.-]+)?")) && !name.contains("cloud", true)
    fun homeName(file: RemoteModel): String? = "hf.co/${file.repo}:${file.file}".takeIf { validHomeName(it) }
    fun homeLabel(name: String): String = if(name.startsWith("hf.co/")) name.substringAfterLast(':').replace(Regex("(?i)\\.gguf$"), "") else name
    fun limit(slot: ModelSlot, phoneLimit: Long): Long = when (slot) {
        ModelSlot.PHONE -> minOf(2_500_000_000L, phoneLimit * 3 / 4)
        ModelSlot.HEAVY_PHONE -> phoneLimit
        ModelSlot.HOME -> Long.MAX_VALUE
    }
    fun fits(slot: ModelSlot, bytes: Long, phoneLimit: Long): Boolean = bytes > 0 && bytes <= limit(slot, phoneLimit)
    fun files(repo: String, response: String): List<RemoteModel> {
        val items = JSONArray(response)
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val path = item.optString("path")
            val name = path.substringAfterLast('/')
            if (item.optString("type", "file") != "file" || !path.endsWith(".gguf", true) ||
                Regex("(?i).*-\\d{5}-of-\\d{5}\\.gguf$").matches(path) || name.contains("mmproj", true)) null
            else RemoteModel(repo, path, size = item.optLong("size", -1).takeIf { it > 0 }
                ?: item.optJSONObject("lfs")?.optLong("size", -1) ?: -1)
        }
    }
    fun encodePath(path: String): String = path.split('/').joinToString("/") {
        URLEncoder.encode(it, Charsets.UTF_8.name()).replace("+", "%20")
    }
}

/** One storage-compatible starting candidate, with no claim of measured task reliability. */
internal fun starterChoice(models: List<PopularGgufModel>, suitable: (RemoteModel)->Boolean): RemoteModel? =
    models.sortedByDescending { it.downloads }.firstNotNullOfOrNull { model ->
        model.files.filter(suitable).sortedWith(compareBy<RemoteModel> { if(it.file.contains("Q4_K_M",true)) 0 else 1 }.thenBy { it.size }).firstOrNull()
    }
