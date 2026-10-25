package dev.pocketmuse

import org.junit.Assert.*
import org.junit.Test

class UiUxPolicyTest {
    @Test fun upcomingInterleavesTypesChronologically() {
        val rows=listOf(
            UpcomingWork("action","a","Tomorrow",300,""),
            UpcomingWork("reminder","r","Soon",100,""),
            UpcomingWork("schedule","s","Later",200,""))
        assertEquals(listOf("r","s","a"),orderedUpcoming(rows).map { it.id })
    }
    @Test fun starterExcludesUnsuitableFilesBeforeRanking() {
        val large=RemoteModel("popular/repo","large.Q4_K_M.gguf",size=4_000)
        val small=RemoteModel("other/repo","small.Q4_K_M.gguf",size=1_000)
        val options=listOf(PopularGgufModel("popular/repo",100,0,listOf(large)),PopularGgufModel("other/repo",50,0,listOf(small)))
        assertEquals(small,starterChoice(options) { it.size<=1_500 })
        assertNull(starterChoice(options) { false })
    }
}
