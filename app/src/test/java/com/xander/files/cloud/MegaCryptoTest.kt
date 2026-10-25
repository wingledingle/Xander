package com.xander.files.cloud

import com.xander.files.data.cloud.MegaCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateCrtKey
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class MegaCryptoTest {
    @Test fun base64UrlRoundTrip() {
        val b = ByteArray(37) { (it * 7).toByte() }
        assertArrayEquals(b, MegaCrypto.unb64(MegaCrypto.b64(b)))
    }

    @Test fun a32RoundTrip() {
        val a = intArrayOf(1, -2, 0x7fffffff, Int.MIN_VALUE)
        assertArrayEquals(a, MegaCrypto.toA32(MegaCrypto.fromA32(a)))
    }

    @Test fun attributesRoundTrip() {
        val key = ByteArray(16) { it.toByte() }
        val enc = MegaCrypto.encryptAttr("Résumé 2024.pdf", key)
        assertEquals("Résumé 2024.pdf", MegaCrypto.decryptAttr(enc, key)["n"])
    }

    @Test fun fileKeyFoldsHalves() {
        val k = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        assertArrayEquals(MegaCrypto.fromA32(intArrayOf(1 xor 5, 2 xor 6, 3 xor 7, 4 xor 8)), MegaCrypto.fileKey(k))
    }

    @Test fun chunkSizesGrowTo1MiB() {
        val s = MegaCrypto.chunkSizes().take(10).toList()
        assertEquals(listOf(128, 256, 384, 512, 640, 768, 896, 1024, 1024, 1024).map { it * 1024 }, s)
    }

    @Test fun sessionIdFromRsaCsid() {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val priv = kp.private as RSAPrivateCrtKey
        fun mpi(v: BigInteger): ByteArray {
            val raw = v.toByteArray().let { if (it[0].toInt() == 0) it.copyOfRange(1, it.size) else it }
            val bits = v.bitLength()
            return byteArrayOf((bits shr 8).toByte(), bits.toByte()) + raw
        }
        val sidBytes = ByteArray(43) { (it + 1).toByte() } + ByteArray(212) { 9 }
        val m = BigInteger(1, sidBytes)
        val c = m.modPow(priv.publicExponent, priv.modulus)
        val privk = mpi(priv.primeP) + mpi(priv.primeQ) + mpi(priv.privateExponent) + mpi(priv.crtCoefficient)
        assertEquals(MegaCrypto.b64(sidBytes.copyOf(43)), MegaCrypto.sessionId(privk, mpi(c)))
    }

    @Test fun ctrAtOffsetMatchesStreamFromZero() {
        val key = ByteArray(16) { 3 }
        val nonce = intArrayOf(11, 22)
        val data = ByteArray(1000) { it.toByte() }
        val full = MegaCrypto.ctr(key, nonce, 0).doFinal(data)
        val tail = MegaCrypto.ctr(key, nonce, 512).doFinal(full.copyOfRange(512, 1000))
        assertArrayEquals(data.copyOfRange(512, 1000), tail)
    }

    @Test fun chunkMacEqualsCbcLastBlock() {
        val key = ByteArray(16) { 5 }; val nonce = intArrayOf(1, 2)
        val data = ByteArray(40) { it.toByte() }
        val mac = MegaCrypto.chunkMac(key, nonce, data, 40)
        val c = Cipher.getInstance("AES/CBC/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), javax.crypto.spec.IvParameterSpec(MegaCrypto.fromA32(intArrayOf(1, 2, 1, 2))))
        val out = c.doFinal(data.copyOf(48))
        assertArrayEquals(out.copyOfRange(32, 48), mac)
    }
}
