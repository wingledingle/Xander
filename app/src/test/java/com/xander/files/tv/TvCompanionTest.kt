package com.xander.files.tv

import com.xander.files.data.server.MiniHttpServer
import com.xander.files.data.tv.TvApp
import com.xander.files.data.tv.TvAppsBackend
import com.xander.files.data.tv.TvClient
import com.xander.files.data.tv.TvCompanionCore
import com.xander.files.data.tv.TvInfo
import com.xander.files.data.tv.TvInputBackend
import com.xander.files.data.tv.TvKey
import com.xander.files.data.tv.TvPairing
import com.xander.files.domain.fs.FsException
import com.xander.files.domain.fs.FsPath
import com.xander.files.fs.MemoryFileSystemProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TvCompanionTest {
    private val mem = MemoryFileSystemProvider("file")
    private val keys = ArrayList<TvKey>()
    private val launched = ArrayList<String>()
    private var installedBytes = 0L
    private val pairing = object : TvPairing {
        val tokens = HashSet<String>(); var code = "123456"
        override val deviceName = "Living Room TV"
        override fun code() = code
        override fun failedAttempt() {}
        override fun issueToken(clientName: String) = "tok-$clientName".also { tokens += it; code = "654321" }
        override fun valid(token: String?) = token in tokens
    }
    private val apps = object : TvAppsBackend {
        override fun list() = listOf(TvApp("com.example.player", "Player", "1.2", 12, false, 1000, true))
        override fun icon(pkg: String) = byteArrayOf(1, 2, 3)
        override fun launch(pkg: String) = launched.add(pkg)
        override fun uninstall(pkg: String) = true
        override fun install(apk: File) = true.also { installedBytes = apk.length() }
        override fun apkFile(pkg: String): File? = null
    }
    private val input = object : TvInputBackend {
        override val available = true
        override fun key(key: TvKey) = keys.add(key)
        override fun text(text: String) = true
    }
    private lateinit var server: MiniHttpServer

    @Before fun setUp() {
        mem.put("/storage/emulated/0/Movies/a.mkv", "movie-bytes")
        val core = TvCompanionCore(mem, "file", { TvInfo("Living Room TV", "X", "14", "/storage/emulated/0", 100, 40, true) }, apps, input, pairing, createTempDir())
        server = MiniHttpServer(0, core::handle).also { it.start() }
    }

    @After fun tearDown() = server.stop()

    @Test fun pairBrowseTransferAppsAndRemote() {
        val anon = TvClient("127.0.0.1", server.localPort, null)
        assertEquals("Living Room TV", anon.hello().getString("name"))
        try { anon.pair("000000", "Phone"); fail() } catch (e: FsException) { }
        try { anon.list(FsPath("tv", "x", "/")); fail() } catch (e: FsException.AuthRequired) { }
        val (token, name) = anon.pair("123456", "Phone")
        assertEquals("Living Room TV", name)

        val c = TvClient("127.0.0.1", server.localPort, token)
        assertEquals(40L, c.info().free)
        val dir = FsPath("tv", "dev", "/storage/emulated/0/Movies")
        assertEquals(listOf("a.mkv"), c.list(dir).map { it.name })
        assertEquals(11L, c.stat(dir.child("a.mkv")).size)
        assertEquals("bytes", c.read(dir.child("a.mkv"), 6).use { String(it.readBytes()) })
        c.write(dir.child("b.txt"), 5).use { it.write("hello".toByteArray()) }
        assertEquals("hello", mem.read("/storage/emulated/0/Movies/b.txt"))
        c.rename(dir.child("b.txt"), "c.txt"); assertEquals("hello", mem.read("/storage/emulated/0/Movies/c.txt"))
        c.mkdir(dir, "Sub"); assertTrue("/storage/emulated/0/Movies/Sub" in mem.dirs)
        c.delete(dir.child("c.txt")); assertEquals(null, mem.read("/storage/emulated/0/Movies/c.txt"))
        try { c.stat(dir.child("missing")); fail() } catch (e: FsException.NotFound) { }

        assertEquals("Player", c.apps().single().label)
        c.launch("com.example.player"); assertEquals(listOf("com.example.player"), launched)
        c.install(ByteArray(4096).inputStream(), 4096); assertEquals(4096L, installedBytes)
        c.key(TvKey.HOME); c.key(TvKey.UP); assertEquals(listOf(TvKey.HOME, TvKey.UP), keys)
    }
}
