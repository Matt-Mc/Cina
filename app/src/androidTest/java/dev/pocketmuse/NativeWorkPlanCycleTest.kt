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

/** A real continuation must reuse the returned plan ID, then stop after two unapproved steps. */
class NativeWorkPlanCycleTest {
    @Test fun twoStepDraftUsesReturnedIdentityAndStops()=runBlocking {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val name=InstrumentationRegistry.getArguments().getString("work_model")
        assumeTrue("Supply work_model for the native continuation test",name!=null)
        require(name==File(name!!).name)
        val model=File(base.getExternalFilesDir(null),name);require(model.isFile)
        val file=File(base.filesDir,"native-work-cycle-${System.nanoTime()}.db")
        val context=object:ContextWrapper(base) { override fun getDatabasePath(name:String)=file }
        val evidence=File(base.getExternalFilesDir(null),"work-cycle-$name.txt")
        evidence.writeText("Production continuation test: $name\n")
        try { ModelAccess.mutex.withLock { LocalStore(context).use { store ->
            val tools=AssistantTools(context,store)
            val runtime=AssistantRuntime(context,store) { web,request,recent,files -> tools.specification(web,request,recent,files) }
            val original="Draft one local action plan for my approval with two note steps. First note: exact title \"Packing\", exact body \"Bring coat\". Second note: exact title \"Tickets\", exact body \"Print tickets\". Do not execute the plan."
            try { withTimeout(240000) {
                runtime.use(LocalModel(0,name,model.absolutePath,"test",model.length()),store.chat(store.newChat())!!,false,YouProfile(),original)
                var raw=runtime.generate(original) {};val results=mutableListOf<String>();var stopped=false
                for(turn in 0..3) {
                    evidence.appendText("\nTurn $turn: $raw\n")
                    val request=runtime.parseAction(raw,original,results,tools)
                    if(request==null) { stopped=true;break }
                    require(request.name in setOf("draft_work_step","append_work_step","read_work")) { "Unexpected tool ${request.name}" }
                    val existing=WorkCoordinator(store).tasks()
                    if(existing.isNotEmpty()) require(request.arguments.optString("work_id")==existing.single().id) { "Continuation must append to the returned plan" }
                    val result=WorkTools.execute(store,request,"cycle",tools.callKey(request))
                    results+=result;evidence.appendText("Result: $result\n")
                    raw=runtime.generateToolFollowUp(original,request.name,result) {}
                }
                val task=WorkCoordinator(store).tasks().single()
                assertTrue("Model must stop after drafting",stopped)
                assertEquals(listOf(WorkAction("note","Packing","Bring coat"),WorkAction("note","Tickets","Print tickets")),task.steps.map { it.action })
                assertEquals("waiting_for_approval",task.state);assertNull(task.approvedHash)
                assertTrue(store.notes().isEmpty());assertTrue(store.reminders().isEmpty())
                evidence.appendText("\nPASS: exactly two ordered draft steps, no execution.\n")
            } } catch(e:Exception) { evidence.appendText("\nFAILED: ${e.message}\n");throw e }
            finally { runtime.release() }
        } } } finally { SQLiteDatabase.deleteDatabase(file) }
    }
}
