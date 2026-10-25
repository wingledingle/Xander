package com.xander.files.ui.viewers

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.xander.files.R
import com.xander.files.data.books.Book
import com.xander.files.data.books.BookParser
import com.xander.files.data.media.MediaLibraryHelper
import com.xander.files.data.office.OfficeConverter
import com.xander.files.data.open.FileOpener
import com.xander.files.data.settings.SettingsRepository
import com.xander.files.domain.fs.FsPath
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipFile
import javax.inject.Inject

enum class ReaderTheme(val bg: String, val fg: String) { LIGHT("#ffffff", "#1d1d1d"), SEPIA("#f4ecd8", "#5b4636"), DARK("#121212", "#d8d8d8") }

@HiltViewModel
class BookViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val opener: FileOpener,
    private val media: MediaLibraryHelper,
    private val settings: SettingsRepository,
) : ViewModel() {
    val path = FsPath.parse(saved.get<String>("path")!!)
    val book = MutableStateFlow<Book?>(null)
    val chapter = MutableStateFlow(0)
    val restoreFraction = MutableStateFlow(0f)
    val error = MutableStateFlow<String?>(null)
    val fontSize = MutableStateFlow(100)
    val theme = MutableStateFlow(ReaderTheme.LIGHT)
    var file: File? = null
        private set

    init {
        viewModelScope.launch {
            try {
                val f = opener.materialize(path); file = f
                val cs = runCatching { Charset.forName(settings.settings.first().defaultEncoding) }.getOrDefault(Charsets.UTF_8)
                book.value = withContext(Dispatchers.IO) { if (path.extension == "epub") BookParser.epub(f) else BookParser.txt(f, cs) }
                media.readerPosition(path)?.split(':')?.let { parts ->
                    chapter.value = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, (book.value!!.chapters.size - 1).coerceAtLeast(0)) ?: 0
                    restoreFraction.value = parts.getOrNull(1)?.toFloatOrNull() ?: 0f
                    parts.getOrNull(2)?.toIntOrNull()?.let { fontSize.value = it }
                    parts.getOrNull(3)?.let { t -> ReaderTheme.entries.firstOrNull { it.name == t }?.let { theme.value = it } }
                }
            } catch (e: Exception) { error.value = e.message ?: "Cannot open this book" }
        }
    }

    fun go(i: Int) { val n = book.value?.chapters?.size ?: return; if (i in 0 until n) { chapter.value = i; restoreFraction.value = 0f; save(0f) } }
    fun save(fraction: Float) = viewModelScope.launch { media.saveReaderPosition(path, "${chapter.value}:$fraction:${fontSize.value}:${theme.value.name}") }

    fun css(): String = "html,body{background:${theme.value.bg}!important;color:${theme.value.fg}!important}" +
        "body{font-size:${fontSize.value}%;line-height:1.6;margin:0 18px 48px;font-family:serif}img{max-width:100%;height:auto}a{color:inherit}pre{white-space:pre-wrap}"

    /** HTML of the current chapter (EPUB chapters get the reader CSS injected; TXT chapters are wrapped). */
    suspend fun chapterHtml(): Pair<String, String> = withContext(Dispatchers.IO) {
        val b = book.value ?: return@withContext "" to ""
        val c = b.chapters[chapter.value]
        val style = "<style>${css()}</style>"
        if (b.isEpub && c.href != null) {
            val raw = ZipFile(file!!).use { z -> z.getEntry(c.href)?.let { e -> z.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } } } ?: ""
            val html = if (raw.contains("</head>", true)) raw.replaceFirst(Regex("</head>", RegexOption.IGNORE_CASE), "$style</head>") else style + raw
            "https://book.local/${c.href}" to html
        } else {
            "https://book.local/txt" to "<html><head><meta charset=utf-8>$style</head><body>" +
                (c.text ?: "").split("\n\n").joinToString("") { "<p>${OfficeConverter.esc(it).replace("\n", "<br>")}</p>" } + "</body></html>"
        }
    }

    /** Serves images/CSS/fonts referenced by EPUB chapters straight from the archive. */
    fun resource(url: String): WebResourceResponse? {
        val f = file ?: return null
        if (!url.startsWith("https://book.local/")) return null
        val name = BookParser.resolve("", url.removePrefix("https://book.local/").substringBefore('#').substringBefore('?'))
        return runCatching {
            val z = ZipFile(f)
            val e = z.getEntry(name) ?: run { z.close(); return null }
            val bytes = z.getInputStream(e).use { it.readBytes() }; z.close()
            val mime = when (name.substringAfterLast('.').lowercase()) {
                "css" -> "text/css"; "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "svg" -> "image/svg+xml"
                "ttf", "otf" -> "font/ttf"; "woff" -> "font/woff"; "woff2" -> "font/woff2"; "xhtml", "html", "htm" -> "text/html"; else -> "application/octet-stream"
            }
            WebResourceResponse(mime, "UTF-8", bytes.inputStream())
        }.getOrNull()
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BookReaderScreen(onBack: () -> Unit, vm: BookViewModel = hiltViewModel()) {
    val book by vm.book.collectAsStateWithLifecycle()
    val chapter by vm.chapter.collectAsStateWithLifecycle()
    val font by vm.fontSize.collectAsStateWithLifecycle()
    val theme by vm.theme.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var tocOpen by remember { mutableStateOf(false) }
    var styleOpen by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var html by remember { mutableStateOf<Pair<String, String>?>(null) }
    androidx.compose.runtime.LaunchedEffect(book, chapter, font, theme) { if (book != null) html = vm.chapterHtml() }

    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            title = { Column { Text(book?.title ?: vm.path.name, maxLines = 1, overflow = TextOverflow.Ellipsis); book?.let { Text(it.chapters.getOrNull(chapter)?.title.orEmpty(), style = MaterialTheme.typography.labelSmall, maxLines = 1) } } },
            actions = {
                IconButton(onClick = { tocOpen = true }) { Icon(Icons.AutoMirrored.Filled.List, stringResource(R.string.contents)) }
                IconButton(onClick = { styleOpen = true }) { Icon(Icons.Filled.FormatSize, stringResource(R.string.reading_style)) }
            }
        )
    }, bottomBar = {
        book?.let { b ->
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = chapter > 0, onClick = { vm.go(chapter - 1) }) { Icon(Icons.AutoMirrored.Filled.NavigateBefore, stringResource(R.string.previous)) }
                Text("${chapter + 1} / ${b.chapters.size}", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                IconButton(enabled = chapter < b.chapters.lastIndex, onClick = { vm.go(chapter + 1) }) { Icon(Icons.AutoMirrored.Filled.NavigateNext, stringResource(R.string.next)) }
            }
        }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                error != null -> Text(error!!, Modifier.align(Alignment.Center).padding(24.dp))
                html == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> AndroidView(factory = { c ->
                    WebView(c).apply {
                        settings.javaScriptEnabled = false
                        settings.allowFileAccess = false
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = vm.resource(request.url.toString())
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                // Internal links jump to the matching chapter.
                                val target = request.url.toString().removePrefix("https://book.local/").substringBefore('#')
                                vm.book.value?.chapters?.indexOfFirst { it.href == target }?.takeIf { it >= 0 }?.let { vm.go(it) }
                                return true
                            }
                            override fun onPageFinished(view: WebView, url: String?) {
                                val f = vm.restoreFraction.value
                                if (f > 0) view.postDelayed({ view.scrollTo(0, (view.contentHeight * view.resources.displayMetrics.density * f).toInt()) }, 150)
                            }
                        }
                        setOnScrollChangeListener { v, _, y, _, _ ->
                            val h = (contentHeight * resources.displayMetrics.density).coerceAtLeast(1f)
                            v.removeCallbacks(null); vm.restoreFraction.value = y / h
                        }
                    }.also { web = it }
                }, update = { w ->
                    val (base, content) = html!!
                    if (w.tag != content) { w.tag = content; w.loadDataWithBaseURL(base, content, "text/html", "UTF-8", null) }
                }, onRelease = { vm.save(vm.restoreFraction.value) }, modifier = Modifier.fillMaxSize())
            }
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { vm.save(vm.restoreFraction.value) } }
    if (tocOpen) book?.let { b ->
        ModalBottomSheet(onDismissRequest = { tocOpen = false }) {
            LazyColumn {
                items(b.toc) { t ->
                    Text(t.title, Modifier.fillMaxWidth().clickable { tocOpen = false; vm.go(t.chapterIndex) }.padding(start = (16 + t.depth * 16).dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                        color = if (t.chapterIndex == chapter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
                item { Box(Modifier.padding(24.dp)) }
            }
        }
    }
    if (styleOpen) ModalBottomSheet(onDismissRequest = { styleOpen = false }) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.font_size_pct, font))
            Slider(font.toFloat(), { vm.fontSize.value = it.toInt() }, valueRange = 70f..220f, onValueChangeFinished = { vm.save(vm.restoreFraction.value) })
            Row { ReaderTheme.entries.forEach { t -> FilterChip(theme == t, { vm.theme.value = t; vm.save(vm.restoreFraction.value) }, label = { Text(t.name.lowercase().replaceFirstChar { it.uppercase() }) }, modifier = Modifier.padding(end = 8.dp)) } }
            Box(Modifier.padding(24.dp))
        }
    }
}
