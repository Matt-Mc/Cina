package dev.pocketmuse

import org.junit.Assert.*
import org.junit.Test

class LibraryBackupCryptoTest {
    private val password="a long test passphrase".toCharArray()
    @Test fun roundTripAndFreshRandomness() {
        val payload="Private original text".toByteArray()
        val first=LibraryBackupCrypto.encrypt(payload,password)
        val second=LibraryBackupCrypto.encrypt(payload,password)
        assertFalse(first.contentEquals(second))
        assertArrayEquals(payload,LibraryBackupCrypto.decrypt(first.inputStream(),password))
        assertFalse(String(first).contains("Private original text"))
    }
    @Test fun wrongPasswordTamperingAndTruncationAreRejected() {
        val encrypted=LibraryBackupCrypto.encrypt("payload".toByteArray(),password)
        assertTrue(runCatching { LibraryBackupCrypto.decrypt(encrypted.inputStream(),"another long password".toCharArray()) }.isFailure)
        listOf(0,15,30,encrypted.lastIndex).forEach { index ->
            val changed=encrypted.copyOf();changed[index]=(changed[index].toInt() xor 1).toByte()
            assertTrue(runCatching { LibraryBackupCrypto.decrypt(changed.inputStream(),password) }.isFailure)
        }
        assertTrue(runCatching { LibraryBackupCrypto.decrypt(encrypted.copyOf(encrypted.size-1).inputStream(),password) }.isFailure)
    }
    @Test fun shortPassphrasesAndOversizeFilesAreRejected() {
        assertTrue(runCatching { LibraryBackupCrypto.encrypt(byteArrayOf(1),"short".toCharArray()) }.isFailure)
        val huge=object:java.io.InputStream() {
            override fun read()=0
            override fun read(b: ByteArray,off: Int,len: Int): Int { b.fill(0,off,off+len);return len }
        }
        assertTrue(runCatching { LibraryBackupCrypto.decrypt(huge,password) }.isFailure)
    }
}
