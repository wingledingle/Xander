package com.xander.files.data.crypto

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class WrongPasswordException : IOException("Wrong password or damaged file")
class NotEncryptedException : IOException("Not an encrypted file")

/**
 * Streaming file encryption: AES-256-GCM over 1 MiB chunks, key from PBKDF2-HMAC-SHA256.
 *
 * Layout: "XENC" | ver(1) | iterations(int) | salt(16) | nonce(12) | chunkSize(int) | chunk*  where each chunk is
 * GCM(ciphertext+16-byte tag) with nonce = base nonce XOR chunk index and AAD = header || lastChunkFlag,
 * so chunks cannot be reordered, dropped or truncated without detection.
 */
object FileCrypto {
    private val MAGIC = byteArrayOf('X'.code.toByte(), 'E'.code.toByte(), 'N'.code.toByte(), 'C'.code.toByte())
    private const val VERSION: Byte = 1
    const val DEFAULT_ITERATIONS = 120_000
    const val CHUNK = 1 shl 20
    private const val TAG = 16
    private val random = SecureRandom()

    fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int, length: Int = 32): ByteArray {
        // PBKDF2 (RFC 8018) with HMAC-SHA256, implemented on Mac so it works on every API level.
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(String(password).toByteArray(Charsets.UTF_8).let { if (it.isEmpty()) ByteArray(1) else it }, "HmacSHA256"))
        val out = ByteArray(length)
        var block = 1; var off = 0
        while (off < length) {
            mac.update(salt); mac.update(ByteBuffer.allocate(4).putInt(block).array())
            var u = mac.doFinal()
            val t = u.copyOf()
            for (i in 1 until iterations) { u = mac.doFinal(u); for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte() }
            val n = minOf(t.size, length - off)
            System.arraycopy(t, 0, out, off, n); off += n; block++
        }
        return out
    }

    private fun nonceFor(base: ByteArray, index: Long): ByteArray {
        val n = base.copyOf()
        for (i in 0 until 8) n[11 - i] = (n[11 - i].toInt() xor ((index ushr (8 * i)) and 0xFF).toInt()).toByte()
        return n
    }

    fun isEncrypted(header: ByteArray) = header.size >= 4 && header.copyOfRange(0, 4).contentEquals(MAGIC)

    fun encrypt(input: InputStream, output: OutputStream, password: CharArray, iterations: Int = DEFAULT_ITERATIONS, onBytes: (Long) -> Unit = {}, checkpoint: () -> Unit = {}) {
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val header = ByteBuffer.allocate(4 + 1 + 4 + 16 + 12 + 4).put(MAGIC).put(VERSION).putInt(iterations).put(salt).put(nonce).putInt(CHUNK).array()
        output.write(header)
        val key = SecretKeySpec(deriveKey(password, salt, iterations), "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val pin = PushbackInputStream(input, 1)
        val buf = ByteArray(CHUNK)
        var index = 0L
        while (true) {
            checkpoint()
            val n = readFully(pin, buf)
            val next = pin.read()
            val last = next < 0
            if (!last) pin.unread(next)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG * 8, nonceFor(nonce, index)))
            cipher.updateAAD(header); cipher.updateAAD(byteArrayOf(if (last) 1 else 0))
            output.write(cipher.doFinal(buf, 0, n))
            onBytes(n.toLong())
            index++
            if (last) break
        }
        output.flush()
    }

    fun decrypt(input: InputStream, output: OutputStream, password: CharArray, onBytes: (Long) -> Unit = {}, checkpoint: () -> Unit = {}) {
        val din = DataInputStream(input)
        val header = ByteArray(4 + 1 + 4 + 16 + 12 + 4)
        try { din.readFully(header) } catch (e: EOFException) { throw NotEncryptedException() }
        if (!isEncrypted(header) || header[4] != VERSION) throw NotEncryptedException()
        val bb = ByteBuffer.wrap(header, 5, header.size - 5)
        val iterations = bb.int
        val salt = ByteArray(16).also { bb.get(it) }
        val nonce = ByteArray(12).also { bb.get(it) }
        val chunk = bb.int
        if (chunk <= 0 || chunk > (64 shl 20) || iterations <= 0) throw NotEncryptedException()
        val key = SecretKeySpec(deriveKey(password, salt, iterations), "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val pin = PushbackInputStream(din, 1)
        val buf = ByteArray(chunk + TAG)
        var index = 0L
        while (true) {
            checkpoint()
            val n = readFully(pin, buf)
            val next = pin.read()
            val last = next < 0
            if (!last) pin.unread(next)
            if (n < TAG) throw WrongPasswordException()
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG * 8, nonceFor(nonce, index)))
            cipher.updateAAD(header); cipher.updateAAD(byteArrayOf(if (last) 1 else 0))
            val plain = try { cipher.doFinal(buf, 0, n) } catch (e: AEADBadTagException) { throw WrongPasswordException() }
            output.write(plain)
            onBytes(n.toLong())
            index++
            if (last) break
        }
        output.flush()
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val r = input.read(buf, off, buf.size - off)
            if (r < 0) break
            off += r
        }
        return off
    }
}
