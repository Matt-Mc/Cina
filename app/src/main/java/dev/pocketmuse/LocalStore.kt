package dev.pocketmuse

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues

class LocalStore(context: Context) : SQLiteOpenHelper(context, "pocket_muse.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE chats(id INTEGER PRIMARY KEY, title TEXT NOT NULL, web INTEGER NOT NULL DEFAULT 0, yolo INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY, chat_id INTEGER NOT NULL, role TEXT NOT NULL, body TEXT NOT NULL, time INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE notes(id INTEGER PRIMARY KEY, title TEXT NOT NULL, body TEXT NOT NULL)")
        db.execSQL("CREATE TABLE reminders(id INTEGER PRIMARY KEY, title TEXT NOT NULL, when_ms INTEGER NOT NULL, done INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE memories(id INTEGER PRIMARY KEY, fact TEXT NOT NULL UNIQUE)")
        db.execSQL("CREATE TABLE models(id INTEGER PRIMARY KEY, name TEXT NOT NULL, path TEXT NOT NULL UNIQUE, source TEXT NOT NULL, bytes INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE actions(id INTEGER PRIMARY KEY, chat_id INTEGER NOT NULL, text TEXT NOT NULL, time INTEGER NOT NULL)")
        createScheduledTasks(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createScheduledTasks(db)
    }
    private fun createScheduledTasks(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE scheduled_tasks(id INTEGER PRIMARY KEY, title TEXT NOT NULL, prompt TEXT NOT NULL, model_path TEXT NOT NULL, next_run_ms INTEGER NOT NULL, repeat_rule TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1, last_run_ms INTEGER, last_result TEXT, last_error TEXT)")
    }

    @Synchronized fun newChat(): Long = writableDatabase.insertOrThrow("chats", null, ContentValues().apply { put("title", "New chat") })
    @Synchronized fun chats(): List<Chat> = buildList {
        readableDatabase.rawQuery("SELECT id,title,web,yolo FROM chats ORDER BY id DESC", null).use { c -> while(c.moveToNext()) add(Chat(c.getLong(0), c.getString(1), c.getInt(2) != 0, c.getInt(3) != 0)) }
    }
    @Synchronized fun chat(id: Long) = chats().firstOrNull { it.id == id }
    @Synchronized fun setChatFlag(id: Long, flag: String, enabled: Boolean) {
        require(flag == "web" || flag == "yolo")
        writableDatabase.update("chats", ContentValues().apply { put(flag, if(enabled) 1 else 0) }, "id=?", arrayOf(id.toString()))
    }
    @Synchronized fun endChat(id: Long) = setChatFlag(id, "yolo", false)
    @Synchronized fun addMessage(chatId: Long, role: String, body: String): Long {
        val id = writableDatabase.insertOrThrow("messages", null, ContentValues().apply { put("chat_id", chatId); put("role", role); put("body", body); put("time", System.currentTimeMillis()) })
        if (role == "user") writableDatabase.execSQL("UPDATE chats SET title=? WHERE id=? AND title='New chat'", arrayOf<Any>(body.take(42), chatId))
        return id
    }
    @Synchronized fun messages(chatId: Long): List<Message> = buildList {
        readableDatabase.rawQuery("SELECT id,chat_id,role,body,time FROM messages WHERE chat_id=? ORDER BY id", arrayOf(chatId.toString())).use { c -> while(c.moveToNext()) add(Message(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getLong(4))) }
    }
    @Synchronized fun addNote(title: String, body: String): Long = writableDatabase.insertOrThrow("notes", null, ContentValues().apply { put("title", title); put("body", body) })
    @Synchronized fun notes(): List<Note> = buildList { readableDatabase.rawQuery("SELECT id,title,body FROM notes ORDER BY id DESC", null).use { c -> while(c.moveToNext()) add(Note(c.getLong(0), c.getString(1), c.getString(2))) } }
    @Synchronized fun deleteNote(id: Long) { writableDatabase.delete("notes", "id=?", arrayOf(id.toString())) }
    @Synchronized fun addReminder(title: String, whenMillis: Long): Long = writableDatabase.insertOrThrow("reminders", null, ContentValues().apply { put("title", title); put("when_ms", whenMillis); put("done", 0) })
    @Synchronized fun reminders(): List<Reminder> = buildList { readableDatabase.rawQuery("SELECT id,title,when_ms,done FROM reminders ORDER BY when_ms", null).use { c -> while(c.moveToNext()) add(Reminder(c.getLong(0), c.getString(1), c.getLong(2), c.getInt(3) != 0)) } }
    @Synchronized fun completeReminder(id: Long) { writableDatabase.update("reminders", ContentValues().apply { put("done", 1) }, "id=?", arrayOf(id.toString())) }
    @Synchronized fun addScheduledTask(title: String, prompt: String, modelPath: String, whenMillis: Long, repeat: String): Long =
        writableDatabase.insertOrThrow("scheduled_tasks", null, ContentValues().apply {
            put("title", title); put("prompt", prompt); put("model_path", modelPath); put("next_run_ms", whenMillis)
            put("repeat_rule", repeat); put("enabled", 1)
        })
    @Synchronized fun scheduledTasks(): List<ScheduledTask> = buildList {
        readableDatabase.rawQuery("SELECT id,title,prompt,model_path,next_run_ms,repeat_rule,enabled,last_run_ms,last_result,last_error FROM scheduled_tasks ORDER BY next_run_ms", null).use { c ->
            while (c.moveToNext()) add(ScheduledTask(c.getLong(0), c.getString(1), c.getString(2), c.getString(3),
                c.getLong(4), c.getString(5), c.getInt(6) != 0, if(c.isNull(7)) null else c.getLong(7),
                if(c.isNull(8)) null else c.getString(8), if(c.isNull(9)) null else c.getString(9)))
        }
    }
    @Synchronized fun scheduledTask(id: Long): ScheduledTask? = scheduledTasks().firstOrNull { it.id == id }
    @Synchronized fun setScheduledTaskEnabled(id: Long, enabled: Boolean, nextRunMillis: Long? = null) {
        writableDatabase.update("scheduled_tasks", ContentValues().apply {
            put("enabled", if(enabled) 1 else 0)
            if (nextRunMillis != null) put("next_run_ms", nextRunMillis)
        }, "id=?", arrayOf(id.toString()))
    }
    @Synchronized fun deleteScheduledTask(id: Long) { writableDatabase.delete("scheduled_tasks", "id=?", arrayOf(id.toString())) }
    @Synchronized fun finishScheduledTask(id: Long, runMillis: Long, result: String?, error: String?, nextMillis: Long?) {
        writableDatabase.update("scheduled_tasks", ContentValues().apply {
            put("last_run_ms", System.currentTimeMillis()); put("last_result", result); put("last_error", error)
            if (nextMillis == null) put("enabled", 0) else put("next_run_ms", nextMillis)
        }, "id=? AND next_run_ms=? AND enabled=1", arrayOf(id.toString(), runMillis.toString()))
    }
    @Synchronized fun addMemory(fact: String) { writableDatabase.insertWithOnConflict("memories", null, ContentValues().apply { put("fact", fact) }, SQLiteDatabase.CONFLICT_IGNORE) }
    @Synchronized fun memories(): List<Memory> = buildList { readableDatabase.rawQuery("SELECT id,fact FROM memories ORDER BY id DESC", null).use { c -> while(c.moveToNext()) add(Memory(c.getLong(0), c.getString(1))) } }
    @Synchronized fun updateMemory(id: Long, fact: String) { writableDatabase.update("memories", ContentValues().apply { put("fact", fact) }, "id=?", arrayOf(id.toString())) }
    @Synchronized fun deleteMemory(id: Long) { writableDatabase.delete("memories", "id=?", arrayOf(id.toString())) }
    @Synchronized fun addModel(name: String, path: String, source: String, bytes: Long) { writableDatabase.insertWithOnConflict("models", null, ContentValues().apply { put("name", name); put("path", path); put("source", source); put("bytes", bytes) }, SQLiteDatabase.CONFLICT_REPLACE) }
    @Synchronized fun models(): List<LocalModel> = buildList { readableDatabase.rawQuery("SELECT id,name,path,source,bytes FROM models ORDER BY id DESC", null).use { c -> while(c.moveToNext()) add(LocalModel(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4))) } }
    @Synchronized fun deleteModel(id: Long) { writableDatabase.delete("models", "id=?", arrayOf(id.toString())) }
    @Synchronized fun log(chatId: Long, text: String) { writableDatabase.insertOrThrow("actions", null, ContentValues().apply { put("chat_id", chatId); put("text", text); put("time", System.currentTimeMillis()) }) }
    @Synchronized fun actionLog(chatId: Long): List<String> = buildList { readableDatabase.rawQuery("SELECT text FROM actions WHERE chat_id=? ORDER BY id DESC LIMIT 30", arrayOf(chatId.toString())).use { c -> while(c.moveToNext()) add(c.getString(0)) } }
}
