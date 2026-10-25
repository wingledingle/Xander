package com.xander.files.ui.ops

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.xander.files.R
import com.xander.files.domain.fs.FileEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class RenameMode { PATTERN, REPLACE, CASE, AFFIX }
enum class CaseMode { LOWER, UPPER, TITLE }

data class RenameSpec(
    val mode: RenameMode = RenameMode.PATTERN,
    /** Tokens: {name} original base name, {n} counter, {ext} extension, {date} modified yyyyMMdd. */
    val pattern: String = "{name}_{n}",
    val start: Int = 1,
    val pad: Int = 2,
    val find: String = "",
    val replace: String = "",
    val regex: Boolean = false,
    val case: CaseMode = CaseMode.LOWER,
    val prefix: String = "",
    val suffix: String = "",
    val keepExtension: Boolean = true,
)

object BatchRename {
    fun apply(entries: List<FileEntry>, spec: RenameSpec): List<Pair<FileEntry, String>> {
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        return entries.mapIndexed { i, e ->
            val hasExt = !e.isDirectory && e.name.contains('.') && !e.name.startsWith('.')
            val base = if (hasExt && spec.keepExtension) e.name.substringBeforeLast('.') else e.name
            val ext = if (hasExt) e.name.substringAfterLast('.') else ""
            val newBase = when (spec.mode) {
                RenameMode.PATTERN -> spec.pattern
                    .replace("{name}", base).replace("{ext}", ext)
                    .replace("{n}", (spec.start + i).toString().padStart(spec.pad.coerceIn(0, 8), '0'))
                    .replace("{date}", fmt.format(Date(e.lastModified)))
                RenameMode.REPLACE -> if (spec.find.isEmpty()) base else if (spec.regex) runCatching { base.replace(Regex(spec.find), spec.replace) }.getOrDefault(base) else base.replace(spec.find, spec.replace)
                RenameMode.CASE -> when (spec.case) {
                    CaseMode.LOWER -> base.lowercase(); CaseMode.UPPER -> base.uppercase()
                    CaseMode.TITLE -> base.split(' ').joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.titlecase() } }
                }
                RenameMode.AFFIX -> spec.prefix + base + spec.suffix
            }.replace('/', '_')
            e to if (hasExt && spec.keepExtension) "$newBase.$ext" else newBase
        }
    }

    /** Returns an error message if the result would collide or produce invalid names. */
    fun validate(result: List<Pair<FileEntry, String>>, existing: Set<String>): String? {
        val names = result.map { it.second }
        if (names.any { it.isBlank() || it == "." || it == ".." }) return "Some names would be empty"
        if (names.toSet().size != names.size) return "Some new names are identical"
        val renamedFrom = result.map { it.first.name }.toSet()
        if (names.any { it in existing && it !in renamedFrom }) return "A file with one of these names already exists"
        return null
    }
}

@Composable
fun BatchRenameDialog(entries: List<FileEntry>, existingNames: Set<String>, onDismiss: () -> Unit, onApply: (List<Pair<FileEntry, String>>) -> Unit) {
    var spec by remember { mutableStateOf(RenameSpec()) }
    val preview = BatchRename.apply(entries, spec)
    val error = BatchRename.validate(preview, existingNames)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.batch_rename)) },
        text = {
            Column {
                Row {
                    listOf(RenameMode.PATTERN to R.string.rename_pattern, RenameMode.REPLACE to R.string.rename_replace,
                        RenameMode.CASE to R.string.rename_case, RenameMode.AFFIX to R.string.rename_affix).forEach { (m, l) ->
                        FilterChip(spec.mode == m, { spec = spec.copy(mode = m) }, label = { Text(stringResource(l)) }, modifier = Modifier.padding(end = 4.dp))
                    }
                }
                when (spec.mode) {
                    RenameMode.PATTERN -> {
                        OutlinedTextField(spec.pattern, { spec = spec.copy(pattern = it) }, label = { Text(stringResource(R.string.pattern)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.pattern_help), style = MaterialTheme.typography.bodySmall)
                        Row {
                            OutlinedTextField(spec.start.toString(), { spec = spec.copy(start = it.toIntOrNull() ?: 1) }, label = { Text(stringResource(R.string.start_at)) },
                                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f).padding(end = 8.dp))
                            OutlinedTextField(spec.pad.toString(), { spec = spec.copy(pad = it.toIntOrNull() ?: 0) }, label = { Text(stringResource(R.string.digits)) },
                                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                        }
                    }
                    RenameMode.REPLACE -> {
                        OutlinedTextField(spec.find, { spec = spec.copy(find = it) }, label = { Text(stringResource(R.string.find)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(spec.replace, { spec = spec.copy(replace = it) }, label = { Text(stringResource(R.string.replace_with)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(spec.regex, { spec = spec.copy(regex = it) }); Text(stringResource(R.string.regex)) }
                    }
                    RenameMode.CASE -> Row {
                        listOf(CaseMode.LOWER to "abc", CaseMode.UPPER to "ABC", CaseMode.TITLE to "Abc").forEach { (c, l) ->
                            FilterChip(spec.case == c, { spec = spec.copy(case = c) }, label = { Text(l) }, modifier = Modifier.padding(end = 4.dp))
                        }
                    }
                    RenameMode.AFFIX -> {
                        OutlinedTextField(spec.prefix, { spec = spec.copy(prefix = it) }, label = { Text(stringResource(R.string.prefix)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(spec.suffix, { spec = spec.copy(suffix = it) }, label = { Text(stringResource(R.string.suffix)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(spec.keepExtension, { spec = spec.copy(keepExtension = it) }); Text(stringResource(R.string.keep_extension)) }
                HorizontalDivider()
                LazyColumn(Modifier.heightIn(max = 200.dp)) {
                    items(preview) { (e, n) ->
                        Text("${e.name}  →  $n", style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.padding(vertical = 2.dp))
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(enabled = error == null, onClick = { onApply(preview) }) { Text(stringResource(R.string.rename)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
