package com.xander.files.ui.ops

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.xander.files.R
import com.xander.files.data.archive.OutFormat

data class CompressSpec(val name: String, val format: OutFormat, val password: String, val level: Int)

@Composable
fun CompressDialog(defaultName: String, onDismiss: () -> Unit, onConfirm: (CompressSpec) -> Unit) {
    var name by remember { mutableStateOf(defaultName) }
    var format by remember { mutableStateOf(OutFormat.ZIP) }
    var password by remember { mutableStateOf("") }
    var level by remember { mutableFloatStateOf(5f) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.compress)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.archive_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.padding(top = 8.dp)) {
                    OutFormat.entries.forEach { f -> FilterChip(format == f, { format = f }, label = { Text(f.ext) }, modifier = Modifier.padding(end = 4.dp)) }
                }
                if (format == OutFormat.ZIP) OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.password_optional)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                else if (format == OutFormat.SEVEN_Z) Text(stringResource(R.string.no_password_7z), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                if (format != OutFormat.TAR) {
                    Text(stringResource(R.string.compression_level, level.toInt()), modifier = Modifier.padding(top = 12.dp))
                    Slider(level, { level = it }, valueRange = 0f..9f, steps = 8)
                }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(CompressSpec(name.trim(), format, if (format == OutFormat.ZIP) password else "", level.toInt())) }) { Text(stringResource(R.string.compress)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

/** Password entry for opening encrypted archives / decrypting; [confirm] adds a repeat field for encryption. */
@Composable
fun PasswordDialog(
    title: String, error: String? = null, confirm: Boolean = false, showDeleteOriginal: Boolean = false,
    onDismiss: () -> Unit, onConfirm: (password: String, deleteOriginal: Boolean) -> Unit,
) {
    var p1 by remember { mutableStateOf("") }
    var p2 by remember { mutableStateOf("") }
    var del by remember { mutableStateOf(false) }
    val mismatch = confirm && p2.isNotEmpty() && p1 != p2
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp))
                OutlinedTextField(p1, { p1 = it }, label = { Text(stringResource(R.string.password)) }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                if (confirm) OutlinedTextField(p2, { p2 = it }, label = { Text(stringResource(R.string.repeat_password)) }, singleLine = true, isError = mismatch,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                if (showDeleteOriginal) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(del, { del = it }); Text(stringResource(R.string.delete_original)) }
            }
        },
        confirmButton = { TextButton(enabled = p1.isNotEmpty() && (!confirm || p1 == p2), onClick = { onConfirm(p1, del) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
