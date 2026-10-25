package com.xander.files.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xander.files.R
import com.xander.files.domain.fs.FileCategory

@Composable
fun CategoryIcon(category: FileCategory, size: Int = 44, modifier: Modifier = Modifier) {
    val color = FileIcons.color(category)
    Box(
        modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)).background(color.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(FileIcons.icon(category), null, tint = color, modifier = Modifier.size((size * 0.6f).dp))
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, message: String? = null, action: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp), textAlign = TextAlign.Center)
        if (message != null) Text(
            message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp), textAlign = TextAlign.Center
        )
        if (action != null) Box(Modifier.padding(top = 16.dp)) { action() }
    }
}

/** Single text input dialog used for new folder / new file / rename. Pre-selects the name without extension. */
@Composable
fun TextInputDialog(
    title: String,
    initial: String = "",
    label: String = "",
    confirm: String = stringResource(R.string.ok),
    selectBaseName: Boolean = true,
    validator: (String) -> String? = { if (it.isBlank() || it.contains('/')) "Invalid name" else null },
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val end = if (selectBaseName && initial.contains('.') && !initial.startsWith('.')) initial.lastIndexOf('.') else initial.length
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, end))) }
    val error = if (value.text.isEmpty()) null else validator(value.text)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value, onValueChange = { value = it }, singleLine = true,
                label = if (label.isNotEmpty()) ({ Text(label) }) else null,
                isError = error != null, supportingText = error?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (error == null && value.text.isNotBlank()) onConfirm(value.text.trim()) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus)
            )
        },
        confirmButton = {
            TextButton(enabled = error == null && value.text.isNotBlank(), onClick = { onConfirm(value.text.trim()) }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
fun ConfirmDialog(title: String, message: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit, extra: (@Composable () -> Unit)? = null) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column { Text(message); extra?.invoke() } },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
