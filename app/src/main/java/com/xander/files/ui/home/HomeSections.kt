package com.xander.files.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xander.files.R
import com.xander.files.data.index.LibraryKind
import com.xander.files.data.index.LibraryStat
import com.xander.files.domain.fs.FileEntry
import com.xander.files.ui.browser.EntryThumb
import com.xander.files.ui.common.Format
import com.xander.files.ui.theme.CategoryColors

data class CategoryTile(val kind: LibraryKind?, val icon: ImageVector, val label: Int, val color: Color)

val categoryTiles = listOf(
    CategoryTile(LibraryKind.IMAGES, Icons.Filled.Image, R.string.cat_images, CategoryColors.image),
    CategoryTile(LibraryKind.VIDEOS, Icons.Filled.VideoFile, R.string.cat_videos, CategoryColors.video),
    CategoryTile(LibraryKind.MUSIC, Icons.Filled.AudioFile, R.string.cat_music, CategoryColors.audio),
    CategoryTile(LibraryKind.DOCUMENTS, Icons.Filled.Description, R.string.cat_documents, CategoryColors.document),
    CategoryTile(LibraryKind.APKS, Icons.Filled.Android, R.string.cat_apks, CategoryColors.apk),
    CategoryTile(LibraryKind.ARCHIVES, Icons.Filled.FolderZip, R.string.cat_archives, CategoryColors.archive),
    CategoryTile(LibraryKind.DOWNLOADS, Icons.Filled.Download, R.string.downloads, CategoryColors.download),
    CategoryTile(LibraryKind.LARGE, Icons.Filled.SdStorage, R.string.cat_large, CategoryColors.large),
    CategoryTile(null, Icons.Filled.Apps, R.string.cat_apps, CategoryColors.apps),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryGrid(stats: List<LibraryStat>, appCount: Int, onOpen: (LibraryKind) -> Unit, onApps: () -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(12.dp), maxItemsInEachRow = 3) {
        categoryTiles.forEach { t ->
            val st = stats.firstOrNull { it.kind == t.kind }
            Column(
                Modifier.width(104.dp).clip(RoundedCornerShape(14.dp)).clickable { if (t.kind != null) onOpen(t.kind) else onApps() }.padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(t.color.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                    Icon(t.icon, null, tint = t.color)
                }
                Text(stringResource(t.label), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp), maxLines = 1)
                Text(
                    if (t.kind == null) "$appCount" else if (st == null) "…" else "${st.count} · ${Format.size(st.size)}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1
                )
            }
        }
    }
}

@Composable
fun RecentStrip(items: List<FileEntry>, days: Int, onDays: (Int) -> Unit, onOpen: (FileEntry) -> Unit, onMore: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.recent_files), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).clickable(onClick = onMore))
            FilterChip(days == 7, { onDays(7) }, label = { Text(stringResource(R.string.days_7)) }, modifier = Modifier.padding(end = 6.dp))
            FilterChip(days == 30, { onDays(30) }, label = { Text(stringResource(R.string.days_30)) })
        }
        if (items.isEmpty()) Text(stringResource(R.string.no_recent), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else {
            // Group by day like a timeline.
            val groups = items.groupBy { Format.shortDate(it.lastModified) }
            groups.forEach { (day, list) ->
                Text(day, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list, key = { it.path.toString() }) { e ->
                        Column(Modifier.width(84.dp).clip(RoundedCornerShape(10.dp)).clickable { onOpen(e) }, horizontalAlignment = Alignment.CenterHorizontally) {
                            EntryThumb(e, 72, true)
                            Text(e.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
    }
}
