package com.xander.files.ui.ops

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.xander.files.R
import com.xander.files.domain.fs.FileEntry

@Composable
fun DeleteDialog(entries: List<FileEntry>, recycleEnabled: Boolean, onDismiss: () -> Unit, onConfirm: (permanent: Boolean) -> Unit) {
    var permanent by remember { mutableStateOf(!recycleEnabled) }
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete)) },
        text = {
            Column {
                Text(if (entries.size == 1) entries.first().name else ctx.resources.getQuantityString(R.plurals.delete_confirm, entries.size, entries.size))
                if (recycleEnabled) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(permanent, { permanent = it }); Text(stringResource(R.string.delete_permanently))
                }
                if (!permanent) Text(stringResource(R.string.moved_to_bin_hint))
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(permanent) }) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
