package com.xander.files.ui.browser

import android.content.ClipData
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.xander.files.R
import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FsPath
import com.xander.files.ui.common.Format

/** Drag handle shown on selected rows in dual-pane mode; dragging carries the selection to the other pane. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DragHandle(modifier: Modifier, paths: () -> List<FsPath>) {
    val start: suspend androidx.compose.foundation.draganddrop.DragAndDropSourceScope.() -> Unit = {
        detectDragGestures(onDragStart = {
            val text = DRAG_PREFIX + paths().joinToString("\n") { it.toString() }
            startTransfer(DragAndDropTransferData(ClipData.newPlainText("xander-paths", text)))
        }) { change, _ -> change.consume() }
    }
    Box(modifier.padding(end = 8.dp).size(40.dp).dragAndDropSource({}, start)) {
        Icon(Icons.Filled.DragIndicator, stringResource(R.string.drop_title, 0), Modifier.padding(8.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

/** Quick look for images and videos from a long-press or mouse hover on the thumbnail. */
@Composable
fun PreviewDialog(entry: FileEntry, onDismiss: () -> Unit, onOpen: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.name, maxLines = 1) },
        text = {
            Column {
                AsyncImage(entry, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 420.dp))
                Text("${Format.size(entry.size)} · ${Format.date(entry.lastModified)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = onOpen) { Text(stringResource(R.string.open)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } }
    )
}
