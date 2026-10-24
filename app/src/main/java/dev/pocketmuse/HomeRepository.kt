package dev.pocketmuse

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

internal data class HomeModel(val name: String, val bytes: Long, val loaded: Boolean)
internal data class HomeState(val paired: Boolean = false, val online: Boolean = false, val name: String = "Home", val paused: Boolean = false,
    val models: List<HomeModel> = emptyList(), val catalog: List<String> = emptyList(), val detail: String = "", val selected: String? = null, val deepModel: String? = null)
internal data class HomeJob(val id: String, val server: String, val device: String, val chat: Long?, val body: JSONObject, val snapshot: JSONObject,
    val submitted: Boolean, val command: String?, val error: String, val delivered: Boolean) {
    val state: String get() = snapshot.optString("state", "waiting")
    val title: String get() = body.getString("title")
    val model: String get() = body.getString("model")
    val output: String get() = snapshot.optString("output")
    val kind: String get() = body.getString("kind")
    val terminal: Boolean get() = state in setOf("completed", "cancelled")
}

internal class HomeRepository(private val context: Context, private val store: LocalStore = LocalStore(context)) {
    private val secrets = SecretStore(context)
    private val prefs = context.getSharedPreferences("home", 0)
    fun credentials(): HomeCredentials? = runCatching { HomeCredentials.read(JSONObject(secrets.get("home_connection") ?: return null)) }.getOrNull()
    fun selected(): String? = prefs.getString("selected", null)
    fun select(name: String?) { prefs.edit().putString("selected", name).commit() }
    fun assignDeep(name: String?) { prefs.edit().putString("deep_model", name).commit() }
    fun state(): HomeState {
        val cache = runCatching { JSONObject(prefs.getString("snapshot", "{}")!!) }.getOrDefault(JSONObject())
        val models = cache.optJSONArray("models") ?: JSONArray()
        val catalog = cache.optJSONArray("catalog") ?: JSONArray()
        return HomeState(credentials() != null, false, cache.optString("name", "Home"), cache.optBoolean("paused"),
            (0 until models.length()).map { models.getJSONObject(it).let { m -> HomeModel(m.getString("name"), m.optLong("bytes"), m.optBoolean("loaded")) } },
            (0 until catalog.length()).map { catalog.getString(it) }, prefs.getString("detail", "Last known state")!!, selected(), prefs.getString("deep_model", null))
    }
    suspend fun pair(bundle: String) = withContext(Dispatchers.IO) { mutex.withLock {
        require(credentials() == null) { "Disconnect the existing Home connection first." }
        val j = JSONObject(bundle)
        val url = HomeClient.validUrl(j.getString("url")); val pin = HomeClient.validPin(j.getString("fingerprint"))
        val result = HomeClient(url, pin).call("/v1/pair", JSONObject().put("code", j.getString("code")).put("name", android.os.Build.MODEL.take(80)))
        require(result.getInt("protocol") == 1 && result.getString("server_id") == j.getString("server_id")) { "Unexpected Home server identity or protocol." }
        val credentials = HomeCredentials(url, pin, result.getString("server_id"), result.getString("token"), result.getString("device_id"))
        secrets.put("home_connection", credentials.json().toString(), durable = true)
        prefs.edit().remove("snapshot").remove("selected").putString("detail", "Paired").commit()
        schedule(context)
    } }
    suspend fun disconnect() = withContext(Dispatchers.IO) { mutex.withLock {
        val credentials = credentials() ?: return@withLock
        // Revocation must be acknowledged before removing the credential. Offline users can revoke on the host.
        HomeClient(credentials.url, credentials.fingerprint, credentials.token).call("/v1/device", JSONObject())
        clearConnection()
    } }
    private fun clearConnection() { secrets.put("home_connection", "", durable = true); prefs.edit().clear().commit() }
    suspend fun forget() = withContext(Dispatchers.IO) { mutex.withLock { clearConnection() } }
    fun jobs(): List<HomeJob> = jobs(store)
    fun enqueue(kind: String, model: String, title: String, prompt: String = "", chat: Long? = null, userMessage: String? = null): String {
        val credentials = credentials() ?: error("Pair a Home server in Connections first.")
        require(model.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]*(?::[A-Za-z0-9_.-]+)?")) && model.length <= 120 && !model.contains("cloud", ignoreCase = true)) { "Use a local Ollama model name without a registry URL or cloud tag." }
        require(prompt.length <= 24000 && title.isNotBlank()) { "The request is too long or has no title." }
        val id = UUID.randomUUID().toString()
        val body = JSONObject().put("id", id).put("kind", kind).put("model", model).put("title", title.take(160)).put("prompt", prompt)
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            if (chat != null) require(store.chat(chat) != null) { "This chat was deleted." }
            if (chat != null) {
                require(jobs().none { it.chat == chat && !it.terminal && it.state !in setOf("failed", "paused", "interrupted") }) { "Home already has a request in this chat. Follow it in Work, or start a new chat." }
                if(userMessage != null) {
                    store.addMessage(chat, "user", userMessage)
                    store.setChatTitle(chat, userMessage.take(50))
                }
            }
            db.insertOrThrow("home_jobs", null, ContentValues().apply {
                put("id", id); put("server", credentials.serverId); put("device", credentials.deviceId); put("chat_id", chat)
                put("body", body.toString()); put("snapshot", JSONObject().put("state", "waiting").put("detail", "Waiting to reach Home").toString())
            })
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        schedule(context)
        return id
    }
    fun command(id: String, action: String) {
        require(action in setOf("pause", "resume", "cancel"))
        val credentials = credentials() ?: error("Reconnect Home to control this job.")
        val job = jobs().first { it.id == id }
        require(job.server == credentials.serverId && job.device == credentials.deviceId) { "This job belongs to a previous pairing. Manage it on that server." }
        // No local acknowledgement: until the server replies, Work shows a pending command.
        store.writableDatabase.update("home_jobs", ContentValues().apply { put("command", action); put("error", "") }, "id=?", arrayOf(id))
        schedule(context)
    }
    suspend fun pauseWorker(paused: Boolean) = withContext(Dispatchers.IO) { mutex.withLock {
        val c = credentials() ?: error("Pair Home first.")
        HomeClient(c.url, c.fingerprint, c.token).call("/v1/control", JSONObject().put("paused", paused))
    } }
    suspend fun sync(): HomeState = withContext(Dispatchers.IO) { mutex.withLock {
        val c = credentials() ?: return@withLock state()
        val client = HomeClient(c.url, c.fingerprint, c.token)
        try {
            val snapshot = client.call("/v1/state")
            require(snapshot.getInt("protocol") == 1 && snapshot.getString("server_id") == c.serverId) { "Home identity changed. Pair again after verifying the server." }
            prefs.edit().putString("snapshot", snapshot.toString()).putString("detail", snapshot.optString("runner_error").ifBlank { "Connected" }).commit()
            val eligible = jobs().filter { it.server == c.serverId && it.device == c.deviceId }
            eligible.filter { (!it.submitted && !it.terminal) || it.command != null }.forEach { job ->
                currentCoroutineContext().ensureActive()
                try {
                    if (!job.submitted && job.command == "cancel") {
                        val result = client.call("/v1/jobs/discard", job.body)
                        save(job.id, result, submitted = true, clearCommand = "cancel")
                        return@forEach
                    }
                    if (!job.submitted) {
                        // The immutable request and UUID were committed before sending; retrying cannot create another job.
                        val result = client.call("/v1/jobs", job.body)
                        save(job.id, result, submitted = true)
                    }
                    job.command?.let { action ->
                        val result = client.call("/v1/jobs/control", JSONObject().put("id", job.id).put("action", action))
                        save(job.id, result, submitted = true, clearCommand = action)
                    }
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    store.writableDatabase.update("home_jobs", ContentValues().apply { put("error", error.message?.take(300) ?: "Home could not acknowledge the request.") }, "id=?", arrayOf(job.id))
                    if(error is java.io.IOException) throw error
                }
            }
            val pending = jobs().filter { it.server == c.serverId && it.device == c.deviceId && it.submitted && (!it.terminal || !it.delivered) }
            // Keep even worst-case JSON escaping of full prompts/answers below the client's response cap.
            pending.chunked(4).forEach { chunk ->
                currentCoroutineContext().ensureActive()
                val result = client.call("/v1/jobs/status", JSONObject().put("ids", JSONArray(chunk.map { it.id }))).getJSONArray("jobs")
                val found = (0 until result.length()).map { result.getJSONObject(it) }
                found.forEach { save(it.getString("id"), it, submitted = true) }
                chunk.filter { job -> found.none { it.getString("id") == job.id } }.forEach { job ->
                    store.writableDatabase.update("home_jobs", ContentValues().apply { put("error", "Home no longer has this job. Its outcome is unknown; it was not resubmitted.") }, "id=?", arrayOf(job.id))
                }
            }
            state().copy(online = true)
        } catch (error: Exception) {
            prefs.edit().putString("detail", "Home unreachable or unverified. Last known state; pending work stays saved.").commit()
            throw error
        }
    } }
    internal fun save(id: String, snapshot: JSONObject, submitted: Boolean, clearCommand: String? = null) {
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            val job = jobs().firstOrNull { it.id == id } ?: return
            require(snapshot.getString("id") == id && snapshot.getString("model") == job.model && snapshot.getString("kind") == job.kind) { "Home returned a different job." }
            val oldRevision = job.snapshot.optLong("revision", 0)
            if (snapshot.optLong("revision", 0) < oldRevision) return
            db.update("home_jobs", ContentValues().apply { put("snapshot", snapshot.toString()); put("submitted", if(submitted) 1 else 0); put("error", "") }, "id=?", arrayOf(id))
            if (clearCommand != null) db.execSQL("UPDATE home_jobs SET command=NULL WHERE id=? AND command=?", arrayOf(id, clearCommand))
            if (!job.delivered && snapshot.getString("state") in setOf("completed", "cancelled")) {
                if (job.chat != null && job.kind == "analysis" && snapshot.getString("state") == "completed" && store.chat(job.chat) != null) {
                    store.addMessage(job.chat, "assistant", snapshot.getString("output"))
                }
                db.execSQL("UPDATE home_jobs SET delivered=1 WHERE id=?", arrayOf(id))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    companion object {
        private val mutex = Mutex()
        fun createTables(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS home_jobs(id TEXT PRIMARY KEY,server TEXT NOT NULL,device TEXT NOT NULL,chat_id INTEGER,body TEXT NOT NULL,snapshot TEXT NOT NULL,submitted INTEGER NOT NULL DEFAULT 0,command TEXT,error TEXT NOT NULL DEFAULT '',delivered INTEGER NOT NULL DEFAULT 0)")
        }
        fun jobs(store: LocalStore): List<HomeJob> = buildList {
            store.readableDatabase.rawQuery("SELECT id,server,device,chat_id,body,snapshot,submitted,command,error,delivered FROM home_jobs ORDER BY rowid DESC", null).use { c ->
                while (c.moveToNext()) add(HomeJob(c.getString(0), c.getString(1), c.getString(2), if(c.isNull(3)) null else c.getLong(3), JSONObject(c.getString(4)), JSONObject(c.getString(5)), c.getInt(6)!=0, if(c.isNull(7)) null else c.getString(7), c.getString(8), c.getInt(9)!=0))
            }
        }
        fun schedule(context: Context) {
            val manager = WorkManager.getInstance(context)
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            manager.enqueueUniquePeriodicWork("home_reconcile", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<HomeSyncWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build())
            manager.enqueueUniqueWork("home_sync", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<HomeSyncWorker>().setConstraints(constraints).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS).build())
        }
    }
}

internal class HomeSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = LocalStore(applicationContext)
        try {
            val repository = HomeRepository(applicationContext, store)
            val c = repository.credentials() ?: return Result.success()
            repository.sync()
            HomeFollowUps.recover(applicationContext, repository.jobs())
            return if(repository.jobs().any { it.server == c.serverId && it.device == c.deviceId && (!it.terminal && it.state !in setOf("paused", "interrupted", "failed") || it.command != null) }) Result.retry() else Result.success()
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { return Result.retry() }
        finally { store.close() }
    }
}
