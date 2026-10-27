package com.xander.files.ui.editor

import android.graphics.Color as AColor
import android.util.TypedValue
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.xander.files.R
import com.xander.files.data.settings.SettingsRepository
import com.xander.files.domain.fs.Capability
import com.xander.files.domain.fs.FileSystemRegistry
import com.xander.files.domain.fs.FsException
import com.xander.files.domain.fs.FsPath
import com.xander.files.ui.common.ConfirmDialog
import com.xander.files.ui.common.TextInputDialog
import com.xander.files.ui.ops.FolderPickerDialog
import com.xander.files.ui.settings.ChoiceDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.Charset
import javax.inject.Inject

data class EditorDoc(val text: String, val encoding: String, val readOnlyReason: String?, val page: Int, val pages: Int, val size: Long)

@HiltViewModel
class TextEditorViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val registry: FileSystemRegistry,
    private val settings: SettingsRepository,
) : ViewModel() {
    var path: FsPath = FsPath.parse(saved.get<String>("path")!!)
        private set
    val doc = MutableStateFlow<EditorDoc?>(null)
    val error = MutableStateFlow<String?>(null)
    val dirty = MutableStateFlow(false)
    val saving = MutableStateFlow(false)
    private var encoding = "UTF-8"
    private var bom = false

    init { viewModelScope.launch { encoding = settings.settings.first().defaultEncoding; load(0, null) } }

    /** Files above [EDIT_LIMIT] open read-only in pages; [forceEdit] loads up to [HARD_LIMIT] for editing. */
    fun load(page: Int, enc: String?, forceEdit: Boolean = false) = viewModelScope.launch {
        try {
            val p = registry.provider(path)
            val st = p.stat(path)
            val writable = Capability.WRITE in p.capabilities(path) && st.canWrite
            val paged = st.size > EDIT_LIMIT && !(forceEdit && st.size <= HARD_LIMIT)
            val bytes = withContext(Dispatchers.IO) {
                p.openInput(path, if (paged) page.toLong() * PAGE else 0).use { s ->
                    if (paged) { val b = ByteArray(PAGE); var n = 0; while (n < PAGE) { val r = s.read(b, n, PAGE - n); if (r < 0) break; n += r }; b.copyOf(n) } else s.readBytes()
                }
            }
            val detected = enc ?: detect(bytes)
            encoding = detected
            val body = if (bom && page == 0) bytes.copyOfRange(bomLength(bytes), bytes.size) else bytes
            val text = String(body, Charset.forName(detected))
            doc.value = EditorDoc(text, detected, when {
                paged -> "large"; !writable -> "readonly"; else -> null
            }, page, if (paged) ((st.size + PAGE - 1) / PAGE).toInt() else 1, st.size)
            dirty.value = false
        } catch (e: Exception) { error.value = FsException.wrap(e, path).message }
    }

    private fun detect(b: ByteArray): String {
        bom = false
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) { bom = true; return "UTF-8" }
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) { bom = true; return "UTF-16LE" }
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) { bom = true; return "UTF-16BE" }
        // Valid UTF-8 wins; otherwise fall back to the user's default encoding.
        val dec = Charsets.UTF_8.newDecoder()
        return if (runCatching { dec.decode(java.nio.ByteBuffer.wrap(b, 0, minOf(b.size, 1 shl 20))) }.isSuccess) "UTF-8" else encoding
    }

    private fun bomLength(b: ByteArray) = if (b.size >= 3 && b[0] == 0xEF.toByte()) 3 else 2

    fun save(text: String, target: FsPath = path, onDone: () -> Unit = {}) = viewModelScope.launch {
        saving.value = true
        try {
            val p = registry.provider(target)
            withContext(Dispatchers.IO) {
                val cs = Charset.forName(encoding)
                p.openOutput(target).use { o ->
                    if (bom) when (encoding) { "UTF-8" -> o.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())); "UTF-16LE" -> o.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte())); "UTF-16BE" -> o.write(byteArrayOf(0xFE.toByte(), 0xFF.toByte())) }
                    o.write(text.toByteArray(cs))
                }
            }
            path = target
            dirty.value = false
            onDone()
        } catch (e: Exception) { error.value = FsException.wrap(e, target).message } finally { saving.value = false }
    }

    fun setEncoding(enc: String) { encoding = enc }

    companion object {
        const val EDIT_LIMIT = 5L shl 20
        const val HARD_LIMIT = 20L shl 20
        const val PAGE = 512 * 1024
    }
}

@Composable
fun TextEditorScreen(onBack: () -> Unit, vm: TextEditorViewModel = hiltViewModel()) {
    val doc by vm.doc.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val dirty by vm.dirty.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme() || MaterialTheme.colorScheme.background.toArgb().let { androidx.core.graphics.ColorUtils.calculateLuminance(it) < 0.3 }
    var editor by remember { mutableStateOf<CodeEditText?>(null) }
    var wrap by remember { mutableStateOf(false) }
    var lines by remember { mutableStateOf(true) }
    var fontSp by remember { mutableStateOf(14f) }
    var menu by remember { mutableStateOf(false) }
    var findBar by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var encodingDialog by remember { mutableStateOf(false) }
    var saveAsDir by remember { mutableStateOf(false) }
    var saveAsName by remember { mutableStateOf<FsPath?>(null) }
    var gotoLine by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    BackHandler { if (dirty) confirmExit = true else onBack() }
    error?.let { LaunchedToast(it) { vm.error.value = null } }

    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = { IconButton(onClick = { if (dirty) confirmExit = true else onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            title = {
                Column {
                    Text((if (dirty) "• " else "") + vm.path.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    doc?.let { Text(it.encoding + if (it.pages > 1) " · ${stringResource(R.string.page_of, it.page + 1, it.pages)}" else "", style = MaterialTheme.typography.labelSmall) }
                }
            },
            actions = {
                IconButton(onClick = { editor?.undo() }) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.undo)) }
                IconButton(onClick = { editor?.redo() }) { Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.redo)) }
                IconButton(onClick = { findBar = !findBar }) { Icon(Icons.Filled.Search, stringResource(R.string.find)) }
                if (doc?.readOnlyReason == null) IconButton(enabled = dirty && !saving, onClick = { editor?.text?.toString()?.let { vm.save(it) { Toast.makeText(ctx, R.string.saved, Toast.LENGTH_SHORT).show() } } }) {
                    if (saving) CircularProgressIndicator(Modifier.padding(8.dp)) else Icon(Icons.Filled.Save, stringResource(R.string.save))
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, null) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.save_as)) }, onClick = { menu = false; saveAsDir = true })
                        DropdownMenuItem(text = { Text(stringResource(R.string.word_wrap)) }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.WrapText, null) }, trailingIcon = { Checkbox(wrap, null) }, onClick = { wrap = !wrap })
                        DropdownMenuItem(text = { Text(stringResource(R.string.line_numbers)) }, trailingIcon = { Checkbox(lines, null) }, onClick = { lines = !lines })
                        DropdownMenuItem(text = { Text(stringResource(R.string.text_bigger)) }, onClick = { fontSp = (fontSp + 2).coerceAtMost(32f) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.text_smaller)) }, onClick = { fontSp = (fontSp - 2).coerceAtLeast(8f) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.encoding)) }, onClick = { menu = false; encodingDialog = true })
                        DropdownMenuItem(text = { Text(stringResource(R.string.go_to_line)) }, onClick = { menu = false; gotoLine = true })
                    }
                }
            }
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            val d = doc
            if (d == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return@Column }
            when (d.readOnlyReason) {
                "large" -> Surface(color = colors.secondaryContainer) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.large_file_hint), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        IconButton(enabled = d.page > 0, onClick = { vm.load(d.page - 1, d.encoding) }) { Icon(Icons.Filled.KeyboardArrowUp, null) }
                        IconButton(enabled = d.page < d.pages - 1, onClick = { vm.load(d.page + 1, d.encoding) }) { Icon(Icons.Filled.KeyboardArrowDown, null) }
                        if (d.size <= TextEditorViewModel.HARD_LIMIT) TextButton(onClick = { vm.load(0, d.encoding, forceEdit = true) }) { Text(stringResource(R.string.edit_anyway)) }
                    }
                }
                "readonly" -> Surface(color = colors.secondaryContainer) { Text(stringResource(R.string.read_only_location), Modifier.fillMaxWidth().padding(8.dp)) }
            }
            if (findBar) editor?.let { FindReplaceBar(it, d.readOnlyReason == null) { findBar = false } }
            AndroidView(
                factory = { c ->
                    CodeEditText(c).also { e ->
                        editor = e
                        e.onChanged = { vm.dirty.value = true }
                    }
                },
                update = { e ->
                    if (e.tag != d) {
                        e.tag = d
                        e.setText(d.text)
                        e.clearHistory()
                        vm.dirty.value = false
                        e.isEnabled = true
                        e.keyListener = if (d.readOnlyReason == null) android.text.method.TextKeyListener.getInstance() else null
                        e.highlighter = SyntaxHighlighter.forExtension(vm.path.extension, dark)
                    }
                    e.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp)
                    e.setHorizontallyScrolling(!wrap)
                    e.showLineNumbers = lines
                    e.setTextColor(colors.onSurface.toArgb())
                    e.gutterColor = colors.surfaceVariant.toArgb()
                    e.gutterTextColor = colors.onSurfaceVariant.toArgb()
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
    if (gotoLine) TextInputDialog(stringResource(R.string.go_to_line), "", selectBaseName = false,
        validator = { if (it.toIntOrNull() == null) "1, 2, 3…" else null }, onDismiss = { gotoLine = false }) { v ->
        gotoLine = false
        editor?.let { e ->
            val t = e.text?.toString().orEmpty(); var line = 1; var idx = 0
            val target = v.toInt().coerceAtLeast(1)
            while (line < target && idx < t.length) { if (t[idx] == '\n') line++; idx++ }
            e.requestFocus(); e.setSelection(idx.coerceAtMost(t.length))
        }
    }
    if (confirmExit) ConfirmDialog(stringResource(R.string.unsaved_changes), stringResource(R.string.unsaved_changes_body), stringResource(R.string.discard),
        onDismiss = { confirmExit = false }, onConfirm = { confirmExit = false; onBack() })
    if (encodingDialog) {
        val encs = listOf("UTF-8", "UTF-16LE", "UTF-16BE", "ISO-8859-1", "windows-1252", "windows-1251", "windows-1256", "ISO-8859-7", "KOI8-R", "GBK", "Big5", "Shift_JIS", "EUC-KR")
        ChoiceDialog(stringResource(R.string.encoding), encs, encs.indexOf(doc?.encoding), { encodingDialog = false }) { i ->
            if (dirty) vm.setEncoding(encs[i]) else vm.load(doc?.page ?: 0, encs[i])
        }
    }
    if (saveAsDir) FolderPickerDialog(stringResource(R.string.save_as), vm.path.parent, onDismiss = { saveAsDir = false }, onPick = { dir -> saveAsDir = false; saveAsName = dir })
    saveAsName?.let { dir -> TextInputDialog(stringResource(R.string.save_as), vm.path.name, onDismiss = { saveAsName = null }) { n ->
        saveAsName = null; editor?.text?.toString()?.let { vm.save(it, dir.child(n)) { Toast.makeText(ctx, R.string.saved, Toast.LENGTH_SHORT).show() } }
    } }
}

@Composable
private fun LaunchedToast(msg: String, done: () -> Unit) {
    val ctx = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(msg) { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show(); done() }
}

@Composable
private fun FindReplaceBar(editor: CodeEditText, editable: Boolean, onClose: () -> Unit) {
    var find by remember { mutableStateOf("") }
    var replace by remember { mutableStateOf("") }
    var matchCase by remember { mutableStateOf(false) }
    var regex by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf("") }
    fun pattern(): Regex? = runCatching {
        val opts = if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)
        if (regex) Regex(find, opts) else Regex(Regex.escape(find), opts)
    }.getOrNull()
    fun findNext(back: Boolean) {
        val re = pattern() ?: return
        val t = editor.text?.toString() ?: return
        val all = re.findAll(t).toList()
        if (all.isEmpty()) { info = "0"; return }
        val cur = editor.selectionStart
        val m = if (back) all.lastOrNull { it.range.first < cur } ?: all.last() else all.firstOrNull { it.range.first > cur } ?: all.first()
        editor.requestFocus(); editor.setSelection(m.range.first, m.range.last + 1)
        info = "${all.indexOf(m) + 1}/${all.size}"
    }
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(find, { find = it }, singleLine = true, label = { Text(stringResource(R.string.find)) }, modifier = Modifier.weight(1f))
                Text(info, Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.labelSmall)
                IconButton(onClick = { findNext(true) }) { Icon(Icons.Filled.KeyboardArrowUp, null) }
                IconButton(onClick = { findNext(false) }) { Icon(Icons.Filled.KeyboardArrowDown, null) }
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, null) }
            }
            if (editable) Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(replace, { replace = it }, singleLine = true, label = { Text(stringResource(R.string.replace_with)) }, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = {
                    val re = pattern() ?: return@OutlinedButton
                    val s = editor.selectionStart; val e = editor.selectionEnd
                    val sel = editor.text?.substring(s, e).orEmpty()
                    if (s != e && re.matches(sel)) editor.text?.replace(s, e, if (regex) re.replace(sel, replace) else replace)
                    findNext(false)
                }, modifier = Modifier.padding(start = 4.dp)) { Text(stringResource(R.string.replace)) }
                OutlinedButton(onClick = {
                    val re = pattern() ?: return@OutlinedButton
                    val t = editor.text?.toString() ?: return@OutlinedButton
                    val count = re.findAll(t).count()
                    editor.text?.replace(0, t.length, if (regex) re.replace(t, replace) else re.replace(t, Regex.escapeReplacement(replace)))
                    info = "$count"
                }, modifier = Modifier.padding(start = 4.dp)) { Text(stringResource(R.string.replace_all)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(matchCase, { matchCase = it }); Text(stringResource(R.string.match_case))
                Checkbox(regex, { regex = it }); Text(stringResource(R.string.regex))
            }
        }
    }
}
