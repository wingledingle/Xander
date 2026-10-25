package com.xander.files.ui.browser

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.xander.files.domain.fs.FileCategory
import com.xander.files.domain.fs.FileEntry
import com.xander.files.ui.common.CategoryIcon
import com.xander.files.ui.common.Format
import com.xander.files.ui.theme.LocalDensityPrefs

/** Icon or thumbnail for an entry. Thumbnails are loaded through the app's Coil fetchers (any backend). */
@Composable
fun EntryThumb(entry: FileEntry, size: Int, thumbnails: Boolean, modifier: Modifier = Modifier) {
    val cat = entry.category
    val thumbable = thumbnails && cat in setOf(FileCategory.IMAGE, FileCategory.VIDEO, FileCategory.AUDIO, FileCategory.APK, FileCategory.PDF)
    if (!thumbable) { CategoryIcon(cat, size, modifier); return }
    SubcomposeAsyncImage(
        model = entry,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)),
        loading = { CategoryIcon(cat, size) },
        error = { CategoryIcon(cat, size) },
    )
}

/** Opens the preview after the mouse rests on the thumbnail for 700 ms (tablets, Chromebooks, DeX). */
fun Modifier.hoverPreview(onPreview: (() -> Unit)?): Modifier = if (onPreview == null) this else this.pointerInput(onPreview) {
    awaitPointerEventScope {
        var enteredAt = 0L
        while (true) {
            val e = awaitPointerEvent()
            when (e.type) {
                androidx.compose.ui.input.pointer.PointerEventType.Enter -> enteredAt = System.currentTimeMillis()
                androidx.compose.ui.input.pointer.PointerEventType.Exit -> enteredAt = 0
                androidx.compose.ui.input.pointer.PointerEventType.Move ->
                    if (enteredAt > 0 && System.currentTimeMillis() - enteredAt > 700) { enteredAt = 0; onPreview() }
            }
        }
    }
}

fun FileEntry.displayName(showExt: Boolean): String =
    if (showExt || isDirectory || !name.contains('.') || name.startsWith('.')) name else name.substringBeforeLast('.')

fun FileEntry.subtitle(): String = buildString {
    if (isDirectory) { childCount?.let { append("$it items · ") } }
    else append(Format.size(size)).append(" · ")
    append(Format.date(lastModified))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileListRow(
    entry: FileEntry, selected: Boolean, showExt: Boolean, thumbnails: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit, onThumbLongPress: () -> Unit = {},
    onHoverPreview: (() -> Unit)? = null,
) {
    val d = LocalDensityPrefs.current
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    Row(
        Modifier.fillMaxWidth().height(d.rowHeight.dp).background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.combinedClickable(onClick = onClick, onLongClick = onThumbLongPress).hoverPreview(onHoverPreview)) {
            EntryThumb(entry, d.iconSize, thumbnails)
            if (selected) Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.BottomEnd).size(18.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(9.dp)))
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(entry.displayName(showExt), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
                color = if (entry.isHidden) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            Text(entry.subtitle(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileGridCell(entry: FileEntry, selected: Boolean, showExt: Boolean, thumbnails: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
    Column(
        Modifier.padding(4.dp).clip(RoundedCornerShape(12.dp)).background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
            val cat = entry.category
            if (thumbnails && cat in setOf(FileCategory.IMAGE, FileCategory.VIDEO, FileCategory.APK, FileCategory.AUDIO, FileCategory.PDF)) {
                SubcomposeAsyncImage(model = entry, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                    loading = { CategoryIcon(cat, 56) }, error = { CategoryIcon(cat, 56) })
            } else CategoryIcon(cat, 56)
            if (selected) Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.TopEnd).size(22.dp))
        }
        Text(entry.displayName(showExt), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(120.dp).padding(top = 6.dp))
    }
}
