package app.quinten.ui.screens

import androidx.compose.ui.semantics.contentDescription

import androidx.compose.ui.semantics.semantics

import androidx.compose.ui.draw.paint

import androidx.compose.ui.draw.clip

import androidx.compose.foundation.layout.height

import androidx.compose.foundation.layout.width

import androidx.compose.foundation.border

import app.quinten.ui.common.QButton
import app.quinten.ui.common.QOutlinedButton
import app.quinten.ui.common.QTextButton
import androidx.compose.foundation.layout.size

import androidx.compose.foundation.background

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.quinten.container
import app.quinten.data.HighlightWithBook
import app.quinten.data.LibraryRepository
import app.quinten.model.LocatorRange
import app.quinten.render.ReaderTheme
import app.quinten.ui.library.LibraryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Screen(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}, content: @Composable (Modifier) -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = { Text(title) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }, actions = { actions() })
    }) { p -> content(Modifier.padding(p).fillMaxSize()) }
}

@Composable
private fun SwitchLine(label: String, sub: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(checked, onChange)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val vm: LibraryViewModel = viewModel()
    val context = LocalContext.current
    val c = context.container
    val prefs by vm.prefs.collectAsState()
    val folders by vm.folders.collectAsState()
    val scope = rememberCoroutineScope()
    var langs by remember { mutableStateOf(c.ocr.installedLanguages()) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) vm.addFolder(uri.toString()) }
    // Backup of reading data to a file the user picks (and restore from one).
    val exportData = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            val ok = runCatching {
                val text = c.library.exportReadingData()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }
            }.isSuccess
            android.widget.Toast.makeText(context, if (ok) "Reading data saved." else "Could not save the backup.", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    val importData = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val msg = runCatching {
                val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { String(it.readBytes()) } } ?: error("unreadable")
                val r = c.library.importReadingData(text)
                "Restored reading data for ${r.restored} books." + if (r.waiting > 0) " ${r.waiting} more will be restored when their files are found." else ""
            }.getOrElse { "That file is not a Dingo Reader backup." }
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }
    val importLang = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val name = app.quinten.data.BookFiles.displayName(context, uri) ?: "lang.traineddata"
            if (name.endsWith(".traineddata")) {
                // Language files are tens of MB: copy them off the main thread.
                val ok = withContext(Dispatchers.IO) {
                    runCatching { context.contentResolver.openInputStream(uri)?.use { c.ocr.importLanguage(name, it) } != null }.getOrDefault(false)
                }
                if (!ok) android.widget.Toast.makeText(context, "The language file could not be imported.", android.widget.Toast.LENGTH_LONG).show()
                langs = withContext(Dispatchers.IO) { c.ocr.installedLanguages() }
            } else {
                android.widget.Toast.makeText(context, "Choose a Tesseract “.traineddata” file.", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    var licenses by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<String?>(null) }
    Screen("Settings", onBack) { m ->
        Column(m.padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            Text("Book folders", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            val access = vm.hasAllFilesAccess()
            Text(
                if (access) "All-files access granted: internal storage and SD cards are scanned." else "Without all-files access only the folders below are scanned.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!access) QButton(onClick = {
                val i = if (android.os.Build.VERSION.SDK_INT >= 30) Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, android.net.Uri.parse("package:${context.packageName}"))
                else Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))
                runCatching { context.startActivity(i) }
            }) { Text("Allow access to all books on this device") }
            folders.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(android.net.Uri.decode(f.location).substringAfterLast(':').ifBlank { f.location }, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { removing = f.location }) { Icon(Icons.Default.Delete, "Remove folder") }
                }
            }
            Row {
                QOutlinedButton(onClick = { pickFolder.launch(null) }) { Text("Add folder") }
                QTextButton(onClick = { vm.scanNow() }) { Text("Scan now") }
            }
            SwitchLine("Scan for new books at start", checked = prefs.autoScanOnStart) { v -> vm.updateApp { it.copy(autoScanOnStart = v) } }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Reading", style = MaterialTheme.typography.titleMedium)
            SwitchLine("Clean Reading Mode by default", "Hide page numbers, running headers and watermarks. Can be changed per book.", prefs.cleanModeDefault) { v -> vm.updateApp { it.copy(cleanModeDefault = v) } }
            Text("Night theme (moon button in the reader)", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
            androidx.compose.foundation.layout.FlowRow {
                ReaderTheme.ALL.filter { it.isDark }.forEach { t ->
                    FilterChip(selected = prefs.nightTheme == t.id, onClick = { vm.updateApp { it.copy(nightTheme = t.id) } }, label = { Text(t.name) }, modifier = Modifier.padding(end = 6.dp))
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Backup", style = MaterialTheme.typography.titleMedium)
            Text("Reading positions, bookmarks, highlights, notes and collections, in one file. Books are matched by their content, so a backup also restores on a new phone.", style = MaterialTheme.typography.bodySmall)
            Row {
                QOutlinedButton(onClick = { exportData.launch("dingo-backup-${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())}.json") }) { Text("Save backup") }
                androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                QOutlinedButton(onClick = { importData.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text("Restore") }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Appearance", style = MaterialTheme.typography.titleMedium)
            Text("Styled themes", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
            Text("Wallpaper, fonts and buttons to match. Tap again to go back to plain colours.", style = MaterialTheme.typography.bodySmall)
            androidx.compose.foundation.lazy.LazyRow(
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(vertical = 8.dp),
            ) {
                items(app.quinten.ui.theme.AppThemes.STYLED.size) { i ->
                    val t = app.quinten.ui.theme.AppThemes.STYLED[i]
                    StyledThemeCard(t, selected = prefs.uiTheme == t.id) {
                        vm.updateApp { it.copy(uiTheme = if (it.uiTheme == t.id) "system" else t.id) }
                    }
                }
            }
            Text("App colours", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
            androidx.compose.foundation.layout.FlowRow {
                FilterChip(selected = prefs.uiTheme == "system", onClick = { vm.updateApp { it.copy(uiTheme = "system") } }, label = { Text("Wallpaper colours") }, modifier = Modifier.padding(end = 6.dp))
                app.quinten.ui.theme.AppThemes.ALL.forEach { t ->
                    FilterChip(
                        selected = prefs.uiTheme == t.id, onClick = { vm.updateApp { it.copy(uiTheme = t.id) } }, label = { Text(t.name) },
                        leadingIcon = { androidx.compose.foundation.layout.Box(Modifier.size(14.dp).background(t.swatch, androidx.compose.foundation.shape.CircleShape)) },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }
            Text("Light / dark", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
            androidx.compose.foundation.layout.FlowRow {
                listOf("system" to "Follow system", "light" to "Light", "dark" to "Dark", "black" to "Black (OLED)").forEach { (id, label) ->
                    FilterChip(selected = prefs.uiDarkMode == id, onClick = { vm.updateApp { it.copy(uiDarkMode = id) } }, label = { Text(label) }, modifier = Modifier.padding(end = 6.dp))
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Text recognition (OCR)", style = MaterialTheme.typography.titleMedium)
            Text("Scanned pages are recognised on the device. Installed languages: ${langs.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
            Row {
                langs.forEach { l ->
                    val selected = l in prefs.ocrLanguages.split('+')
                    FilterChip(selected = selected, onClick = {
                        val set = prefs.ocrLanguages.split('+').filter { it.isNotBlank() }.toMutableSet()
                        if (selected && set.size > 1) set -= l else set += l
                        val value = set.joinToString("+")
                        vm.updateApp { it.copy(ocrLanguages = value) }
                        c.ocr.languages = value
                    }, label = { Text(l) }, modifier = Modifier.padding(end = 6.dp))
                }
            }
            QOutlinedButton(onClick = { importLang.launch(arrayOf("*/*")) }) { Text("Import language (.traineddata)") }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            QTextButton(onClick = { licenses = true }) { Text("Open-source licences") }
            Text("Dingo Reader works fully offline and never modifies your book files.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 24.dp))
        }
    }
    removing?.let { loc ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Stop watching this folder?") },
            text = { Text("Its books are removed from the library. Their reading positions, bookmarks and notes are kept and come back if you add the folder or the books again. No files are deleted.") },
            confirmButton = { QButton(onClick = { vm.removeFolder(loc); removing = null }) { Text("Remove") } },
            dismissButton = { QTextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
    if (licenses) LicencesDialog { licenses = false }
}

/** A component shipped in the app, its licence and the asset with the full licence text (if any). */
private data class Component(val name: String, val licence: String, val asset: String?, val note: String? = null)

private val COMPONENTS = listOf(
    Component("Dingo Reader (this app's own code)", "Apache License 2.0 / MIT", "apache-2.0.txt"),
    Component("Kotlin, kotlinx.coroutines, kotlinx.serialization", "Apache License 2.0", "apache-2.0.txt"),
    Component("AndroidX: Core, Activity, Lifecycle, Navigation, Jetpack Compose, Material 3, Room, DataStore, DocumentFile", "Apache License 2.0", "apache-2.0.txt"),
    Component("PdfBox-Android (port of Apache PDFBox)", "Apache License 2.0, with BSD-style notices for PDFBox/FontBox and the Adobe CMaps", "pdfbox-android.txt"),
    Component("Bouncy Castle (inside PdfBox-Android)", "Bouncy Castle licence (MIT-style)", "bouncy-castle.txt"),
    Component("Liberation Sans font (inside PdfBox-Android)", "SIL Open Font License 1.1", "ofl-1.1-liberation-fonts.txt"),
    Component("Adobe Font Metrics (AFM) for the 14 core PDF fonts (inside PdfBox-Android)", "Adobe AFM licence (in the PdfBox-Android licence file)", "pdfbox-android.txt"),
    Component("Adobe Glyph List and Zapf Dingbats Glyph List (inside PdfBox-Android)", "Adobe BSD-style licence", "adobe-glyph-list.txt"),
    Component("Unicode data: Scripts.txt, BidiMirroring.txt (inside PdfBox-Android)", "Unicode licence", "unicode.txt"),
    Component("Tesseract4Android, Tesseract OCR", "Apache License 2.0", "apache-2.0.txt"),
    Component("tessdata_fast English model (eng.traineddata)", "Apache License 2.0", "apache-2.0.txt"),
    Component("Leptonica (native, inside Tesseract4Android)", "BSD 2-Clause licence", "leptonica-bsd-2-clause.txt"),
    Component("libjpeg-turbo (native, inside Tesseract4Android)", "IJG licence, BSD 3-Clause licence and zlib licence", "libjpeg-turbo.txt"),
    Component("libpng (native, inside Tesseract4Android)", "libpng licence (PNG Reference Library License)", "libpng.txt"),
    Component("jsoup", "MIT License", "mit-jsoup.txt"),
    Component("SCOWL English word list (OCR repair)", "SCOWL licence (MIT-style permission notice)", "scowl.txt"),
    Component("ONNX Runtime (runs the Kokoro voice model)", "MIT License", "mit-onnxruntime.txt"),
    Component("Libraries inside ONNX Runtime", "Their own licences (third-party notices)", "onnxruntime-third-party-notices.txt"),
    Component("Kokoro-82M voice model and voices (downloaded on request)", "Apache License 2.0", "apache-2.0.txt"),
    Component("misaki pronunciation dictionaries (Kokoro)", "Apache License 2.0", "apache-2.0.txt"),
    Component("djvu-rs (DjVu codecs, ported to Kotlin)", "MIT License", "mit-djvu-rs.txt"),
    Component("Theme fonts: Special Elite, Ultra, Syncopate, Smokum, Rochester", "Apache License 2.0", "apache-2.0.txt"),
    Component("Theme wallpapers (generated on the AI Horde)", "CC0 1.0 (public domain dedication)", "cc0-1.0.txt"),
    Component("App icon", "Provided by the project owner", null),
    Component(
        "CBR (RAR) decoder", "Provenance under review", null,
        "The origin of the RAR decompression code is under review. It has not been confirmed to be a clean-room implementation.",
    ),
)

@Composable
private fun LicencesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var open by remember { mutableStateOf<Component?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { QTextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Licences") },
        text = {
            LazyColumn {
                items(COMPONENTS) { comp ->
                    Column(
                        Modifier.fillMaxWidth()
                            .then(if (comp.asset != null) Modifier.clickable { open = comp } else Modifier)
                            .padding(vertical = 8.dp),
                    ) {
                        Text(comp.name, style = MaterialTheme.typography.bodyMedium)
                        Text(comp.licence + if (comp.asset != null) " · Read the licence" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        comp.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    HorizontalDivider()
                }
            }
        },
    )
    open?.let { comp ->
        val text by androidx.compose.runtime.produceState("Loading…", comp.asset) {
            value = withContext(Dispatchers.IO) {
                runCatching { context.assets.open("licenses/${comp.asset}").bufferedReader().use { it.readText() } }.getOrDefault("The licence text could not be loaded.")
            }
        }
        AlertDialog(
            onDismissRequest = { open = null },
            confirmButton = { QTextButton(onClick = { open = null }) { Text("Back") } },
            title = { Text(comp.licence, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, modifier = Modifier.verticalScroll(rememberScrollState()))
            },
        )
    }
}

/** Shares [text] as a Markdown file through the system share sheet. */
private suspend fun shareMarkdown(context: android.content.Context, fileName: String, text: String, title: String) {
    val uri = withContext(Dispatchers.IO) {
        val dir = java.io.File(context.cacheDir, "export").apply { mkdirs() }
        val f = java.io.File(dir, fileName)
        f.writeText(text)
        androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".export", f)
    }
    val send = Intent(Intent.ACTION_SEND).setType("text/markdown")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, title)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(send, title)) }
}

@Composable
fun NotesScreen(onBack: () -> Unit, onOpen: (Long, String) -> Unit) {
    val context = LocalContext.current
    val repo = context.container.library
    val notes by repo.observeAllHighlights().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    // One section per book (the book with the latest note first), its notes in reading order.
    val groups = remember(notes) {
        notes.groupBy { it.bookId }.map { (id, hs) ->
            Triple(id, hs.first().bookTitle, hs.sortedWith(LibraryRepository.byPosition<HighlightWithBook>({ it.range }, { it.progress })))
        }
    }
    Screen("Notes & highlights", onBack, actions = {
        if (notes.isNotEmpty()) IconButton(onClick = {
            scope.launch { shareMarkdown(context, "dingo-notes.md", repo.exportAllNotes(), "Export all notes") }
        }) { Icon(Icons.Default.Share, "Export all notes") }
    }) { m ->
        if (notes.isEmpty()) Text("Long-press text while reading to highlight it or add a note.", m.padding(24.dp))
        else LazyColumn(m) {
            for ((bookId, title, hs) in groups) {
                item(key = "book-$bookId") {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        IconButton(onClick = {
                            scope.launch {
                                val text = repo.exportNotes(bookId)
                                runCatching {
                                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, title), "Export notes"))
                                }
                            }
                        }) { Icon(Icons.Default.Share, "Export notes and bookmarks of $title") }
                    }
                    HorizontalDivider()
                }
                items(hs, key = { it.id }) { h ->
                    Column(Modifier.fillMaxWidth().clickable { LocatorRange.decode(h.range)?.let { onOpen(h.bookId, it.start.encode()) } }.padding(16.dp)) {
                        Text("“${h.text}”", maxLines = 4, overflow = TextOverflow.Ellipsis)
                        h.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        Text(
                            "${(h.progress * 100).toInt()}% · " + DateFormat.getDateInstance().format(Date(h.updatedAt)),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun HistoryScreen(onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val repo = LocalContext.current.container.library
    val history by repo.observeHistory().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    Screen("Reading history", onBack, actions = { QTextButton(onClick = { confirm = true }) { Text("Clear") } }) { m ->
        if (history.isEmpty()) Text("Books you read will appear here.", m.padding(24.dp))
        else LazyColumn(m) {
            items(history, key = { it.id }) { h ->
                val minutes = ((h.closedAt - h.openedAt) / 60000).coerceAtLeast(0)
                Column(Modifier.fillMaxWidth().clickable { onOpen(h.bookId) }.padding(16.dp)) {
                    Text(h.bookTitle, style = MaterialTheme.typography.titleSmall)
                    Text(
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(h.openedAt)) +
                            " · $minutes min · ${(h.startProgress * 100).toInt()}% → ${(h.endProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                HorizontalDivider()
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Clear reading history?") },
        text = { Text("Reading positions, bookmarks and notes are kept.") },
        confirmButton = { QButton(onClick = { scope.launch { repo.clearHistory() }; confirm = false }) { Text("Clear") } },
        dismissButton = { QTextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
}


/** Miniature of a styled theme: its wallpaper with the name and a button in the theme's look. */
@Composable
private fun StyledThemeCard(t: app.quinten.ui.theme.AppTheme, selected: Boolean, onClick: () -> Unit) {
    val style = t.style ?: return
    val scheme = t.dark
    Column(
        Modifier.width(118.dp).height(196.dp)
            .clip(style.cardShape)
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Gray, style.cardShape)
            .clickable(onClick = onClick)
            .paint(androidx.compose.ui.res.painterResource(style.thumbnail), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            .background(style.scrim)
            .semantics { contentDescription = "Theme ${t.name}" + if (selected) ", selected" else "" }
            .padding(8.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
    ) {
        Text(t.name, color = scheme.onBackground, fontFamily = style.headingFont, style = MaterialTheme.typography.titleMedium, maxLines = 2)
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxWidth().clip(style.buttonShape).background(scheme.primary).padding(vertical = 6.dp),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) { Text("Read", color = scheme.onPrimary, fontFamily = style.labelFont, style = MaterialTheme.typography.labelLarge) }
    }
}
