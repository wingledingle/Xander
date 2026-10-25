package com.xander.files.ui.viewers

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.xander.files.R
import com.xander.files.data.media.MediaLibraryHelper
import com.xander.files.data.ops.FileActions
import com.xander.files.data.settings.SettingsRepository
import com.xander.files.data.thumbs.FullImage
import com.xander.files.data.transfer.TransferManager
import com.xander.files.data.transfer.TransferRequest
import com.xander.files.data.transfer.TransferType
import com.xander.files.domain.fs.FileCategory
import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FileSystemRegistry
import com.xander.files.domain.fs.FsPath
import com.xander.files.ui.common.Format
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ImageViewerViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val registry: FileSystemRegistry,
    private val media: MediaLibraryHelper,
    private val actions: FileActions,
    private val transfers: TransferManager,
    private val settings: SettingsRepository,
) : ViewModel() {
    val start: FsPath = FsPath.parse(saved.get<String>("path")!!)
    val images = MutableStateFlow<List<FileEntry>>(emptyList())
    val initialIndex = MutableStateFlow(-1)

    init {
        viewModelScope.launch {
            val self = runCatching { registry.provider(start).stat(start) }.getOrNull()
            val list = media.siblings(start, setOf(FileCategory.IMAGE)).ifEmpty { listOfNotNull(self) }
            images.value = list
            initialIndex.value = list.indexOfFirst { it.path == start }.coerceAtLeast(0)
        }
    }

    suspend fun exif(e: FileEntry): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        runCatching {
            registry.provider(e.path).openInput(e.path).use { s ->
                val x = ExifInterface(s)
                val tags = listOf(
                    "Camera" to listOfNotNull(x.getAttribute(ExifInterface.TAG_MAKE), x.getAttribute(ExifInterface.TAG_MODEL)).joinToString(" "),
                    "Date" to (x.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: x.getAttribute(ExifInterface.TAG_DATETIME)),
                    "Resolution" to "${x.getAttribute(ExifInterface.TAG_IMAGE_WIDTH) ?: x.getAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION)} × ${x.getAttribute(ExifInterface.TAG_IMAGE_LENGTH) ?: x.getAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION)}",
                    "Exposure" to x.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.let { t -> t.toDoubleOrNull()?.let { if (it < 1) "1/${(1 / it).toInt()} s" else "$it s" } },
                    "Aperture" to x.getAttribute(ExifInterface.TAG_F_NUMBER)?.let { "f/$it" },
                    "ISO" to x.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY),
                    "Focal length" to x.getAttribute(ExifInterface.TAG_FOCAL_LENGTH),
                    "Flash" to x.getAttribute(ExifInterface.TAG_FLASH),
                    "Location" to x.latLong?.let { "%.5f, %.5f".format(it[0], it[1]) },
                    "Orientation" to x.getAttribute(ExifInterface.TAG_ORIENTATION),
                )
                tags.mapNotNull { (k, v) -> v?.takeIf { it.isNotBlank() && it != "null × null" }?.let { k to it } }
            }
        }.getOrDefault(emptyList()) + listOf("Size" to Format.size(e.size), "Modified" to Format.date(e.lastModified), "Path" to e.path.toString().removePrefix("file://"))
    }

    fun delete(e: FileEntry) = viewModelScope.launch {
        val bin = settings.settings.first().recycleBinEnabled
        transfers.enqueue(TransferRequest(if (bin) TransferType.RECYCLE else TransferType.DELETE, listOf(e.path.toString()), null, "Deleting ${e.name}"))
        images.value = images.value.filter { it.path != e.path }
    }

    suspend fun share(e: FileEntry): Intent = actions.shareIntent(listOf(e))
    suspend fun setAs(e: FileEntry): Intent = actions.setAsIntent(e)
}

@Composable
fun ImageViewerScreen(onBack: () -> Unit, castButton: @Composable () -> Unit = {}, onCast: (FileEntry) -> Unit = {}, vm: ImageViewerViewModel = hiltViewModel()) {
    val images by vm.images.collectAsStateWithLifecycle()
    val initial by vm.initialIndex.collectAsStateWithLifecycle()
    if (initial < 0 || images.isEmpty()) { Box(Modifier.fillMaxSize().background(Color.Black)); return }
    val pager = rememberPagerState(initialPage = initial.coerceIn(0, images.lastIndex)) { images.size }
    var chrome by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    var slideshow by remember { mutableStateOf(false) }
    val rotations = remember { mutableStateMapOf<Int, Float>() }
    var exif by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val current = images.getOrNull(pager.currentPage)

    LaunchedEffect(slideshow, pager.currentPage) {
        if (slideshow) { delay(3000); if (pager.currentPage < images.lastIndex) pager.animateScrollToPage(pager.currentPage + 1) else slideshow = false }
    }
    LaunchedEffect(current) { current?.let(onCast) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = !zoomed, key = { images[it].path.toString() }) { page ->
            ZoomableImage(FullImage(images[page]), rotations[page] ?: 0f, onTap = { chrome = !chrome }, onZoomChanged = { zoomed = it })
        }
        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.5f)).statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(current?.name.orEmpty(), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${pager.currentPage + 1} / ${images.size}", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                }
                castButton()
            }
        }
        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.5f)).navigationBarsPadding().padding(8.dp)) {
                val tint = Color.White
                IconButton(onClick = { slideshow = !slideshow }) { Icon(if (slideshow) Icons.Filled.Pause else Icons.Filled.PlayArrow, stringResource(R.string.slideshow), tint = tint) }
                IconButton(onClick = { rotations[pager.currentPage] = ((rotations[pager.currentPage] ?: 0f) + 90f) % 360f }) { Icon(Icons.Filled.RotateRight, stringResource(R.string.rotate), tint = tint) }
                IconButton(onClick = { current?.let { e -> scope.launch { runCatching { ctx.startActivity(vm.share(e)) } } } }) { Icon(Icons.Filled.Share, stringResource(R.string.share), tint = tint) }
                IconButton(onClick = { current?.let { e -> scope.launch { runCatching { ctx.startActivity(vm.setAs(e)) } } } }) { Icon(Icons.Filled.Wallpaper, stringResource(R.string.set_as), tint = tint) }
                IconButton(onClick = { current?.let { e -> scope.launch { exif = vm.exif(e) } } }) { Icon(Icons.Filled.Info, stringResource(R.string.details), tint = tint) }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete), tint = tint) }
            }
        }
    }
    exif?.let { list ->
        AlertDialog(onDismissRequest = { exif = null }, title = { Text(stringResource(R.string.details)) }, text = {
            Column { list.forEach { (k, v) -> Row(Modifier.padding(vertical = 2.dp)) { Text(k, Modifier.weight(0.4f), style = MaterialTheme.typography.labelMedium); Text(v, Modifier.weight(0.6f), style = MaterialTheme.typography.bodySmall) } } }
        }, confirmButton = { TextButton(onClick = { exif = null }) { Text(stringResource(R.string.ok)) } })
    }
    if (confirmDelete && current != null) com.xander.files.ui.common.ConfirmDialog(stringResource(R.string.delete), current.name, stringResource(R.string.delete),
        onDismiss = { confirmDelete = false }, onConfirm = {
            confirmDelete = false; vm.delete(current)
            if (images.size <= 1) onBack()
            Toast.makeText(ctx, R.string.deleted, Toast.LENGTH_SHORT).show()
        })
}
