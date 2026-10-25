package com.xander.files.data.cloud

import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** MEGA's crypto primitives (a32 arrays, base64url, AES-ECB/CBC/CTR, key derivation, RSA session). */
object MegaCrypto {
    private val random = SecureRandom()

    fun b64(bytes: ByteArray): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun unb64(s: String): ByteArray = java.util.Base64.getUrlDecoder().decode(s.replace('+', '-').replace('/', '_').trimEnd('=').replace(",", ""))

    fun toA32(b: ByteArray): IntArray {
        val padded = if (b.size % 4 == 0) b else b.copyOf(b.size + (4 - b.size % 4))
        val bb = ByteBuffer.wrap(padded)
        return IntArray(padded.size / 4) { bb.int }
    }

    fun fromA32(a: IntArray): ByteArray = ByteBuffer.allocate(a.size * 4).apply { a.forEach { putInt(it) } }.array()

    fun aesEcb(key: ByteArray, data: ByteArray, encrypt: Boolean): ByteArray {
        val c = Cipher.getInstance("AES/ECB/NoPadding")
        c.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return c.doFinal(data)
    }

    fun aesCbc(key: ByteArray, data: ByteArray, encrypt: Boolean): ByteArray {
        val c = Cipher.getInstance("AES/CBC/NoPadding")
        c.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16)))
        return c.doFinal(data)
    }

    fun ctr(key: ByteArray, nonce: IntArray, offset: Long): Cipher {
        val iv = ByteBuffer.allocate(16).putInt(nonce[0]).putInt(nonce[1]).putLong(offset / 16).array()
        return Cipher.getInstance("AES/CTR/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }
    }

    /** v1 accounts: 65536 rounds of AES over the password. */
    fun prepareKey(password: String): ByteArray {
        val arr = toA32(password.toByteArray(Charsets.UTF_8))
        var pkey = fromA32(intArrayOf(0x93C467E3.toInt(), 0x7DB0C7A4, 0xD1BE3F81.toInt(), 0x0152CB56))
        val keys = (arr.indices step 4).map { j -> fromA32(IntArray(4) { k -> arr.getOrElse(j + k) { 0 } }) }
        repeat(0x10000) { for (k in keys) pkey = aesEcb(k, pkey, true) }
        return pkey
    }

    /** v1 user hash for "us" login. */
    fun stringHash(s: String, key: ByteArray): String {
        val s32 = toA32(s.toByteArray(Charsets.UTF_8))
        val h = IntArray(4)
        s32.forEachIndexed { i, v -> h[i % 4] = h[i % 4] xor v }
        var hb = fromA32(h)
        repeat(0x4000) { hb = aesEcb(key, hb, true) }
        val r = toA32(hb)
        return b64(fromA32(intArrayOf(r[0], r[2])))
    }

    fun pbkdf2Sha512(password: String, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(password.toByteArray(Charsets.UTF_8).let { if (it.isEmpty()) ByteArray(1) else it }, "HmacSHA512"))
        val out = ByteArray(length); var block = 1; var off = 0
        while (off < length) {
            mac.update(salt); mac.update(ByteBuffer.allocate(4).putInt(block).array())
            var u = mac.doFinal(); val t = u.copyOf()
            for (i in 1 until iterations) { u = mac.doFinal(u); for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte() }
            val n = minOf(t.size, length - off); System.arraycopy(t, 0, out, off, n); off += n; block++
        }
        return out
    }

    /** Decrypts a key blob with the master key (ECB per 16-byte block). */
    fun decryptKey(master: ByteArray, blob: ByteArray): ByteArray = aesEcb(master, blob, false)
    fun encryptKey(master: ByteArray, blob: ByteArray): ByteArray = aesEcb(master, blob, true)

    /** Reads MPIs (2-byte bit length + big-endian bytes). */
    fun mpis(b: ByteArray, count: Int): List<BigInteger> {
        var off = 0
        return (0 until count).map {
            val bits = ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)
            val len = (bits + 7) / 8
            val v = BigInteger(1, b.copyOfRange(off + 2, off + 2 + len))
            off += 2 + len
            v
        }
    }

    /** RSA-decrypts csid with the account's private key and returns the 43-byte session id. */
    fun sessionId(privk: ByteArray, csid: ByteArray): String {
        val (p, q, d) = mpis(privk, 4)
        val c = mpis(csid, 1)[0]
        val m = c.modPow(d, p.multiply(q))
        var bytes = m.toByteArray()
        if (bytes.isNotEmpty() && bytes[0].toInt() == 0) bytes = bytes.copyOfRange(1, bytes.size)
        return b64(bytes.copyOf(43))
    }

    /** File node keys are 8 ints: AES key = k[0..3] xor k[4..7], nonce = k[4..5], meta MAC = k[6..7]. */
    fun fileKey(k: IntArray): ByteArray = fromA32(intArrayOf(k[0] xor k[4], k[1] xor k[5], k[2] xor k[6], k[3] xor k[7]))

    fun decryptAttr(attr: String, key: ByteArray): Map<String, Any?> {
        val plain = String(aesCbc(key, unb64(attr), false), Charsets.UTF_8).trimEnd('\u0000')
        if (!plain.startsWith("MEGA")) return emptyMap()
        return runCatching {
            val j = cloudJson.parseToJsonElement(plain.substring(4)) as kotlinx.serialization.json.JsonObject
            j.mapValues { (it.value as? kotlinx.serialization.json.JsonPrimitive)?.content }
        }.getOrDefault(emptyMap())
    }

    fun encryptAttr(name: String, key: ByteArray): String {
        val json = kotlinx.serialization.json.buildJsonObject { put("n", kotlinx.serialization.json.JsonPrimitive(name)) }.toString()
        val raw = ("MEGA$json").toByteArray(Charsets.UTF_8)
        val padded = raw.copyOf(((raw.size + 15) / 16) * 16)
        return b64(aesCbc(key, padded, true))
    }

    fun randomInts(n: Int) = IntArray(n) { random.nextInt() }

    /** Upload chunk boundaries: 128K, 256K, ... 1M, then 1M each. */
    fun chunkSizes(): Sequence<Int> = sequence { var s = 128 * 1024; while (true) { yield(s); if (s < 1024 * 1024) s += 128 * 1024 } }

    /** CBC-MAC of one chunk with IV = nonce||nonce. */
    fun chunkMac(key: ByteArray, nonce: IntArray, data: ByteArray, len: Int): ByteArray {
        val c = Cipher.getInstance("AES/CBC/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(fromA32(intArrayOf(nonce[0], nonce[1], nonce[0], nonce[1]))))
        val padded = if (len % 16 == 0) data.copyOf(len) else data.copyOf(len + (16 - len % 16))
        val out = c.doFinal(padded)
        return out.copyOfRange(out.size - 16, out.size)
    }
}
