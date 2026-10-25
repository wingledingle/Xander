package com.xander.files.net

import com.xander.files.data.db.ConnectionDao
import com.xander.files.data.db.ConnectionEntity
import com.xander.files.data.net.ConnOptions
import com.xander.files.data.net.ConnType
import com.xander.files.data.net.Connection
import com.xander.files.data.net.ConnectionRepository
import com.xander.files.data.security.SecretCipher
import com.xander.files.domain.fs.FileSystemProvider
import com.xander.files.domain.fs.FsPath
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume

/** Integration tests need the servers from scripts/start-test-servers.sh and XANDER_IT=1. */
fun assumeServers() = Assume.assumeTrue("set XANDER_IT=1 and run scripts/start-test-servers.sh", System.getenv("XANDER_IT") == "1")

class FakeConnectionDao : ConnectionDao {
    private val rows = MutableStateFlow<Map<String, ConnectionEntity>>(emptyMap())
    override fun observeAll(): Flow<List<ConnectionEntity>> = rows.map { it.values.toList() }
    override suspend fun get(id: String) = rows.value[id]
    override suspend fun upsert(e: ConnectionEntity) { rows.value = rows.value + (e.id to e) }
    override suspend fun delete(id: String) { rows.value = rows.value - id }
}

object PlainCipher : SecretCipher {
    override fun encrypt(plain: String) = plain
    override fun decrypt(cipherText: String) = cipherText
}

fun repo() = ConnectionRepository(FakeConnectionDao(), PlainCipher)

suspend fun ConnectionRepository.add(type: ConnType, port: Int, path: String = "/", options: ConnOptions = ConnOptions(), user: String = "xander", pass: String = "xanderpw") =
    save(Connection("", type, type.name, "127.0.0.1", port, user, pass, "", path, options))

/** Shared contract every writable backend must satisfy. */
suspend fun exerciseProvider(p: FileSystemProvider, root: FsPath) {
    val dir = p.mkdir(root, "it-${System.nanoTime()}")
    try {
        assertTrue(p.stat(dir).isDirectory)
        val f = dir.child("hello.txt")
        p.openOutput(f, 11).use { it.write("hello world".toByteArray()) }
        assertEquals(11L, p.stat(f).size)
        assertEquals("hello world", p.openInput(f).use { String(it.readBytes()) })
        assertEquals("world", p.openInput(f, 6).use { String(it.readBytes()) })
        val big = ByteArray(3_000_000) { (it % 251).toByte() }
        p.openOutput(dir.child("big.bin"), big.size.toLong()).use { it.write(big) }
        assertTrue(big.contentEquals(p.openInput(dir.child("big.bin")).use { it.readBytes() }))
        val sub = p.mkdir(dir, "sub dir ü")
        val renamed = p.rename(f, "renamed.txt")
        assertEquals(setOf("renamed.txt", "big.bin", "sub dir ü"), p.list(dir).map { it.name }.toSet())
        val moved = p.moveInternal(renamed, sub, "moved.txt")
        if (moved != null) assertEquals("hello world", p.openInput(moved).use { String(it.readBytes()) })
        p.delete(sub)
        assertFalse(p.exists(sub))
    } finally {
        runCatching { p.delete(dir) }
    }
}
