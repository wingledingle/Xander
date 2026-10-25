package com.xander.files.ui.browser

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xander.files.R
import com.xander.files.data.clipboard.ClipItem
import com.xander.files.data.clipboard.ClipOp
import com.xander.files.domain.fs.FileCategory
import com.xander.files.domain.fs.FileEntry
import com.xander.files.ui.ops.SheetAction

/** Full action list for a selection (shown from the overflow / long-press sheet). */
@Composable
fun entryActions(
    entries: List<FileEntry>, vm: BrowserViewModel,
    onRename: (FileEntry) -> Unit, onProperties: (List<FileEntry>) -> Unit, onCopyPath: () -> Unit,
): List<SheetAction> {
    val single = entries.singleOrNull()
    val anyHidden = entries.any { it.isHidden }
    return buildList {
        add(SheetAction(Icons.Filled.ContentCopy, stringResource(R.string.copy)) { vm.copy(entries, false) })
        add(SheetAction(Icons.Filled.ContentCut, stringResource(R.string.cut)) { vm.copy(entries, true) })
        if (single != null) add(SheetAction(Icons.Filled.DriveFileRenameOutline, stringResource(R.string.rename)) { onRename(single) })
        add(SheetAction(Icons.Filled.Delete, stringResource(R.string.delete)) { vm.requestDelete(entries) })
        if (entries.any { !it.isDirectory }) add(SheetAction(Icons.Filled.Share, stringResource(R.string.share)) { vm.share(entries) })
        if (single != null && !single.isDirectory) add(SheetAction(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.open_with)) { vm.openWith(single) })
        if (single != null && single.category in setOf(FileCategory.IMAGE, FileCategory.AUDIO))
            add(SheetAction(Icons.Filled.Wallpaper, stringResource(R.string.set_as)) { vm.setAs(single) })
        if (single != null) add(SheetAction(Icons.Filled.FileCopy, stringResource(R.string.duplicate)) { vm.duplicate(single) })
        add(SheetAction(if (anyHidden) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, stringResource(if (anyHidden) R.string.unhide else R.string.hide)) {
            vm.setHidden(entries, !anyHidden)
        })
        add(SheetAction(Icons.Filled.Star, stringResource(R.string.toggle_favorite)) { vm.toggleFavorite(entries) })
        if (single != null) add(SheetAction(Icons.Filled.AddToHomeScreen, stringResource(R.string.create_shortcut)) { vm.createShortcut(single) })
        add(SheetAction(Icons.Filled.Link, stringResource(R.string.copy_path)) { onCopyPath() })
        add(SheetAction(Icons.Filled.Info, stringResource(R.string.properties)) { onProperties(entries) })
    }
}

@Composable
fun PasteBar(top: ClipItem, count: Int, onPaste: () -> Unit, onMore: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (top.op == ClipOp.CUT) Icons.Filled.ContentCut else Icons.Filled.ContentCopy, null)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(top.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                if (count > 1) Text(stringResource(R.string.clipboard_more, count - 1), style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = onMore) { Text(stringResource(R.string.clipboard)) }
            TextButton(onClick = onPaste) { Icon(Icons.Filled.ContentPaste, null); Text(" " + stringResource(R.string.paste_here)) }
        }
    }
}
