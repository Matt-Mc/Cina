package dev.pocketmuse

import org.junit.Assert.*
import org.junit.Test

class ModelChoicesTest {
    @Test fun smallChatModelsAreNotHiddenByTheAgentBenchmarkMinimum() {
        assertTrue(ModelChoices.fits(ModelSlot.PHONE, 350_000_000, 3_600_000_000))
        assertFalse(ModelChoices.fits(ModelSlot.PHONE, 0, 3_600_000_000))
        assertFalse(ModelChoices.fits(ModelSlot.PHONE, -1, 3_600_000_000))
    }
    @Test fun heavyPhoneGetsALargerBudgetAndStillAllowsTheSameSmallModel() {
        assertFalse(ModelChoices.fits(ModelSlot.PHONE, 3_000_000_000, 3_600_000_000))
        assertTrue(ModelChoices.fits(ModelSlot.HEAVY_PHONE, 3_000_000_000, 3_600_000_000))
        assertTrue(ModelChoices.fits(ModelSlot.HEAVY_PHONE, 350_000_000, 3_600_000_000))
        assertFalse(ModelChoices.fits(ModelSlot.HEAVY_PHONE, 4_000_000_000, 3_600_000_000))
    }
    @Test fun hubFilesKeepNestedDownloadsAndLfsSizesButExcludeShardsAndProjectors() {
        val result = ModelChoices.files("owner/model", """[
          {"type":"file","path":"small/model Q4_K_M.gguf","size":600000000},
          {"type":"file","path":"model-Q8.gguf","lfs":{"size":1200000000}},
          {"type":"file","path":"model-00001-of-00002.gguf","size":900000000},
          {"type":"file","path":"mmproj-model.gguf","size":400000000},
          {"type":"directory","path":"folder.gguf"},
          {"type":"file","path":"README.md","size":50}
        ]""")
        assertEquals(2, result.size)
        assertEquals(1_200_000_000L, result[1].size)
        assertEquals("https://huggingface.co/owner/model/resolve/main/small/model%20Q4_K_M.gguf", result[0].url)
    }
    @Test fun customHomeNamesMatchTheServersSupportedLocalNames() {
        listOf("qwen3:4b", "gemma3:4b", "my-model").forEach { assertTrue(ModelChoices.validHomeName(it)) }
        listOf("", "qwen3::4b", "hf.co/owner/model", "https://host/model", "model:cloud", "has spaces").forEach { assertFalse(ModelChoices.validHomeName(it)) }
    }
    @Test fun homeHubSourcesUseExactRootFilenamesAndOnlyThePinnedHost() {
        val model = RemoteModel("Qwen/Qwen3-1.7B-GGUF", "Qwen3-1.7B-Q8_0.gguf", size = 1_834_426_016)
        assertEquals("hf.co/Qwen/Qwen3-1.7B-GGUF:Qwen3-1.7B-Q8_0.gguf", ModelChoices.homeName(model))
        assertNull(ModelChoices.homeName(model.copy(file = "nested/model.gguf")))
        assertNull(ModelChoices.homeName(model.copy(file = "model name.gguf")))
        assertNull(ModelChoices.homeName(model.copy(file = "x".repeat(80) + ".gguf")))
        listOf("hf.co/owner/repo:weights.gguf", "qwen3:4b").forEach { assertTrue(ModelChoices.validHomeName(it)) }
        listOf("http://hf.co/owner/repo:model.gguf", "evil.test/owner/repo:model.gguf", "hf.co/owner/repo:latest", "hf.co/owner/repo:../secret.gguf").forEach { assertFalse(ModelChoices.validHomeName(it)) }
    }
}
