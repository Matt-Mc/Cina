package dev.pocketmuse

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/** Bounded local measurements; never stores requests, replies, or model paths. */
internal class LocalPerformance(context: Context) {
    private val prefs = context.getSharedPreferences("response_performance", 0)
    private var started = 0L
    private var record = JSONObject()
    fun begin(model: String, mode: ResponseMode, submittedAt: Long = SystemClock.elapsedRealtime()) {
        started = submittedAt
        record = JSONObject().put("model", model).put("mode", mode.name).put("time", System.currentTimeMillis())
    }
    fun mark(name: String) { if (started != 0L) record.put(name, SystemClock.elapsedRealtime() - started) }
    fun value(name: String, value: Int) { if (started != 0L) record.put(name, value) }
    fun addDuration(name: String, elapsed: Long) { if (started != 0L) record.put(name, record.optLong(name) + elapsed) }
    fun has(name: String) = record.has(name)
    fun finish(outcome: String) {
        if (started == 0L) return
        mark("total_ms"); record.put("outcome", outcome)
        val previous = JSONArray(prefs.getString("samples", "[]"))
        val next = JSONArray()
        for (i in maxOf(0, previous.length() - 29) until previous.length()) next.put(previous.getJSONObject(i))
        next.put(record)
        prefs.edit().putString("samples", next.toString()).apply()
        started = 0
    }
    fun read(): List<String> {
        val samples = JSONArray(prefs.getString("samples", "[]"))
        return (0 until samples.length()).reversed().map { i ->
            val item = samples.getJSONObject(i)
            val timings = item.keys().asSequence().filter { it.endsWith("_ms") }.joinToString(" · ") {
                "${it.removeSuffix("_ms").replace('_', ' ')}: ${"%.1f".format(item.getLong(it) / 1000.0)}s"
            }
            "${item.getString("model")} · ${item.getString("mode")} · ${item.getString("outcome")}\n$timings\nPrompt: ${item.optInt("prompt_tokens")} tokens · Recovery: ${item.optInt("recoveries")} · Thinking control: ${if(item.optInt("thinking_control_supported") == 1) "supported" else "unavailable"}"
        }
    }
    fun clear() { prefs.edit().remove("samples").apply() }
}
