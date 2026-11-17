package com.xander.files.modules

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.util.Base64
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/** A web page from `assets/modules/<id>/` plus the native features it may use. */
data class WebPage(
    val id: String,
    val entry: String = "index.html",
    /** Turns on the native low-latency pad engine (XanderHost.pad*). */
    val lowLatencyAudio: Boolean = false,
    /** Gives the page a native video player laid over it (XanderHost.videoCmd); see [StudioVideo]. */
    val nativeVideo: Boolean = false,
)

object WebModuleHost {
    const val HOST = "appassets.androidplatform.net"
    const val ORIGIN = "https://$HOST"
    fun pageUrl(p: WebPage) = "$ORIGIN/assets/modules/${p.id}/${p.entry}"
}

class WebModuleViewModel(private val context: Context, val storage: HostStorage) : ViewModel() {
    val files = HostFiles(context, storage)
    private val extractor = AudioExtractor(context, files)
    private var padsOrNull: LowLatencyPads? = null
    val pads: LowLatencyPads get() = padsOrNull ?: LowLatencyPads(context).also { padsOrNull = it }
    private var videoOrNull: StudioVideo? = null
    @get:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    val video: StudioVideo get() = videoOrNull ?: StudioVideo(context, storage, files).also { videoOrNull = it }
    private val saves = ConcurrentHashMap<String, File>()
    private val decodeDir = File(context.cacheDir, "module-audio").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
    /** The launch file's JSON, looked up off the main thread (a network file's stat can take seconds). */
    private var launchJob: kotlinx.coroutines.Deferred<String>? = null
    var launchJson: String
        get() = launchJob?.let { j -> runCatching { runBlocking { j.await() } }.getOrDefault("") } ?: ""
        set(v) { launchJob = kotlinx.coroutines.CompletableDeferred(v) }
    /** Times the page's renderer died this session (shown to the user, and stops auto-reopening the file). */
    var crashes = 0
    private var launched: String? = null

    fun launch(file: String?) {
        if (file == null || file == launched) return
        launched = file
        launchJob = viewModelScope.async(Dispatchers.IO) { runCatching { files.register(file).toJson().toString() }.getOrDefault("") }
    }

    fun openPath(path: String): String = runCatching { runBlocking(Dispatchers.IO) { files.register(path).toJson().toString() } }.getOrDefault("")

    fun decode(token: String, startMs: Long, maxMs: Long, channels: Int, done: (JSONObject?, String?) -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        val f = files.get(token) ?: return@launch done(null, "Unknown file")
        try {
            // One decoded window per file is kept; the previous one is dropped to bound cache use.
            decodeDir.listFiles { x -> x.name.startsWith("${token}_") }?.forEach { it.delete() }
            val out = File(decodeDir, "${token}_${startMs}_$channels.wav")
            val r = extractor.extract(f, startMs, maxMs, out, channels)
            val wav = files.register(out, "audio/wav")
            done(JSONObject().put("url", wav.url).put("startSec", r.startMs / 1000.0).put("durationSec", r.durationMs / 1000.0)
                .put("sampleRate", r.sampleRate).put("channels", r.channels), null)
        } catch (e: Exception) {
            done(null, e.message ?: "Could not decode audio")
        }
    }

    /** A JPEG data URL of the frame at [ms], [width] px wide, from the platform decoder (no second <video> needed). */
    fun thumbnail(token: String, ms: Long, width: Int, done: (JSONObject?, String?) -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        val f = files.get(token) ?: return@launch done(null, "Unknown file")
        val mmr = android.media.MediaMetadataRetriever()
        var ds: StreamDataSource? = null
        try {
            when (val s = f.source) {
                is HostSource.Local -> mmr.setDataSource(s.file.absolutePath)
                is HostSource.Content -> mmr.setDataSource(context, s.uri)
                is HostSource.Fs -> storage.localFile(s.path)?.let { mmr.setDataSource(it) } ?: mmr.setDataSource(StreamDataSource(f, files).also { ds = it })
            }
            val src = mmr.getFrameAtTime(ms * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return@launch done(null, "No frame")
            val h = (width * src.height / src.width.coerceAtLeast(1)).coerceAtLeast(1)
            val bmp = android.graphics.Bitmap.createScaledBitmap(src, width, h, true)
            val bytes = java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 72, it) }.toByteArray()
            if (bmp !== src) bmp.recycle(); src.recycle()
            done(JSONObject().put("dataUrl", "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)), null)
        } catch (e: Exception) {
            done(null, e.message ?: "No frame")
        } finally { mmr.release(); ds?.close() }
    }

    fun saveBegin(name: String, folder: String): String {
        val id = java.util.UUID.randomUUID().toString()
        val tmp = File(context.cacheDir, "module-save-$id")
        tmp.delete()
        saves[id] = tmp
        saveTargets[id] = safeName(name) to folder.split('/').map(::safeName).filter { it.isNotBlank() }.joinToString("/")
        return id
    }
    private val saveTargets = ConcurrentHashMap<String, Pair<String, String>>()
    fun saveChunk(id: String, b64: String) {
        val f = saves[id] ?: return
        FileOutputStream(f, true).use { it.write(Base64.decode(b64, Base64.DEFAULT)) }
    }

    /** Moves the finished upload into Music/<folder>/ (or the app's own Music folder if storage is not writable). */
    fun saveEnd(id: String, done: (JSONObject?, String?) -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        val tmp = saves.remove(id)
        val (name, folder) = saveTargets.remove(id) ?: return@launch done(null, "Unknown save")
        if (tmp == null || !tmp.exists()) return@launch done(null, "Nothing to save")
        // 1) The public Music folder directly (works with all-files access, as in Xander).
        // 2) Otherwise (the standalone app; Android may refuse non-audio files there): MediaStore, so it's still shared.
        // 3) Last resort: the app's own Music folder.
        val public = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        copyInto(tmp, File(public, folder), name)?.let { return@launch done(JSONObject().put("path", it), null) }
        publishToMediaStore(tmp, name, "Music/$folder")?.let { uri -> tmp.delete(); return@launch done(JSONObject().put("path", uri), null) }
        context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)?.let { root -> copyInto(tmp, File(root, folder), name) }
            ?.let { return@launch done(JSONObject().put("path", it), null) }
        tmp.delete()
        done(null, "Could not write to storage")
    }

    private fun copyInto(tmp: File, dir: File, name: String): String? {
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val target = uniqueFile(dir, name)
        if (!runCatching { tmp.copyTo(target, overwrite = false); true }.getOrDefault(false)) { target.delete(); return null }
        tmp.delete()
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), null, null)
        return target.absolutePath
    }

    /** Copies [file] into the shared collection under [relativePath] (Android 10+); returns its content URI. */
    fun publishToMediaStore(file: File, name: String, relativePath: String): String? {
        if (android.os.Build.VERSION.SDK_INT < 29) return null
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
        // Audio goes to Music/, video to Movies/; anything else (projects) to Download/ (the only place MediaStore takes it).
        val (table, path) = when {
            mime.startsWith("audio/") -> android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to relativePath
            mime.startsWith("video/") -> android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI to relativePath
            else -> android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI to "Download/" + relativePath.substringAfter('/')
        }
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, path)
            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val cr = context.contentResolver
        val uri = runCatching { cr.insert(table, values) }.getOrNull() ?: return null
        return try {
            cr.openOutputStream(uri)!!.use { o -> file.inputStream().use { it.copyTo(o) } }
            cr.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri.toString()
        } catch (e: Exception) { runCatching { cr.delete(uri, null, null) }; null }
    }

    override fun onCleared() {
        padsOrNull?.close()
        videoOrNull?.release()
        decodeDir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        fun safeName(s: String) = s.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").trim().take(120).ifEmpty { "file" }
        fun uniqueFile(dir: File, name: String): File {
            var f = File(dir, name)
            val base = name.substringBeforeLast('.'); val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
            var i = 2
            while (f.exists()) f = File(dir, "$base ($i)$ext").also { i++ }
            return f
        }
        fun decodePcm16(b64: String): ShortArray {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            val out = ShortArray(bytes.size / 2)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
            return out
        }
    }
}

/**
 * The `XanderHost` object web modules see (wrapped by modules/host/xander.js). Methods run on the WebView's
 * bridge thread; async results come back through `window.__xanderHost.done(id, json)` / `.fail(id, msg)`.
 */
class XanderHostBridge(
    private val web: WebView,
    private val vm: WebModuleViewModel,
    private val page: WebPage,
    private val requestPick: (id: Int, mimes: Array<String>, multiple: Boolean) -> Unit,
    private val onExit: () -> Unit,
) {
    fun resolve(id: Int, json: String) = web.post { web.evaluateJavascript("window.__xanderHost && __xanderHost.done($id, $json)", null) }
    fun reject(id: Int, msg: String) = web.post { web.evaluateJavascript("window.__xanderHost && __xanderHost.fail($id, ${JSONObject.quote(msg)})", null) }

    @JavascriptInterface fun launchFile(): String = vm.launchJson
    @JavascriptInterface fun openPath(path: String): String = vm.openPath(path)
    @JavascriptInterface fun pick(id: Int, acceptJson: String, multiple: Boolean) {
        val mimes = runCatching { JSONArray(acceptJson).let { a -> Array(a.length()) { a.getString(it) } } }.getOrDefault(arrayOf("*/*"))
        web.post { requestPick(id, mimes, multiple) }
    }
    @JavascriptInterface fun decodeAudio(id: Int, token: String, startMs: Double, maxMs: Double, channels: Int) {
        vm.decode(token, startMs.toLong(), maxMs.toLong(), channels) { r, err -> if (r != null) resolve(id, r.toString()) else reject(id, err ?: "error") }
    }
    @JavascriptInterface fun thumbnail(id: Int, token: String, ms: Double, width: Int) {
        vm.thumbnail(token, ms.toLong(), width.coerceIn(16, 1280)) { r, err -> if (r != null) resolve(id, r.toString()) else reject(id, err ?: "error") }
    }
    @JavascriptInterface fun saveBegin(name: String, mime: String, folder: String): String = vm.saveBegin(name, folder)
    @JavascriptInterface fun saveChunk(sid: String, b64: String) = vm.saveChunk(sid, b64)
    @JavascriptInterface fun saveEnd(id: Int, sid: String) { vm.saveEnd(sid) { r, err -> if (r != null) resolve(id, r.toString()) else reject(id, err ?: "error") } }
    @JavascriptInterface fun toast(msg: String) { web.post { Toast.makeText(web.context, msg, Toast.LENGTH_SHORT).show() } }
    @JavascriptInterface fun keepScreenOn(on: Boolean) { web.post { web.keepScreenOn = on } }
    @JavascriptInterface fun exit() { web.post(onExit) }
    @JavascriptInterface fun log(msg: String) = ModuleLog.write(web.context, page.id, msg.take(2000))

    // Low-latency pads (only when the module asked for them).
    @JavascriptInterface fun padAvailable(): Boolean = page.lowLatencyAudio
    @JavascriptInterface fun padLatencyMs(): Double = if (page.lowLatencyAudio) vm.pads.latencyMs else 0.0
    @JavascriptInterface fun padLoad(id: String, rate: Int, channels: Int, b64: String) { if (page.lowLatencyAudio) vm.pads.load(id, rate, channels, WebModuleViewModel.decodePcm16(b64)) }
    @JavascriptInterface fun padTrigger(id: String, gain: Double, pan: Double) { if (page.lowLatencyAudio) vm.pads.trigger(id, gain.toFloat(), pan.toFloat()) }
    @JavascriptInterface fun padStopAll() { if (page.lowLatencyAudio) vm.pads.stopAll() }

    /** Videos or audio on the device, newest first (in Xander: its shared index, so no second walk of the phone). */
    @JavascriptInterface fun mediaIndex(kind: String): String {
        val arr = JSONArray()
        runCatching { vm.storage.mediaIndex(kind) }.getOrDefault(emptyList()).take(5000)
            .forEach { arr.put(JSONObject().put("path", it.path).put("name", it.name).put("size", it.size).put("modified", it.modified)) }
        return arr.toString()
    }

    // Native video player over the page (only when the module asked for it).
    @JavascriptInterface fun videoAvailable(): Boolean = page.nativeVideo
    @JavascriptInterface fun videoCmd(json: String) { if (page.nativeVideo) vm.video.command(json) }
    @JavascriptInterface fun videoExport(id: Int, name: String, startSec: Double, endSec: Double) {
        if (page.nativeVideo) vm.video.export(name, startSec, endSec) { r, err -> if (r != null) resolve(id, r.toString()) else reject(id, err ?: "error") }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebModuleScreen(page: WebPage, file: String?, storage: HostStorage, onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: WebModuleViewModel = viewModel(key = "web-${page.id}") { WebModuleViewModel(context.applicationContext, storage) }
    val back by rememberUpdatedState(onBack)
    remember(file) { vm.launch(file) }
    val holder = remember { arrayOfNulls<Pair<XanderHostBridge, Int>>(1) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val (bridge, id) = holder[0] ?: return@rememberLauncherForActivityResult
        holder[0] = null
        val arr = JSONArray()
        for (u in uris) {
            runCatching { context.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            arr.put(vm.files.register(u).toJson())
        }
        bridge.resolve(id, arr.toString())
    }
    // If the page's renderer dies (usually out of memory), Android would kill the whole app unless we handle it.
    // We drop that WebView and build a fresh one instead.
    var generation by remember { mutableIntStateOf(0) }
    val web = remember(generation) {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(android.graphics.Color.parseColor("#0b0c0f"))
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = false
                allowContentAccess = false
                setSupportZoom(false)
                // Rasterise the whole page ahead (smooth scrolling, no checkerboarding) while it's on screen.
                offscreenPreRaster = true
            }
            // Keep the renderer at foreground priority while the module is visible.
            if (android.os.Build.VERSION.SDK_INT >= 26) setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
            val assets = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context)).build()
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url
                    if (url.host != WebModuleHost.HOST) return null
                    val p = url.path.orEmpty()
                    if (p.startsWith("/host/file/")) return vm.files.respond(p.removePrefix("/host/file/").substringBefore('/'), request.requestHeaders.entries.firstOrNull { it.key.equals("Range", true) }?.value)
                    return assets.shouldInterceptRequest(url)
                }
                override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                    ModuleLog.write(context, page.id, "RENDERER GONE crashed=${detail.didCrash()} priority=${detail.rendererPriorityAtExit()}")
                    vm.crashes++
                    vm.launchJson = "" // don't reopen the file that may have caused it
                    (view.parent as? ViewGroup)?.removeView(view)
                    view.destroy()
                    Toast.makeText(context, if (detail.didCrash()) "The module crashed and was restarted" else "The module ran out of memory and was restarted", Toast.LENGTH_LONG).show()
                    generation++
                    return true
                }
                // Links to the web open in the browser; the module page itself never navigates away.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (request.url.host == WebModuleHost.HOST) return false
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR || m.messageLevel() == ConsoleMessage.MessageLevel.WARNING)
                        ModuleLog.write(context, page.id, "${m.messageLevel()}: ${m.message()} (${m.sourceId().substringAfterLast('/')}:${m.lineNumber()})")
                    return true
                }
            }
            addJavascriptInterface(XanderHostBridge(this, vm, page, { id, mimes, _ ->
                holder[0] = (this.tag as XanderHostBridge) to id
                picker.launch(mimes) // the system picker always allows several; single-file callers take the first
            }, { back() }).also { tag = it }, "XanderHost")
            ModuleLog.started(context, page.id)
            loadUrl(WebModuleHost.pageUrl(page))
        }
    }
    BackHandler {
        web.evaluateJavascript("(window.XanderModule && window.XanderModule.onBack) ? String(window.XanderModule.onBack()) : 'false'") { handled ->
            if (handled?.trim('"') != "true") back()
        }
    }
    DisposableEffect(web) {
        web.onResume()
        onDispose { web.keepScreenOn = false; runCatching { web.stopLoading(); web.destroy() } }
    }
    if (page.nativeVideo) DisposableEffect(web) {
        vm.video.push = { json -> web.post { web.evaluateJavascript("window.__xanderVideo && __xanderVideo.state($json)", null) } }
        onDispose { vm.video.push = {}; vm.video.rect.value = null }
    }
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        key(generation) { AndroidView(factory = { web }, modifier = Modifier.fillMaxSize()) }
        if (page.nativeVideo) {
            val r by vm.video.rect.collectAsState()
            // The page reports the video box in CSS pixels; WebView CSS pixels are density-independent pixels.
            r?.let { (x, y, w, h) ->
                val d = androidx.compose.ui.platform.LocalDensity.current
                with(d) {
                    AndroidView(factory = { (vm.video.view.parent as? ViewGroup)?.removeView(vm.video.view); vm.video.view },
                        modifier = Modifier.offset(x.dp, y.dp).size(w.dp, h.dp))
                }
            }
        }
    }
}
