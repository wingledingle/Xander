@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.quinten.ui.reader

import app.quinten.ui.common.QButton
import app.quinten.ui.common.QOutlinedButton
import app.quinten.ui.common.QTextButton
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.StickyNote2
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.quinten.data.BookFiles
import app.quinten.data.Images
import app.quinten.model.Locator
import app.quinten.model.LocatorRange
import app.quinten.model.RemovalCategory
import app.quinten.render.ReaderSettings
import app.quinten.render.ReaderTheme
import app.quinten.render.TextAlign
import app.quinten.settings.AppPrefs
import app.quinten.tts.KokoroModels
import app.quinten.tts.TtsController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val HIGHLIGHT_COLORS = listOf(0x66FFEB3B, 0x6681C784, 0x6664B5F6, 0x66F48FB1, 0x66FFB74D)

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, display: String, steps: Int = 0, onChange: (Float) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.padding(vertical = 2.dp)) {
        Row { Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Text(display, style = MaterialTheme.typography.bodyMedium) }
        Slider(v, { v = it }, valueRange = range, steps = steps, onValueChangeFinished = { onChange(v) }, modifier = Modifier.semantics { contentDescription = label })
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TypographySheet(vm: ReaderViewModel, s: ReaderSettings, app: AppPrefs, onDismiss: () -> Unit) {
    ModalBottomSheet(contentWindowInsets = { WindowInsets.navigationBarsIgnoringVisibility }, onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text("Text", style = MaterialTheme.typography.titleLarge)
            // Font size with large +/- buttons for low-vision users.
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.updateSettings { it.copy(fontSizeSp = (it.fontSizeSp - 2).coerceAtLeast(ReaderSettings.MIN_FONT)) } }, modifier = Modifier.size(56.dp)) { Icon(Icons.Default.Remove, "Smaller text") }
                Text("${s.fontSizeSp.toInt()} sp", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                IconButton(onClick = { vm.updateSettings { it.copy(fontSizeSp = (it.fontSizeSp + 2).coerceAtMost(ReaderSettings.MAX_FONT)) } }, modifier = Modifier.size(56.dp)) { Icon(Icons.Default.Add, "Larger text") }
            }
            LabeledSlider("Font size", s.fontSizeSp, ReaderSettings.MIN_FONT..ReaderSettings.MAX_FONT, "${s.fontSizeSp.toInt()}") { v -> vm.updateSettings { it.copy(fontSizeSp = v) } }
            Text("Font", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("serif" to "Serif", "sans-serif" to "Sans", "sans-serif-condensed" to "Condensed", "monospace" to "Mono").forEach { (fam, label) ->
                    FilterChip(selected = s.fontFamily == fam, onClick = { vm.updateSettings { it.copy(fontFamily = fam) } }, label = {
                        Text(label, fontFamily = when (fam) { "serif" -> FontFamily.Serif; "monospace" -> FontFamily.Monospace; else -> FontFamily.SansSerif })
                    })
                }
            }
            val context = androidx.compose.ui.platform.LocalContext.current
            val fontsDir = remember { java.io.File(context.filesDir, "fonts").apply { mkdirs() } }
            var userFonts by remember { mutableStateOf(fontsDir.listFiles()?.filter { it.extension.lowercase() in setOf("ttf", "otf") }?.sortedBy { it.name } ?: emptyList()) }
            val importFont = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) {
                    val name = BookFiles.displayName(context, uri) ?: "font.ttf"
                    if (name.lowercase().endsWith(".ttf") || name.lowercase().endsWith(".otf")) {
                        val target = java.io.File(fontsDir, name)
                        context.contentResolver.openInputStream(uri)?.use { i -> target.outputStream().use { i.copyTo(it) } }
                        userFonts = fontsDir.listFiles()?.filter { it.extension.lowercase() in setOf("ttf", "otf") }?.sortedBy { it.name } ?: emptyList()
                        vm.updateSettings { it.copy(fontFamily = target.absolutePath) }
                    }
                }
            }
            if (userFonts.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                userFonts.forEach { f ->
                    FilterChip(selected = s.fontFamily == f.absolutePath, onClick = { vm.updateSettings { it.copy(fontFamily = f.absolutePath) } }, label = { Text(f.nameWithoutExtension, maxLines = 1) })
                }
            }
            QTextButton(onClick = { importFont.launch(arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/octet-stream", "*/*")) }) { Text("Import a font (TTF/OTF)…") }
            SwitchRow("Bold text", s.boldText) { b -> vm.updateSettings { it.copy(boldText = b) } }
            LabeledSlider("Line spacing", s.lineSpacing, 1f..2.5f, "%.2f".format(s.lineSpacing)) { v -> vm.updateSettings { it.copy(lineSpacing = v) } }
            LabeledSlider("Paragraph spacing", s.paragraphSpacing, 0f..2f, "%.1f em".format(s.paragraphSpacing)) { v -> vm.updateSettings { it.copy(paragraphSpacing = v) } }
            LabeledSlider("First-line indent", s.firstLineIndent, 0f..4f, "%.1f em".format(s.firstLineIndent)) { v -> vm.updateSettings { it.copy(firstLineIndent = v) } }
            LabeledSlider("Letter spacing", s.letterSpacing, -0.05f..0.3f, "%.2f".format(s.letterSpacing)) { v -> vm.updateSettings { it.copy(letterSpacing = v) } }
            LabeledSlider("Side margins", s.marginHorizontalDp.toFloat(), 0f..80f, "${s.marginHorizontalDp} dp") { v -> vm.updateSettings { it.copy(marginHorizontalDp = v.toInt()) } }
            LabeledSlider("Top & bottom margins", s.marginVerticalDp.toFloat(), 0f..80f, "${s.marginVerticalDp} dp") { v -> vm.updateSettings { it.copy(marginVerticalDp = v.toInt()) } }
            Text("Alignment", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                TextAlign.entries.forEachIndexed { i, a ->
                    SegmentedButton(selected = s.align == a, onClick = { vm.updateSettings { it.copy(align = a) } }, shape = SegmentedButtonDefaults.itemShape(i, TextAlign.entries.size)) {
                        Text(if (a == TextAlign.START) "Left" else "Justified")
                    }
                }
            }
            SwitchRow("Hyphenation", s.hyphenation) { b -> vm.updateSettings { it.copy(hyphenation = b) } }
            SwitchRow("Keep the book's centred headings", s.respectAlignment) { b -> vm.updateSettings { it.copy(respectAlignment = b) } }
            SwitchRow("Pages (off = continuous scrolling)", s.paginated) { b -> vm.updateSettings { it.copy(paginated = b) } }
            SwitchRow("Show images", s.showImages) { b -> vm.updateSettings { it.copy(showImages = b) } }
            SwitchRow("Status line (chapter, page, %)", s.showStatusBar) { b -> vm.updateSettings { it.copy(showStatusBar = b) } }
            Text("Theme", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            ThemePicker(s.theme) { t -> vm.updateSettings { it.copy(theme = t) } }
            Text("Brightness", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            SwitchRow("Use phone brightness", app.screenBrightness < 0f) { b -> vm.updateApp { it.copy(screenBrightness = if (b) -1f else 0.5f) } }
            if (app.screenBrightness >= 0f) {
                val window = (androidx.compose.ui.platform.LocalView.current.context as? android.app.Activity)?.window
                var level by remember(app.screenBrightness) { mutableStateOf(app.screenBrightness) }
                androidx.compose.material3.Slider(
                    value = level,
                    onValueChange = { v ->
                        level = v
                        window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = v } } // live while dragging
                    },
                    onValueChangeFinished = { vm.updateApp { it.copy(screenBrightness = level) } },
                    valueRange = 0.01f..1f,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Reading brightness" },
                )
            }
            SwitchRow("Keep screen on", app.keepScreenOn) { b -> vm.updateApp { it.copy(keepScreenOn = b) } }
            SwitchRow("Volume keys turn pages", app.volumeKeysTurnPages) { b -> vm.updateApp { it.copy(volumeKeysTurnPages = b) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentsSheet(vm: ReaderViewModel, onDismiss: () -> Unit, onGo: (Locator) -> Unit) {
    val toc by vm.toc.collectAsState()
    val bookmarks by vm.bookmarks.collectAsState()
    val highlights by vm.highlights.collectAsState()
    val pos by vm.position.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<app.quinten.data.HighlightEntity?>(null) }
    ModalBottomSheet(contentWindowInsets = { WindowInsets.navigationBarsIgnoringVisibility }, onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        TabRow(tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Contents") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Bookmarks (${bookmarks.size})") })
            Tab(tab == 2, { tab = 2 }, text = { Text("Notes (${highlights.size})") })
        }
        Box(Modifier.heightIn(min = 200.dp, max = 640.dp)) {
            when (tab) {
                0 -> if (toc.isEmpty()) Empty("This book has no table of contents.") else LazyColumn {
                    itemsIndexed(toc) { _, e ->
                        val current = e.chapter == pos.chapter
                        Text(
                            e.title,
                            Modifier.fillMaxWidth().clickable {
                                onGo(if (e.unit >= 0) Locator(e.chapter, e.unit, 0) else e.anchor?.let { a -> vm.locatorOfLink(app.quinten.model.LinkTarget(e.chapter, a).encode()) } ?: Locator(e.chapter, 0, 0))
                            }.padding(start = (16 + 16 * e.level.coerceAtMost(4)).dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                            style = if (e.level == 0) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                            color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                1 -> if (bookmarks.isEmpty()) Empty("No bookmarks yet. Tap the bookmark icon to add one.") else LazyColumn {
                    items(bookmarks, key = { it.id }) { b ->
                        Row(Modifier.fillMaxWidth().clickable { Locator.decode(b.position)?.let(onGo) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${(b.progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                                Text(b.snippet, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { vm.deleteBookmark(b.id) }) { Icon(Icons.Default.Delete, "Delete bookmark") }
                        }
                        HorizontalDivider()
                    }
                }
                else -> if (highlights.isEmpty()) Empty("No highlights or notes yet. Long-press text to select it.") else LazyColumn {
                    items(highlights, key = { it.id }) { h ->
                        Row(Modifier.fillMaxWidth().clickable { LocatorRange.decode(h.range)?.let { onGo(it.start) } }.padding(16.dp)) {
                            Box(Modifier.width(6.dp).height(48.dp).background(Color(h.color or 0xFF000000.toInt())))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("“${h.text}”", maxLines = 3, overflow = TextOverflow.Ellipsis)
                                h.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                            }
                            IconButton(onClick = { editing = h }) { Icon(Icons.Default.Edit, "Edit note") }
                            IconButton(onClick = { vm.deleteHighlight(h.id) }) { Icon(Icons.Default.Delete, "Delete highlight") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
    editing?.let { h ->
        NoteDialog(initial = h.note ?: "", quote = h.text, onDismiss = { editing = null }) { note ->
            vm.updateHighlight(h.copy(note = note.ifBlank { null })); editing = null
        }
    }
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text(text, style = MaterialTheme.typography.bodyLarge) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanSheet(vm: ReaderViewModel, app: AppPrefs, onDismiss: () -> Unit) {
    val prefs by vm.prefs.collectAsState()
    val preview by vm.previewRemovals.collectAsState()
    val removals by vm.removals.collectAsState()
    val enabled = prefs.cleanMode ?: app.cleanModeDefault
    ModalBottomSheet(contentWindowInsets = { WindowInsets.navigationBarsIgnoringVisibility }, onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Clean Reading Mode", style = MaterialTheme.typography.titleLarge)
            Text(
                "Hides page numbers, running headers and footers, and watermarks left over from print. " +
                    "The book's text, chapter headings, dialogue, footnotes and illustrations are kept. Your file is never changed.",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp),
            )
            SwitchRow("Clean Reading Mode for this book", enabled) { vm.setCleanMode(it) }
            SwitchRow("Preview removals (shown struck through)", preview) { vm.setPreview(it) }
            Text("Remove", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            RemovalCategory.entries.forEach { cat ->
                val count = removals.count { it.category == cat }
                SwitchRow("${cat.label}${if (count > 0) " ($count in this chapter)" else ""}", cat.name !in prefs.disabledCategories) { vm.setCategory(cat, it) }
            }
            Text("Detected in this chapter", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            if (removals.isEmpty()) Text("Nothing to remove here.", style = MaterialTheme.typography.bodyMedium)
            LazyColumn(Modifier.heightIn(max = 280.dp)) {
                items(removals, key = { it.id }) { r ->
                    val restored = r.id in prefs.restoredRemovals
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(r.category.label + if (r.sourcePage >= 0) " · page ${r.sourcePage + 1}" else "", style = MaterialTheme.typography.labelSmall)
                        }
                        QTextButton(onClick = { if (restored) vm.reapplyRemoval(r.id) else vm.restoreRemoval(r.id) }) { Text(if (restored) "Hide again" else "Restore") }
                    }
                }
            }
            if (prefs.restoredRemovals.isNotEmpty() || prefs.disabledCategories.isNotEmpty()) {
                QTextButton(onClick = { vm.resetRemovals() }) { Text("Reset all choices") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchSheet(vm: ReaderViewModel, onDismiss: () -> Unit, onGo: (SearchHit) -> Unit) {
    var q by remember { mutableStateOf("") }
    val results by vm.searchResults.collectAsState()
    val searching by vm.searching.collectAsState()
    ModalBottomSheet(contentWindowInsets = { WindowInsets.navigationBarsIgnoringVisibility }, onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            OutlinedTextField(
                q, { q = it }, Modifier.fillMaxWidth(), label = { Text("Search in book") }, singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search(q) }),
            )
            if (searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
            Text("${results.size} results", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = 6.dp))
            LazyColumn(Modifier.heightIn(max = 560.dp)) {
                items(results) { r ->
                    Column(Modifier.fillMaxWidth().clickable { onGo(r) }.padding(vertical = 10.dp)) {
                        r.chapterTitle?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                        Text(r.snippet, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TtsSheet(vm: ReaderViewModel, state: TtsController.State, app: AppPrefs, onPlay: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(contentWindowInsets = { WindowInsets.navigationBarsIgnoringVisibility }, onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Read aloud", style = MaterialTheme.typography.titleLarge)
            VoiceChooser(vm, app)
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center) {
                IconButton(onClick = onPlay, modifier = Modifier.size(72.dp)) {
                    Icon(if (state == TtsController.State.PLAYING) Icons.Default.Pause else Icons.Default.PlayArrow, if (state == TtsController.State.PLAYING) "Pause" else "Play", Modifier.size(48.dp))
                }
                IconButton(onClick = { vm.tts?.stop() }, modifier = Modifier.size(72.dp)) { Icon(Icons.Default.Stop, "Stop", Modifier.size(48.dp)) }
            }
            LabeledSlider("Speed", app.ttsRate, 0.5f..3f, "%.1f×".format(app.ttsRate)) { v -> vm.tts?.rate = v; vm.updateApp { it.copy(ttsRate = v) } }
            if (vm.tts?.hasPitch != false && app.ttsEngine != "kokoro") {
                LabeledSlider("Pitch", app.ttsPitch, 0.5f..2f, "%.1f".format(app.ttsPitch)) { v -> vm.tts?.pitch = v; vm.updateApp { it.copy(ttsPitch = v) } }
            }
            // Sleep timer: pause reading after a while (e.g. falling asleep to a book).
            val tts = vm.tts
            if (tts != null) {
                val sleep by tts.sleepMinutes.collectAsState()
                var left by remember { mutableStateOf(0L) }
                LaunchedEffect(sleep) {
                    while (sleep > 0) {
                        left = ((tts.sleepEndsAt - android.os.SystemClock.elapsedRealtime()) / 60_000L).coerceAtLeast(0) + 1
                        kotlinx.coroutines.delay(15_000)
                    }
                }
                Text(if (sleep > 0) "Sleep timer: stops in about $left min" else "Sleep timer", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "Off", 10 to "10 min", 15 to "15 min", 30 to "30 min", 45 to "45 min", 60 to "1 hour", 90 to "1½ hours").forEach { (m, label) ->
                        androidx.compose.material3.FilterChip(selected = sleep == m, onClick = { tts.setSleepTimer(m) }, label = { Text(label) })
                    }
                }
            }
            if (state == TtsController.State.UNAVAILABLE) Text("No text-to-speech engine/voice is available. Install one in Android settings.", color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Phone voices or Kokoro; Kokoro's one-time download / import and its voice list. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun VoiceChooser(vm: ReaderViewModel, app: AppPrefs) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val kState by KokoroModels.state.collectAsState()
    LaunchedEffect(Unit) { KokoroModels.refresh(context) }
    var importError by remember { mutableStateOf<String?>(null) }
    val pickZip = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importKokoro(uri) { importError = it }
    }
    val kokoro = app.ttsEngine == "kokoro" && kState is KokoroModels.State.Ready
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
        androidx.compose.material3.FilterChip(selected = !kokoro, onClick = { vm.setTtsEngine("system") }, label = { Text("Phone voices") })
        androidx.compose.material3.FilterChip(selected = kokoro, onClick = { vm.setTtsEngine("kokoro") }, label = { Text("Kokoro (natural)") },
            enabled = kState is KokoroModels.State.Ready)
    }
    when (val st = kState) {
        is KokoroModels.State.Ready -> if (kokoro) {
            Text("Kokoro voices run on this phone, offline.", style = MaterialTheme.typography.bodySmall)
            var open by remember { mutableStateOf(false) }
            val voices = remember(st) { KokoroModels.installedVoices(context) }
            val current = voices.firstOrNull { it.id == app.kokoroVoice } ?: voices.firstOrNull()
            Box {
                androidx.compose.material3.OutlinedButton(onClick = { open = true }, modifier = Modifier.padding(top = 4.dp)) { Text("Voice: ${current?.label ?: "–"}") }
                androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    for (v in voices) androidx.compose.material3.DropdownMenuItem(text = { Text(v.label) }, onClick = {
                        open = false
                        vm.updateApp { it.copy(kokoroVoice = v.id) }
                        vm.tts?.restart()
                    })
                }
            }
        } else Text("Uses the voices installed on your phone; works offline.", style = MaterialTheme.typography.bodySmall)
        is KokoroModels.State.Downloading -> {
            Text("Downloading Kokoro voices… ${st.done / 1_000_000} of ${st.total / 1_000_000} MB", style = MaterialTheme.typography.bodySmall)
            androidx.compose.material3.LinearProgressIndicator(progress = { if (st.total > 0) st.done.toFloat() / st.total else 0f }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
        }
        else -> {
            Text("Uses the voices installed on your phone; works offline. Kokoro is a natural-sounding voice that also runs offline once its files (about 106 MB) are on the phone.",
                style = MaterialTheme.typography.bodySmall)
            if (st is KokoroModels.State.Failed) Text(st.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                androidx.compose.material3.Button(onClick = { vm.downloadKokoro() }) { Text("Download Kokoro") }
                androidx.compose.material3.OutlinedButton(onClick = { pickZip.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("Import zip") }
            }
        }
    }
    importError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

@Composable
fun SelectionBar(
    modifier: Modifier,
    onHighlight: (Int) -> Unit,
    onNote: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onSpeak: () -> Unit,
    onSearch: () -> Unit,
    onLookUp: () -> Unit = {},
) {
    Surface(modifier, shape = MaterialTheme.shapes.large, tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Column(Modifier.padding(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(6.dp)) {
                HIGHLIGHT_COLORS.forEachIndexed { i, c ->
                    Box(Modifier.size(36.dp).clip(CircleShape).background(Color(c or 0xFF000000.toInt())).clickable { onHighlight(c) }.semantics { contentDescription = "Highlight colour ${i + 1}" })
                }
            }
            Row {
                IconButton(onClick = onNote) { Icon(Icons.Default.StickyNote2, "Add note") }
                IconButton(onClick = onCopy) { Icon(Icons.Default.ContentCopy, "Copy") }
                IconButton(onClick = onShare) { Icon(Icons.Default.Share, "Share") }
                IconButton(onClick = onSpeak) { Icon(Icons.Default.VolumeUp, "Read aloud from here") }
                IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "Search for selection") }
                IconButton(onClick = onLookUp) { Icon(Icons.Default.Translate, "Look up in a dictionary app") }
            }
        }
    }
}

@Composable
fun NoteDialog(initial: String, quote: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Note") },
        text = {
            Column {
                Text("“${quote.take(300)}”", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 120.dp), label = { Text("Your note") })
            }
        },
        confirmButton = { QTextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { QTextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Full-screen image viewer with pinch-to-zoom and panning. */
@Composable
fun ImageViewer(vm: ReaderViewModel, resource: String, onDismiss: () -> Unit) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(resource) {
        bitmap = withContext(Dispatchers.IO) {
            val doc = vm.controllerOrNull?.document ?: return@withContext null
            val data = if (resource.startsWith("page:")) doc.originalPage(resource.substring(5).toInt(), 2000) else doc.image(resource)
            Images.toBitmap(data, 3000)
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        val state = rememberTransformableState { zoom, pan, _ ->
            scale = (scale * zoom).coerceIn(1f, 8f)
            offset += pan
        }
        Box(Modifier.fillMaxSize().background(Color.Black).transformable(state)) {
            bitmap?.let {
                Image(
                    it.asImageBitmap(), "Illustration",
                    Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
        }
    }
}

@Suppress("unused")
private val unusedSp = 1.sp


private val backgroundSwatches = listOf(
    0xFFFFFF, 0xF4ECD8, 0xF0E1BC, 0xE6E0D0, 0xDCEFDC, 0xE0EDFA, 0xFAE9E9, 0xECE8FA,
    0x2E251B, 0x202124, 0x15192C, 0x2E3440, 0x1E2B1E, 0x101010, 0x000000,
)
private val textSwatches = listOf(
    0x000000, 0x1C1C1C, 0x43342A, 0x24160A, 0x1E2E1E, 0x14233C, 0x3E1E28,
    0xFFFFFF, 0xDCDCD6, 0xE4D0AC, 0xFFB000, 0x40FF6E, 0x9FD8FF, 0xF8C8DC,
)

/** Built-in reading themes plus a custom background / text colour pair. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ThemePicker(current: ReaderTheme, onPick: (ReaderTheme) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ReaderTheme.ALL.forEach { t ->
            val selected = current.id == t.id
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp).clickable { onPick(t) }.semantics { contentDescription = "Theme ${t.name}" }) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(Color(t.background))
                        .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Text("Aa", color = Color(t.text)) }
                Text(t.name, style = MaterialTheme.typography.labelSmall, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
    Text("Custom colours", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
    val custom = current.id.startsWith("custom:")
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).background(Color(current.background)).padding(10.dp)) {
        Text(if (custom) "Your colours: the quick brown fox" else "Pick a background and a text colour", color = Color(current.text))
    }
    Text("Background", style = MaterialTheme.typography.labelMedium)
    SwatchRow(backgroundSwatches, current.background and 0xFFFFFF, custom) { c ->
        val fg = if (custom) current.text and 0xFFFFFF else if (ReaderTheme.luminance(c or (0xFF shl 24)) < 0.45) 0xDCDCD6 else 0x1C1C1C
        onPick(ReaderTheme.custom(c, fg))
    }
    Text("Text", style = MaterialTheme.typography.labelMedium)
    SwatchRow(textSwatches, current.text and 0xFFFFFF, custom) { c -> onPick(ReaderTheme.custom(current.background and 0xFFFFFF, c)) }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SwatchRow(colors: List<Int>, selected: Int, active: Boolean, onPick: (Int) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        colors.forEach { c ->
            val on = active && c == selected
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(Color(c or (0xFF shl 24)))
                    .border(if (on) 3.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape)
                    .clickable { onPick(c) }
                    .semantics { contentDescription = "Colour #%06X".format(c) },
            )
        }
    }
}
