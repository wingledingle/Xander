package com.xander.files.crypto

import com.xander.files.data.crypto.FileCrypto
import com.xander.files.data.crypto.NotEncryptedException
import com.xander.files.data.crypto.WrongPasswordException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class FileCryptoTest {
    private fun enc(data: ByteArray, pwd: String = "pw"): ByteArray =
        ByteArrayOutputStream().also { FileCrypto.encrypt(ByteArrayInputStream(data), it, pwd.toCharArray(), iterations = 1000) }.toByteArray()

    private fun dec(data: ByteArray, pwd: String = "pw"): ByteArray =
        ByteArrayOutputStream().also { FileCrypto.decrypt(ByteArrayInputStream(data), it, pwd.toCharArray()) }.toByteArray()

    @Test fun roundTripsEmptySmallAndMultiChunk() {
        for (size in listOf(0, 1, 17, FileCrypto.CHUNK - 1, FileCrypto.CHUNK, FileCrypto.CHUNK * 2 + 123)) {
            val data = Random(size).nextBytes(size)
            assertArrayEquals("size $size", data, dec(enc(data)))
        }
    }

    @Test fun ciphertextIsNotPlaintextAndIsSalted() {
        val data = "same content".toByteArray()
        val a = enc(data); val b = enc(data)
        assertTrue(!a.contentEquals(b))
        assertTrue(String(a).indexOf("same content") < 0)
        assertTrue(FileCrypto.isEncrypted(a))
    }

    @Test(expected = WrongPasswordException::class) fun wrongPasswordFails() { dec(enc("x".toByteArray(), "right"), "wrong") }

    @Test(expected = WrongPasswordException::class) fun tamperingIsDetected() {
        val c = enc(ByteArray(5000) { 1 })
        c[c.size - 20] = (c[c.size - 20].toInt() xor 1).toByte()
        dec(c)
    }

    @Test(expected = WrongPasswordException::class) fun truncationIsDetected() {
        val c = enc(Random(1).nextBytes(FileCrypto.CHUNK * 2))
        // Drop the final chunk entirely: the new last chunk was sealed as "not last".
        dec(c.copyOf(c.size - 100))
    }

    @Test(expected = NotEncryptedException::class) fun rejectsPlainFiles() { dec("hello world, not encrypted at all".toByteArray()) }

    @Test fun pbkdf2MatchesRfc7914TestVector() {
        // PBKDF2-HMAC-SHA256, P="passwd", S="salt", c=1, dkLen=64 (RFC 7914 section 11)
        val dk = FileCrypto.deriveKey("passwd".toCharArray(), "salt".toByteArray(), 1, 64)
        assertEquals("55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            dk.joinToString("") { "%02x".format(it) })
    }
}
