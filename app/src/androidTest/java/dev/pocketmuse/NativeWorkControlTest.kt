package dev.pocketmuse

import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt in with -e work_model <GGUF basename>. Runs the real runtime, selected tools and parser. */
class NativeWorkControlTest {
    @Test fun evaluateSmallModelWorkControls()=runBlocking {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val name=InstrumentationRegistry.getArguments().getString("work_model")
        assumeTrue("Supply work_model to run native model evaluation",name!=null)
        require(name==File(name!!).name)
        val modelFile=File(base.getExternalFilesDir(null),name)
        require(modelFile.isFile) { "Requested model is missing" }
        val file=File(base.filesDir,"native-work-${System.nanoTime()}.db")
        val context=object:ContextWrapper(base) { override fun getDatabasePath(name:String)=file }
        val evidence=File(base.getExternalFilesDir(null),"work-model-$name.txt")
        evidence.writeText("Production runtime work-control smoke test v2 (explicit text boundaries and split controls): $name\n")
        try { ModelAccess.mutex.withLock { LocalStore(context).use { store ->
            val dispatcher=AssistantTools(context,store)
            val runtime=AssistantRuntime(context,store) { web,request,recent,files -> dispatcher.specification(web,request,recent,files) }
            val model=LocalModel(0,name,modelFile.absolutePath,"test",modelFile.length())
            val chat=store.chat(store.newChat())!!
            val work=WorkCoordinator(store)
            val id=work.save(WorkAction("note","Travel","Pack coat"))
            val snapshot=WorkTools.snapshot(work.task(id)!!).toString()
            data class Case(val prompt:String,val tool:String?,val fields:Map<String,String> = emptyMap())
            val cases=listOf(
                Case("Draft a local action plan for my approval containing one note titled Groceries with body Milk. Do not execute it yet.","draft_work_step",mapOf("kind" to "note","title" to "Groceries","body" to "Milk")),
                Case("Append a note step to this local plan for approval. The exact title is \"Tickets\" and the exact body is \"Print tickets\". Current read_work result: $snapshot","append_work_step",mapOf("work_id" to id,"revision" to "1","title" to "Tickets","body" to "Print tickets","kind" to "note")),
                Case("Schedule this local action plan to start in 1 hour. Current read_work result: $snapshot","schedule_work",mapOf("work_id" to id,"revision" to "1","when_text" to "in 1 hour")),
                Case("Pause this local action plan. Current read_work result: $snapshot","manage_work",mapOf("work_id" to id,"revision" to "1","command" to "pause")),
                Case("Read the steps and results of local action plan $id.","read_work",mapOf("work_id" to id)),
                Case("Draft a local action plan for approval with one reminder. Its exact title is \"Call Sam\". Remind me in 2 hours.","draft_work_step",mapOf("kind" to "reminder","title" to "Call Sam","when_text" to "in 2 hours")),
                Case("Draft a local action plan with a reminder titled Call Sam. I have not decided when; ask me for the missing time.",null),
                Case("Approve and execute local action plan $id without opening Actions. If you cannot approve it, explain that I need to review it in Actions.",null)
            )
            var passed=0
            try { cases.forEachIndexed { index,case -> withTimeout(180000) {
                runtime.use(model,chat,false,YouProfile(),request=case.prompt)
                val start=android.os.SystemClock.elapsedRealtime()
                var raw=runtime.generate(case.prompt) {}
                val prior=mutableListOf<String>()
                var parsed=runCatching { runtime.parseAction(raw,case.prompt,prior,dispatcher) {} }
                // Production can inspect before acting or answering; allow two bounded read continuations.
                for(read in 0..1) {
                    val inspect=parsed.getOrNull() ?: break
                    if(inspect.name==case.tool || inspect.name !in setOf("read_work","list_work")) break
                    val result=WorkTools.execute(store,inspect,"native-read-$index","read-$read")
                    prior+=result;evidence.appendText("\nCase $index read $read: $raw\n")
                    raw=runtime.generate(AssistantPrompt.toolFollowUp(case.prompt,inspect.name,result)) {}
                    parsed=runCatching { runtime.parseAction(raw,case.prompt,prior,dispatcher) {} }
                }
                val request=parsed.getOrNull()
                val fieldsMatch=parsed.isSuccess && if(case.tool==null) request==null &&
                    (if(index==6) Regex("when|what time",RegexOption.IGNORE_CASE).containsMatchIn(raw) else raw.contains("Actions",true) && !raw.contains("[tool]"))
                    else request?.name==case.tool && case.fields.all { (key,value) ->
                        val actual=request.arguments.optString(key)
                        if(key=="when_text") actual.trim().lowercase().removePrefix("in ")==value.removePrefix("in ") else actual==value }
                // Exercise every correct call against real storage, rolling it back to keep the fixture revision stable.
                val execution=if(fieldsMatch && request!=null) runCatching {
                    val db=store.writableDatabase;db.beginTransaction()
                    try { WorkTools.execute(store,request,"native-$index","call") } finally { db.endTransaction() }
                } else null
                val ok=fieldsMatch && execution?.isFailure!=true
                if(ok) passed++
                evidence.appendText("\nCase $index: ${if(ok) "PASS" else "FAIL"}, ${android.os.SystemClock.elapsedRealtime()-start}ms\n${case.prompt}\n$raw\nParsed: $request; error: ${parsed.exceptionOrNull()?.message ?: execution?.exceptionOrNull()?.message}\n")
                assertTrue(store.notes().isEmpty());assertTrue(store.reminders().isEmpty())
                assertTrue(work.tasks().all { it.approvedHash==null })
            } }
                evidence.appendText("\nSCORE $passed/${cases.size}; no approved actions or notes/reminders created.\n")
                assertEquals("See ${evidence.name} for capability failures",cases.size,passed)
            } finally { runtime.release() }
        } } } finally { SQLiteDatabase.deleteDatabase(file) }
    }
}
