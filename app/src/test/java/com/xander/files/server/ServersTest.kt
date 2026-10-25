package com.xander.files.server

import com.xander.files.data.server.FtpServer
import com.xander.files.data.server.ServerRoot
import com.xander.files.data.server.WebFileServer
import com.xander.files.domain.fs.FileSystemRegistry
import com.xander.files.domain.fs.FsPath
import com.xander.files.fs.MemoryFileSystemProvider
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream

class ServersTest {
    private val mem = MemoryFileSystemProvider("mem")
    private val root = ServerRoot(FsPath("mem", "", "/share"), FileSystemRegistry(setOf(mem)), readOnly = false)
    private lateinit var ftp: FtpServer
    private lateinit var web: WebFileServer

    @Before fun setUp() {
        mem.put("/share/docs/a.txt", "hello world")
        mem.put("/secret.txt", "outside root")
        ftp = FtpServer(root, 0, "u", "p").apply { start() }
        web = WebFileServer(root, "u", "p", 0).apply { start() }
    }

    @After fun tearDown() { ftp.stop(); web.stop() }

    @Test fun ftpListDownloadUploadRenameDelete() {
        val c = FTPClient()
        c.connect("127.0.0.1", ftp.localPort)
        assertFalse(c.login("u", "wrong"))
        assertTrue(c.login("u", "p"))
        c.enterLocalPassiveMode(); c.setFileType(FTP.BINARY_FILE_TYPE)
        assertTrue(c.changeWorkingDirectory("docs"))
        assertEquals("/docs", c.printWorkingDirectory())
        assertEquals(listOf("a.txt"), c.listFiles().map { it.name })
        assertEquals(listOf("a.txt"), c.mlistDir().map { it.name })
        val out = ByteArrayOutputStream(); assertTrue(c.retrieveFile("a.txt", out)); assertEquals("hello world", out.toString())
        c.setRestartOffset(6); val part = ByteArrayOutputStream(); assertTrue(c.retrieveFile("a.txt", part)); assertEquals("world", part.toString())
        assertTrue(c.storeFile("b.bin", "uploaded".byteInputStream()))
        assertEquals("uploaded", mem.read("/share/docs/b.bin"))
        assertTrue(c.appendFile("b.bin", "+more".byteInputStream()))
        assertEquals("uploaded+more", mem.read("/share/docs/b.bin"))
        assertTrue(c.rename("b.bin", "c.bin"))
        assertEquals("uploaded+more", mem.read("/share/docs/c.bin"))
        assertTrue(c.makeDirectory("sub"))
        assertTrue(c.deleteFile("c.bin"))
        assertEquals(null, mem.read("/share/docs/c.bin"))
        // Cannot escape the shared folder.
        assertTrue(c.changeWorkingDirectory("/../.."))
        assertEquals("/", c.printWorkingDirectory())
        assertFalse(c.retrieveFile("../secret.txt", ByteArrayOutputStream()))
        c.logout(); c.disconnect()
    }

    @Test fun httpBrowseRangeUploadAuth() {
        val base = "http://127.0.0.1:${web.port}"
        val http = OkHttpClient()
        http.newCall(Request.Builder().url("$base/").build()).execute().use { assertEquals(401, it.code) }
        val auth = Credentials.basic("u", "p")
        fun get(path: String, range: String? = null) = http.newCall(Request.Builder().url(base + path).header("Authorization", auth).apply { range?.let { header("Range", it) } }.build()).execute()
        get("/docs/").use { r -> assertEquals(200, r.code); assertTrue(r.body!!.string().contains("a.txt")) }
        get("/docs/a.txt").use { assertEquals("hello world", it.body!!.string()) }
        get("/docs/a.txt", "bytes=6-").use { r -> assertEquals(206, r.code); assertEquals("bytes 6-10/11", r.header("Content-Range")); assertEquals("world", r.body!!.string()) }
        get("/../secret.txt").use { assertEquals(404, it.code) }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("f", "up1.txt", "first file\r\n--not a boundary".toRequestBody("text/plain".toMediaType()))
            .addFormDataPart("f", "up2.bin", ByteArray(200_000) { (it % 251).toByte() }.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        http.newCall(Request.Builder().url("$base/docs/").header("Authorization", auth).post(body).build()).execute().use { assertEquals(200, it.code) }
        assertEquals("first file\r\n--not a boundary", mem.read("/share/docs/up1.txt"))
        assertTrue(mem.files["/share/docs/up2.bin"]!!.contentEquals(ByteArray(200_000) { (it % 251).toByte() }))
        http.newCall(Request.Builder().url("$base/docs/?mkdir=New%20Folder").header("Authorization", auth).post(ByteArray(0).toRequestBody()).build()).execute().use { assertEquals(200, it.code) }
        assertTrue("/share/docs/New Folder" in mem.dirs)
        http.newCall(Request.Builder().url("$base/docs/?delete=up1.txt").header("Authorization", auth).post(ByteArray(0).toRequestBody()).build()).execute().use { assertEquals(200, it.code) }
        assertEquals(null, mem.read("/share/docs/up1.txt"))
    }
}

class MediaStreamServerTest {
    @Test fun srtConvertsToVtt() {
        val vtt = com.xander.files.data.cast.MediaStreamServer.srtToVtt("﻿1\r\n00:00:01,500 --> 00:00:03,250\r\nHi\r\n")
        assertEquals("WEBVTT\n\n1\n00:00:01.500 --> 00:00:03.250\nHi\n", vtt)
    }
}
