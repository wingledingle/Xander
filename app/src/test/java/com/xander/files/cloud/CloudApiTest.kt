package com.xander.files.cloud

import com.xander.files.data.cloud.Dropbox
import com.xander.files.data.cloud.GoogleDrive
import com.xander.files.data.cloud.TokenSource
import com.xander.files.domain.fs.FsPath
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Exercises the real cloud clients against a scripted server (all hosts are rewritten to MockWebServer). */
class CloudApiTest {
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private val seen = CopyOnWriteArrayList<RecordedRequest>()
    private var routes: (RecordedRequest) -> MockResponse = { MockResponse().setResponseCode(404) }

    private val tokens = object : TokenSource {
        override val accountId = "acc"
        var refreshed = 0
        override suspend fun token(forceRefresh: Boolean): String { if (forceRefresh) refreshed++; return if (refreshed > 0) "fresh" else "stale" }
    }

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse { seen += request; return routes(request) }
        }
        server.start()
        val base = server.url("/")
        http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val orig = chain.request()
            val u = orig.url.newBuilder().scheme("http").host(base.host).port(base.port)
                .encodedPath("/" + orig.url.host + orig.url.encodedPath).build()
            chain.proceed(orig.newBuilder().url(u).header("X-Orig-Host", orig.url.host).build())
        }).build()
    }

    @After fun tearDown() = server.shutdown()

    private fun json(s: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(s)

    @Test fun dropboxListsWithPaginationAndRefreshesTokenOn401() = runBlocking {
        routes = { r ->
            when {
                r.getHeader("Authorization") == "Bearer stale" -> MockResponse().setResponseCode(401)
                r.path!!.endsWith("/files/list_folder") -> json("""{"entries":[{".tag":"folder","name":"Photos","id":"id:1"}],"cursor":"c1","has_more":true}""")
                r.path!!.endsWith("/files/list_folder/continue") -> json("""{"entries":[{".tag":"file","name":"a.txt","size":5,"server_modified":"2024-01-02T03:04:05Z","id":"id:2"}],"cursor":"c2","has_more":false}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val d = Dropbox(http, tokens)
        val list = d.list(FsPath("cloud", "acc", "/"))
        assertEquals(listOf("Photos", "a.txt"), list.map { it.name })
        assertTrue(list[0].isDirectory)
        assertEquals(5L, list[1].size)
        assertEquals(1, tokens.refreshed)
    }

    @Test fun dropboxLargeUploadUsesSession() = runBlocking {
        tokens.refreshed = 1
        val bodies = CopyOnWriteArrayList<Long>()
        routes = { r ->
            bodies += r.bodySize
            when {
                r.path!!.endsWith("/upload_session/start") -> json("""{"session_id":"S1"}""")
                r.path!!.endsWith("/upload_session/append_v2") -> json("null")
                r.path!!.endsWith("/upload_session/finish") -> json("""{"name":"big.bin"}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val size = 20L * 1024 * 1024 + 123
        Dropbox(http, tokens).openOutput(FsPath("cloud", "acc", "/big.bin"), size).use { o ->
            val buf = ByteArray(1 shl 20); var left = size
            while (left > 0) { val n = minOf(left, buf.size.toLong()).toInt(); o.write(buf, 0, n); left -= n }
        }
        val paths = seen.map { it.path!!.substringAfterLast("/2/") }
        assertEquals(listOf("files/upload_session/start", "files/upload_session/append_v2", "files/upload_session/finish"), paths)
        assertEquals(size, bodies.sum())
        assertTrue(seen.last().getHeader("Dropbox-API-Arg")!!.contains("\"offset\":16777216"))
    }

    @Test fun googleDriveResolvesPathsAndUploadsResumably() = runBlocking {
        tokens.refreshed = 1
        routes = { r ->
            val p = r.requestUrl!!
            when {
                p.encodedPath.endsWith("/drive/v3/files") && r.method == "GET" -> {
                    val q = p.queryParameter("q")!!
                    if (q.startsWith("'root'")) json("""{"files":[{"id":"F1","name":"Docs","mimeType":"application/vnd.google-apps.folder"}]}""")
                    else json("""{"files":[{"id":"F2","name":"Report","mimeType":"application/vnd.google-apps.document"},{"id":"F3","name":"x.bin","mimeType":"application/octet-stream","size":"7"}]}""")
                }
                p.encodedPath.endsWith("/upload/drive/v3/files") -> MockResponse().setHeader("Location", server.url("/session/1").toString())
                p.encodedPath.endsWith("/session/1") -> MockResponse().setResponseCode(200)
                else -> MockResponse().setResponseCode(404)
            }
        }
        val g = GoogleDrive(http, tokens)
        val root = FsPath("cloud", "acc", "/")
        assertEquals(listOf("My Drive", "Shared drives", "Shared with me"), g.list(root).map { it.name })
        val docs = g.list(root.child("My Drive").child("Docs"))
        // Native Google Docs are presented with the export extension.
        assertEquals(listOf("Report.docx", "x.bin"), docs.map { it.name })
        g.openOutput(root.child("My Drive").child("Docs").child("new.txt"), 3).use { it.write("abc".toByteArray()) }
        val put = seen.last { it.method == "PUT" }
        assertEquals("bytes 0-2/3", put.getHeader("Content-Range"))
        val init = seen.first { it.requestUrl!!.encodedPath.endsWith("/upload/drive/v3/files") }
        assertTrue(init.body.readUtf8().contains("\"parents\":[\"F1\"]"))
    }
}
