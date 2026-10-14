package dev.pocketmuse

import org.junit.Assert.*
import org.junit.Test

class WorkActionTest {
    @Test fun approvalFingerprintBindsEveryArgument() {
        val base=WorkAction("note","Title","Body")
        assertEquals(base.fingerprint(),base.copy().fingerprint())
        listOf(base.copy(title="Other"),base.copy(body="Other"),base.copy(kind="reminder",body="",whenMillis=100L)).forEach { assertNotEquals(base.fingerprint(),it.fingerprint()) }
        assertNotEquals(WorkAction("note","ab","c").fingerprint(),WorkAction("note","a","bc").fingerprint())
    }
    @Test fun policyRejectsUnsupportedOrAmbiguousActions() {
        listOf(WorkAction("send_email","Email","Body"),WorkAction("note","","Body"),WorkAction("reminder","Due",whenMillis=10),WorkAction("note","Title","Body",20)).forEach {
            assertTrue(runCatching { it.validate(10) }.isFailure)
        }
        WorkAction("reminder","Due",whenMillis=11).validate(10)
        WorkAction("note","Title","Body").validate(10)
    }
}
