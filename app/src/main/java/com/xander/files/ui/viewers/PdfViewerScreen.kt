package com.xander.files.ui.viewers

import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.xander.files.R
import com.xander.files.data.media.MediaLibraryHelper
import com.xander.files.data.open.FileOpener
import com.xander.files.domain.fs.FsException
import com.xander.files.domain.fs.FsPath
import com.xander.files.ui.common.TextInputDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class PdfViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val opener: FileOpener,
    private val media: MediaLibraryHelper,
) : ViewModel() {
    val path = FsPath.parse(saved.get<String>("path")!!)
    private var renderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private val lock = Mutex()
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) { override fun sizeOf(key: String, value: Bitmap) = value.byteCount }
    val pageCount = MutableStateFlow(-1)
    val aspect = MutableStateFlow<List<Float>>(emptyList())
    val error = MutableStateFlow<String?>(null)
    val startPage = MutableStateFlow(0)

    init {
        viewModelScope.launch {
            try {
                val f = opener.materialize(path)
                withContext(Dispatchers.IO) {
                    pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
                    val r = PdfRenderer(pfd!!)
                    renderer = r
                    aspect.value = (0 until r.pageCount).map { i -> r.openPage(i).use { it.height.toFloat() / it.width } }
                    pageCount.value = r.pageCount
                }
                startPage.value = media.readerPosition(path)?.toIntOrNull() ?: 0
            } catch (e: SecurityException) {
                error.value = "This PDF is password protected"
            } catch (e: Exception) { error.value = FsException.wrap(e, path).message }
        }
    }

    suspend fun render(index: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = "$index@$widthPx"
        cache.get(key)?.let { return@withContext it }
        lock.withLock {
            val r = renderer ?: return@withLock null
            r.openPage(index).use { page ->
                val w = widthPx.coerceIn(64, 3000)
                val h = (w.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(AColor.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                cache.put(key, bmp)
                bmp
            }
        }
    }

    fun savePage(p: Int) = viewModelScope.launch { media.saveReaderPosition(path, p.toString()) }

    override fun onCleared() { runCatching { renderer?.close(); pfd?.close() } }
}

private val invert = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f)))

@Composable
fun PdfViewerScreen(onBack: () -> Unit, vm: PdfViewModel = hiltViewModel()) {
    val count by vm.pageCount.collectAsStateWithLifecycle()
    val aspects by vm.aspect.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val start by vm.startPage.collectAsStateWithLifecycle()
    var night by remember { mutableStateOf(false) }
    var jump by remember { mutableStateOf(false) }
    var thumbs by remember { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(count, start) { if (count > 0 && start in 0 until count) list.scrollToItem(start) }
    LaunchedEffect(list) { snapshotFlow { list.firstVisibleItemIndex }.collect { vm.savePage(it) } }

    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            title = { Column { Text(vm.path.name, maxLines = 1); if (count > 0) Text("${list.firstVisibleItemIndex + 1} / $count", style = MaterialTheme.typography.labelSmall) } },
            actions = {
                IconButton(onClick = { thumbs = true }) { Icon(Icons.Filled.GridView, stringResource(R.string.pages)) }
                IconButton(onClick = { jump = true }) { Icon(Icons.Filled.Numbers, stringResource(R.string.go_to_page)) }
                IconButton(onClick = { night = !night }) { Icon(Icons.Filled.DarkMode, stringResource(R.string.night_mode)) }
            }
        )
    }) { pad ->
        BoxWithConstraints(Modifier.padding(pad).fillMaxSize().background(if (night) Color.Black else Color(0xFF606060))
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    offset = if (scale == 1f) Offset.Zero else Offset((offset.x + pan.x).coerceIn(-size.width * (scale - 1) / 2, size.width * (scale - 1) / 2), offset.y)
                }
            }) {
            val widthPx = with(LocalDensity.current) { maxWidth.toPx() }.toInt()
            when {
                error != null -> Text(error!!, color = Color.White, modifier = Modifier.align(Alignment.Center).padding(24.dp))
                count < 0 -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> LazyColumn(state = list, modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x }) {
                    items(count) { i ->
                        val bmp by produceState<Bitmap?>(null, i, widthPx, scale > 1.5f) { value = vm.render(i, (widthPx * if (scale > 1.5f) 2 else 1)) }
                        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).aspectRatio(1f / (aspects.getOrNull(i) ?: 1.414f)).background(Color.White)) {
                            bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), colorFilter = if (night) invert else null) }
                        }
                    }
                }
            }
        }
    }
    if (jump) TextInputDialog(stringResource(R.string.go_to_page), "", selectBaseName = false, validator = { v -> if (v.toIntOrNull() in 1..count) null else "1–$count" },
        onDismiss = { jump = false }) { v -> jump = false; scope.launch { list.scrollToItem(v.toInt() - 1) } }
    if (thumbs) ModalBottomSheet(onDismissRequest = { thumbs = false }) {
        LazyVerticalGrid(GridCells.Adaptive(96.dp), Modifier.fillMaxWidth().padding(8.dp)) {
            items(count) { i ->
                val bmp by produceState<Bitmap?>(null, i) { value = vm.render(i, 200) }
                Column(Modifier.padding(4.dp).clickable { thumbs = false; scope.launch { list.scrollToItem(i) } }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f / (aspects.getOrNull(i) ?: 1.414f)).background(Color.White)) { bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize()) } }
                    Text("${i + 1}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
