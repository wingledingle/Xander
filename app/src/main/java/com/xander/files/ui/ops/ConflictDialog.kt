package com.xander.files.ui.ops

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.xander.files.R
import com.xander.files.data.transfer.ConflictBroker
import com.xander.files.data.transfer.ConflictChoice
import com.xander.files.ui.common.Format

/** Shown app-wide whenever a background transfer hits an existing file. */
@Composable
fun ConflictDialog(pending: ConflictBroker.Pending, onAnswer: (ConflictChoice, Boolean) -> Unit) {
    var all by remember(pending) { mutableStateOf(false) }
    val r = pending.request
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.conflict_title)) },
        text = {
            Column {
                Text(r.sourceName, style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.conflict_existing, Format.size(r.targetSize), Format.date(r.targetModified)), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                Text(stringResource(R.string.conflict_new, Format.size(r.sourceSize), Format.date(r.sourceModified)), style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Checkbox(all, { all = it }); Text(stringResource(R.string.apply_to_all))
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { onAnswer(if (r.isDirectory) ConflictChoice.MERGE else ConflictChoice.OVERWRITE, all) }) {
                    Text(stringResource(if (r.isDirectory) R.string.merge else R.string.overwrite))
                }
                TextButton(onClick = { onAnswer(ConflictChoice.RENAME, all) }) { Text(stringResource(R.string.keep_both)) }
                TextButton(onClick = { onAnswer(ConflictChoice.SKIP, all) }) { Text(stringResource(R.string.skip)) }
            }
        }
    )
}
