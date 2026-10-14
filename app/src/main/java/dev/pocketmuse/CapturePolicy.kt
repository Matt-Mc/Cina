package dev.pocketmuse

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal object CapturePolicy {
    const val MAX_BYTES = 8_000_000
    const val MAX_TEXT = 250_000
    fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_BYTES) { "Choose a file no larger than 8 MB." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    fun text(value: String): String {
        require(value.isNotBlank()) { "Add some text or a link first." }
        require(value.length <= MAX_TEXT) { "Choose text with at most 250,000 characters." }
        return value
    }
    fun decode(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
}
