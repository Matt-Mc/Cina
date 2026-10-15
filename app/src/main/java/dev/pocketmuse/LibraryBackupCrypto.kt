package dev.pocketmuse

import java.io.InputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Version 1: fixed KDF parameters; authenticated header, salt, nonce and payload. */
internal object LibraryBackupCrypto {
    const val MAX_PAYLOAD = 32 * 1024 * 1024
    private val magic = "CINA-BACKUP-1\n".toByteArray(Charsets.US_ASCII)
    private const val ITERATIONS = 600_000
    private fun cipher(mode: Int, password: CharArray, salt: ByteArray, nonce: ByteArray): Cipher {
        require(password.size in 12..1024) { "Use a passphrase of 12–1024 characters." }
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(magic + salt + nonce)
            }
        } finally { key.fill(0) }
    }
    fun encrypt(payload: ByteArray, password: CharArray): ByteArray {
        require(payload.size <= MAX_PAYLOAD) { "This library exceeds the 32 MiB backup limit." }
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        return magic + salt + nonce + cipher(Cipher.ENCRYPT_MODE,password,salt,nonce).doFinal(payload)
    }
    fun decrypt(input: InputStream, password: CharArray): ByteArray {
        val limit = MAX_PAYLOAD + magic.size + 28 + 16
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(65536)
        while (true) {
            val count = input.read(buffer,0,minOf(buffer.size,limit+1-output.size()))
            if(count < 0) break
            if(count == 0) { val byte = input.read(); if(byte < 0) break; output.write(byte) }
            else output.write(buffer,0,count)
            require(output.size() <= limit) { "Backup exceeds 32 MiB." }
        }
        val bytes = output.toByteArray()
        require(bytes.size in (magic.size + 28 + 16)..(MAX_PAYLOAD + magic.size + 28 + 16)) { "Backup is incomplete or exceeds 32 MiB." }
        require(bytes.copyOfRange(0,magic.size).contentEquals(magic)) { "Unsupported backup format." }
        val salt = bytes.copyOfRange(magic.size,magic.size+16)
        val nonce = bytes.copyOfRange(magic.size+16,magic.size+28)
        return try { cipher(Cipher.DECRYPT_MODE,password,salt,nonce).doFinal(bytes,magic.size+28,bytes.size-magic.size-28) }
        catch (e: javax.crypto.AEADBadTagException) { throw IllegalArgumentException("Incorrect passphrase or damaged backup.",e) }
    }
}
