package com.xander.files.ui.ops

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xander.files.R
import com.xander.files.data.fs.local.PosixInfo
import com.xander.files.domain.fs.FileEntry
import com.xander.files.ui.common.Format

@Composable
fun PropertiesDialog(entries: List<FileEntry>, onDismiss: () -> Unit, vm: PropertiesViewModel = hiltViewModel(key = "props:" + entries.joinToString { it.path.toString() })) {
    LaunchedEffect(entries) { vm.load(entries) }
    val s by vm.state.collectAsStateWithLifecycle()
    var editPerms by remember { mutableStateOf(false) }
    var editOwner by remember { mutableStateOf(false) }
    val single = entries.singleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(single?.name ?: stringResource(R.string.properties)) },
        text = {
            SelectionContainer {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (single != null) {
                        Prop(stringResource(R.string.prop_path), single.path.toString().removePrefix("file://"))
                        Prop(stringResource(R.string.prop_type), if (single.isDirectory) stringResource(R.string.folder) else single.mimeType ?: "")
                    } else Prop(stringResource(R.string.prop_items), entries.size.toString())
                    Prop(stringResource(R.string.prop_size), Format.size(s.size) + " (${"%,d".format(s.size)} B)" + if (s.measuring) " …" else "")
                    if (entries.any { it.isDirectory }) Prop(stringResource(R.string.prop_contains), "${s.files} files, ${s.folders} folders")
                    if (single != null) {
                        Prop(stringResource(R.string.prop_modified), Format.date(single.lastModified))
                        single.created?.let { Prop(stringResource(R.string.prop_created), Format.date(it)) }
                        single.permissions?.let { Prop(stringResource(R.string.prop_permissions), it) }
                        if (single.owner != null) Prop(stringResource(R.string.prop_owner), "${single.owner}:${single.group ?: ""}")
                        if (single.isSymlink) Prop(stringResource(R.string.prop_link_target), single.linkTarget ?: "")
                        Prop(stringResource(R.string.prop_readable), if (single.canRead) "✓" else "✗")
                        Prop(stringResource(R.string.prop_writable), if (single.canWrite) "✓" else "✗")
                        if (!single.isDirectory) {
                            HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            listOf("MD5", "SHA-1", "SHA-256").forEach { alg ->
                                val v = s.checksums[alg]
                                if (v != null) Prop(alg, v, mono = true)
                                else if (s.hashing == alg) {
                                    Text(alg, style = MaterialTheme.typography.labelMedium)
                                    LinearProgressIndicator(progress = { if (single.size > 0) s.hashProgress.toFloat() / single.size else 0f }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                                } else TextButton(onClick = { vm.hash(alg) }) { Text(stringResource(R.string.calculate, alg)) }
                            }
                        }
                    }
                    s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
        dismissButton = {
            if (s.posix && single != null) Row {
                TextButton(onClick = { editPerms = true }) { Text(stringResource(R.string.permissions)) }
                TextButton(onClick = { editOwner = true }) { Text(stringResource(R.string.owner)) }
            }
        }
    )
    if (editPerms && single != null) PermissionsDialog(single.permissions ?: "rw-r--r--", single.isDirectory, onDismiss = { editPerms = false }) { mode, rec ->
        vm.chmod(mode, rec) { editPerms = false; onDismiss() }
    }
    if (editOwner && single != null) OwnerDialog(single.owner ?: "", single.group ?: "", single.isDirectory, onDismiss = { editOwner = false }) { o, g, rec ->
        vm.chown(o, g, rec) { editOwner = false; onDismiss() }
    }
}

@Composable
private fun Prop(label: String, value: String, mono: Boolean = false) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = if (mono) MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyMedium)
    }
}

/** chmod editor: owner/group/others × read/write/execute checkboxes. */
@Composable
fun PermissionsDialog(current: String, isDir: Boolean, onDismiss: () -> Unit, onApply: (Int, Boolean) -> Unit) {
    val initial = PosixInfo.parseMode(current)
    val bits = remember { mutableStateListOf(*Array(9) { i -> initial and (1 shl (8 - i)) != 0 }) }
    var recursive by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.permissions)) },
        text = {
            Column {
                Row { Text("", Modifier.weight(1.4f)); listOf("R", "W", "X").forEach { Text(it, Modifier.weight(1f)) } }
                listOf(R.string.perm_owner, R.string.perm_group, R.string.perm_others).forEachIndexed { row, label ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(label), Modifier.weight(1.4f))
                        for (col in 0..2) Checkbox(bits[row * 3 + col], { bits[row * 3 + col] = it }, Modifier.weight(1f))
                    }
                }
                val mode = bits.foldIndexed(0) { i, acc, b -> if (b) acc or (1 shl (8 - i)) else acc } or (initial and 0b111000000000)
                Text(Integer.toOctalString(mode).padStart(3, '0') + "  " + PosixInfo.permissionString(mode).drop(1), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                if (isDir) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(recursive, { recursive = it }); Text(stringResource(R.string.apply_recursively))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val mode = bits.foldIndexed(0) { i, acc, b -> if (b) acc or (1 shl (8 - i)) else acc } or (initial and 0b111000000000)
                onApply(mode, recursive)
            }) { Text(stringResource(R.string.apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
fun OwnerDialog(owner: String, group: String, isDir: Boolean, onDismiss: () -> Unit, onApply: (String, String, Boolean) -> Unit) {
    var o by remember { mutableStateOf(owner) }
    var g by remember { mutableStateOf(group) }
    var recursive by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.owner)) },
        text = {
            Column {
                OutlinedTextField(o, { o = it }, label = { Text(stringResource(R.string.perm_owner)) }, singleLine = true)
                OutlinedTextField(g, { g = it }, label = { Text(stringResource(R.string.perm_group)) }, singleLine = true)
                if (isDir) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(recursive, { recursive = it }); Text(stringResource(R.string.apply_recursively)) }
            }
        },
        confirmButton = { TextButton(enabled = o.isNotBlank(), onClick = { onApply(o.trim(), g.trim().ifEmpty { o.trim() }, recursive) }) { Text(stringResource(R.string.apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
