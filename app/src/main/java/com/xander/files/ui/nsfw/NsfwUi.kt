package com.xander.files.ui.nsfw

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xander.files.R
import com.xander.files.data.nsfw.NsfwScores

/** "Check for adult content" result: all five class probabilities, so the user sees why. */
@Composable
fun NsfwCheckDialog(name: String, scores: NsfwScores?, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(name, maxLines = 2) },
        text = {
            if (scores == null) CircularProgressIndicator()
            else Column {
                ScoreBar(stringResource(R.string.nsfw_porn), scores.porn)
                ScoreBar(stringResource(R.string.nsfw_hentai), scores.hentai)
                ScoreBar(stringResource(R.string.nsfw_sexy), scores.sexy)
                ScoreBar(stringResource(R.string.nsfw_drawings), scores.drawings)
                ScoreBar(stringResource(R.string.nsfw_neutral), scores.neutral)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } })
}

@Composable
private fun ScoreBar(label: String, v: Float) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(96.dp), style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(progress = { v }, Modifier.weight(1f).padding(horizontal = 8.dp))
        Text("${(v * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
    }
}

/** Compact score line for list rows: "porn 92% · hentai 3% · sexy 4%". */
fun scoreLine(porn: Float, hentai: Float, sexy: Float, drawings: Float) =
    "porn ${(porn * 100).toInt()}% · hentai ${(hentai * 100).toInt()}% · sexy ${(sexy * 100).toInt()}% · drawing ${(drawings * 100).toInt()}%"
