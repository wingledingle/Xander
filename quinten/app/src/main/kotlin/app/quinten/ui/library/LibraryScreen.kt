package app.quinten.ui.library

import app.quinten.ui.common.QButton
import app.quinten.ui.common.QOutlinedButton
import app.quinten.ui.common.QTextButton
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.StickyNote2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.quinten.data.BookEntity
import app.quinten.settings.LibrarySort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Decoded covers keyed by "path@lastModified", so a rewritten cover file is never shown stale. */
private val coverCache = LruCache<String, Bitmap>(120)
/** Last cache key seen for a cover path (instant first frame without touching the disk). */
private val coverKeys = java.util.concurrent.ConcurrentHashMap<String, String>()

@Composable
fun BookCover(book: BookEntity, modifier: Modifier = Modifier) {
    val path = book.coverPath
    val bmp by produceState(path?.let { coverKeys[it] }?.let { coverCache.get(it) }, path, book.metadataLoaded) {
        val p = path ?: run { value = null; return@produceState }
        value = withContext(Dispatchers.IO) {
            val key = p + "@" + File(p).lastModified()
            coverKeys[p] = key
            coverCache.get(key) ?: BitmapFactory.decodeFile(p)?.also { coverCache.put(key, it) }
        }
    }
    val style = app.quinten.ui.theme.LocalThemeStyle.current
    val scheme = MaterialTheme.colorScheme
    // Styled themes get book-cloth placeholders in their own colours, shape and lettering.
    val shape = style?.cardShape ?: RoundedCornerShape(6.dp)
    val bg = if (style != null) androidx.compose.ui.graphics.Brush.verticalGradient(listOf(scheme.primary.copy(alpha = 0.85f).compositeOver(scheme.surfaceContainer), scheme.surfaceContainerHighest))
    else androidx.compose.ui.graphics.SolidColor(placeholderColor(book.title))
    val ink = if (style != null) scheme.onPrimaryContainer else Color.White
    var box = modifier.aspectRatio(0.68f).clip(shape).background(bg)
    if (style != null) box = box.border(1.5.dp, scheme.primary, shape)
    Box(box, contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (style != null) androidx.compose.material3.HorizontalDivider(Modifier.width(36.dp).padding(bottom = 6.dp), thickness = 1.5.dp, color = scheme.primary)
            Text(book.title, color = ink, style = if (style != null) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, maxLines = 5, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(book.format, color = ink.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
            if (style != null) androidx.compose.material3.HorizontalDivider(Modifier.width(36.dp).padding(top = 6.dp), thickness = 1.5.dp, color = scheme.primary)
        }
    }
}

private fun Color.compositeOver(bg: Color) = Color(red * alpha + bg.red * (1 - alpha), green * alpha + bg.green * (1 - alpha), blue * alpha + bg.blue * (1 - alpha))

private fun placeholderColor(title: String): Color {
    val palette = listOf(0xFF2E5E4E, 0xFF5D4037, 0xFF283593, 0xFF6A1B9A, 0xFF00695C, 0xFF8D6E63, 0xFF455A64, 0xFFAD1457)
    return Color(palette[(title.hashCode() and 0x7FFFFFFF) % palette.size])
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    onOpen: (Long) -> Unit,
    onSettings: () -> Unit,
    onNotes: () -> Unit,
    onHistory: () -> Unit,
) {
    val vm: LibraryViewModel = viewModel()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val books by vm.books.collectAsState()
    val shelf by vm.shelf.collectAsState()
    val group by vm.group.collectAsState()
    val groups by vm.groups.collectAsState()
    val collections by vm.collections.collectAsState()
    val prefs by vm.prefs.collectAsState()
    val scan by vm.scan.collectAsState()
    val query by vm.query.collectAsState()
    var searching by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<BookEntity?>(null) }
    var newCollection by remember { mutableStateOf<Long?>(null) } // book id or -1
    var hasAccess by remember { mutableStateOf(vm.hasAllFilesAccess()) }

    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { vm.addBook(uri.toString())?.let(onOpen) }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) vm.addFolder(uri.toString()) }
    val allFiles = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        hasAccess = vm.hasAllFilesAccess()
        if (hasAccess) vm.scanNow()
    }
    val legacyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasAccess = granted; if (granted) vm.scanNow()
    }
    fun requestAllFiles() {
        if (Build.VERSION.SDK_INT >= 30) {
            val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
            runCatching { allFiles.launch(i) }.onFailure { allFiles.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        } else legacyPermission.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    LaunchedEffect(Unit) { if (prefs.autoScanOnStart) vm.scanNow() }
    LaunchedEffect(vm) { vm.messages.collect { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show() } }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                val now = vm.hasAllFilesAccess()
                if (now && !hasAccess) vm.scanNow()
                hasAccess = now
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) OutlinedTextField(query, { vm.query.value = it }, Modifier.fillMaxWidth(), placeholder = { Text("Title, author, series…") }, singleLine = true)
                    else Text(group?.key ?: "Library")
                },
                navigationIcon = {
                    if (group != null) IconButton(onClick = { vm.group.value = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = { searching = !searching; if (!searching) vm.query.value = "" }) { Icon(if (searching) Icons.Default.Close else Icons.Default.Search, "Search library") }
                    Box {
                        IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") }
                        DropdownMenu(sortMenu, { sortMenu = false }) {
                            LibrarySort.entries.forEach { s ->
                                DropdownMenuItem(text = { Text(s.name.lowercase().replaceFirstChar { it.uppercase() } + if (prefs.librarySort == s) "  ✓" else "") }, onClick = { vm.updateApp { it.copy(librarySort = s) }; sortMenu = false })
                            }
                        }
                    }
                    IconButton(onClick = { vm.updateApp { it.copy(libraryGrid = !it.libraryGrid) } }) {
                        Icon(if (prefs.libraryGrid) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView, if (prefs.libraryGrid) "List view" else "Grid view")
                    }
                    Box {
                        IconButton(onClick = { overflow = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(overflow, { overflow = false }) {
                            DropdownMenuItem(text = { Text("Scan for new books") }, leadingIcon = { Icon(Icons.Default.Refresh, null) }, onClick = { vm.scanNow(); overflow = false })
                            DropdownMenuItem(text = { Text("Full rescan") }, leadingIcon = { Icon(Icons.Default.Refresh, null) }, onClick = { vm.scanNow(full = true); overflow = false })
                            DropdownMenuItem(text = { Text("Notes & highlights") }, leadingIcon = { Icon(Icons.Default.StickyNote2, null) }, onClick = { onNotes(); overflow = false })
                            DropdownMenuItem(text = { Text("Reading history") }, leadingIcon = { Icon(Icons.Default.History, null) }, onClick = { onHistory(); overflow = false })
                            DropdownMenuItem(text = { Text("Settings & folders") }, leadingIcon = { Icon(Icons.Default.Settings, null) }, onClick = { onSettings(); overflow = false })
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { openFile.launch(arrayOf("*/*")) }) { Icon(Icons.Default.Add, "Open a book file") }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (scan.running) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Scanning… ${scan.found} books found in ${scan.folders} folders${scan.current?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp), maxLines = 1)
            } else if (scan.finishedAt > 0) {
                Text(
                    (if (scan.full) "Last full scan: " else "Last scan: ") + "${scan.added} new, ${scan.found} books in ${scan.folders} folders" +
                        (if (scan.unchanged > 0) " (${scan.unchanged} unchanged, skipped)" else "") + if (scan.failed > 0) ", ${scan.failed} could not be read" else "",
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            // Full device access is what finds every book; offer it until granted (not only when empty).
            if (!hasAccess) {
                androidx.compose.material3.Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), shape = RoundedCornerShape(8.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Only chosen folders are searched. Allow access to find every book on this device and SD card.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        QTextButton(onClick = ::requestAllFiles) { Text("Allow") }
                    }
                }
            }
            if (group == null) {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Shelf.entries.toList()) { s ->
                        FilterChip(selected = shelf == s, onClick = { vm.shelf.value = s }, label = { Text(s.label) })
                    }
                }
            }
            when {
                group == null && shelf == Shelf.COLLECTIONS -> CollectionsList(
                    collections.map { Triple(it.id, it.name, it.count) },
                    onOpen = { id, name -> vm.group.value = Group(Shelf.COLLECTIONS, name, id) },
                    onCreate = { newCollection = -1 },
                    onDelete = { vm.deleteCollection(it) },
                    onRename = { id, name -> vm.renameCollection(id, name) },
                )
                group == null && shelf in setOf(Shelf.AUTHORS, Shelf.SERIES, Shelf.FORMATS) -> LazyColumn {
                    items(groups) { (name, count) ->
                        Row(Modifier.fillMaxWidth().combinedClickable(onClick = { vm.group.value = Group(shelf, name) }).padding(16.dp)) {
                            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            Text("$count", style = MaterialTheme.typography.labelLarge)
                        }
                        HorizontalDivider()
                    }
                }
                books.isEmpty() -> EmptyLibrary(
                    hasAccess = hasAccess,
                    filtered = shelf != Shelf.ALL || query.isNotBlank() || group != null,
                    onAllFiles = ::requestAllFiles,
                    onFolder = { pickFolder.launch(null) },
                    onFile = { openFile.launch(arrayOf("*/*")) },
                )
                prefs.libraryGrid -> LazyVerticalGrid(GridCells.Adaptive(118.dp), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(books, key = { it.id }) { b ->
                        Column(Modifier.combinedClickable(onClick = { onOpen(b.id) }, onLongClick = { actionsFor = b }).semantics { contentDescription = "${b.title}, ${(b.progress * 100).toInt()} percent read" }) {
                            BookCover(b)
                            if (b.progress > 0f) LinearProgressIndicator(progress = { b.progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                            Text(b.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            b.authorList.firstOrNull()?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                else -> LazyColumn {
                    items(books, key = { it.id }) { b ->
                        Row(Modifier.fillMaxWidth().combinedClickable(onClick = { onOpen(b.id) }, onLongClick = { actionsFor = b }).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            BookCover(b, Modifier.width(56.dp))
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(b.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                Text(b.authorList.joinToString(", "), maxLines = 1, style = MaterialTheme.typography.bodySmall)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${b.format} · ${(b.progress * 100).toInt()}%" + if (b.missing) " · file missing" else "", style = MaterialTheme.typography.labelSmall)
                                    if (b.favorite) Icon(Icons.Default.Favorite, "Favourite", Modifier.padding(start = 4.dp).height(14.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                                if (b.progress > 0f) LinearProgressIndicator(progress = { b.progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    actionsFor?.let { b ->
        BookActionsDialog(b, vm, onOpen = { onOpen(b.id); actionsFor = null }, onNewCollection = { newCollection = b.id; actionsFor = null }, onDismiss = { actionsFor = null })
    }
    newCollection?.let { forBook ->
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newCollection = null },
            title = { Text("New collection") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true) },
            confirmButton = { QTextButton(enabled = name.isNotBlank(), onClick = { vm.createCollection(name, forBook.takeIf { it >= 0 }); newCollection = null }) { Text("Create") } },
            dismissButton = { QTextButton(onClick = { newCollection = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EmptyLibrary(hasAccess: Boolean, filtered: Boolean, onAllFiles: () -> Unit, onFolder: () -> Unit, onFile: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        if (filtered) { Text("No books here.", style = MaterialTheme.typography.titleMedium); return }
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(app.quinten.R.drawable.logo), contentDescription = null,
            modifier = Modifier.padding(bottom = 16.dp).height(140.dp).width(140.dp),
        )
        Text("Your library is empty", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Dingo Reader finds EPUB, PDF, DjVu, MOBI/AZW3, FB2, DOC/DOCX, RTF, TXT and CBZ/CBR books on your device. Everything stays offline.",
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 16.dp),
        )
        if (!hasAccess) QButton(onClick = onAllFiles, modifier = Modifier.fillMaxWidth()) { Text("Find all books on this device") }
        QOutlinedButton(onClick = onFolder, modifier = Modifier.fillMaxWidth()) { Text("Choose a folder") }
        QOutlinedButton(onClick = onFile, modifier = Modifier.fillMaxWidth()) { Text("Open a single file") }
    }
}

@Composable
private fun CollectionsList(items: List<Triple<Long, String, Int>>, onOpen: (Long, String) -> Unit, onCreate: () -> Unit, onDelete: (Long) -> Unit, onRename: (Long, String) -> Unit) {
    var renaming by remember { mutableStateOf<Pair<Long, String>?>(null) }
    LazyColumn {
        item {
            QTextButton(onClick = onCreate, modifier = Modifier.padding(8.dp)) { Icon(Icons.Default.Add, null); Text(" New collection") }
        }
        items(items, key = { it.first }) { (id, name, count) ->
            var menu by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().combinedClickableCompat({ onOpen(id, name) }, { menu = true }).padding(16.dp)) {
                Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Text("$count", style = MaterialTheme.typography.labelLarge)
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { renaming = id to name; menu = false })
                    DropdownMenuItem(text = { Text("Delete collection") }, onClick = { onDelete(id); menu = false })
                }
            }
            HorizontalDivider()
        }
    }
    renaming?.let { (id, old) ->
        var name by remember { mutableStateOf(old) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename collection") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { QTextButton(onClick = { onRename(id, name); renaming = null }) { Text("Save") } },
            dismissButton = { QTextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLongClick: () -> Unit) = this.combinedClickable(onClick = onClick, onLongClick = onLongClick)

@Composable
private fun BookActionsDialog(b: BookEntity, vm: LibraryViewModel, onOpen: () -> Unit, onNewCollection: () -> Unit, onDismiss: () -> Unit) {
    val collections by vm.collections.collectAsState()
    val member by vm.collectionsOf(b.id).collectAsState(initial = emptyList())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(b.title, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        text = {
            LazyColumn {
                item {
                    Text(listOfNotNull(b.authorList.joinToString(", ").ifBlank { null }, b.series?.let { s -> "$s${b.seriesIndex?.let { " #${it.toInt()}" } ?: ""}" }, b.format, "${(b.sizeBytes / 1024)} KB").joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    Text(File(b.location).name.takeIf { !b.location.startsWith("content://") } ?: b.fileName, style = MaterialTheme.typography.labelSmall)
                    b.description?.let { Text(it, maxLines = 6, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp)) }
                    QTextButton(onClick = onOpen) { Text("Read") }
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    QTextButton(onClick = { app.quinten.BookWindows.open(ctx, b.id); onDismiss() }) { Text("Open in a new window") }
                    QTextButton(onClick = { vm.setFavorite(b, !b.favorite) ; onDismiss() }) { Text(if (b.favorite) "Remove from favourites" else "Add to favourites") }
                    QTextButton(onClick = { vm.setFinished(b, b.finishedAt == 0L); onDismiss() }) { Text(if (b.finishedAt == 0L) "Mark as finished" else "Mark as not finished") }
                    QTextButton(onClick = { vm.reloadMetadata(b); onDismiss() }) { Text("Reload title & cover") }
                    QTextButton(onClick = { vm.remove(b); onDismiss() }) { Text("Remove from library (file is kept)") }
                    if (b.missing) {
                        var confirmForget by remember { mutableStateOf(false) }
                        QTextButton(onClick = { confirmForget = true }) { Text("Forget this book and its notes…") }
                        if (confirmForget) AlertDialog(
                            onDismissRequest = { confirmForget = false },
                            title = { Text("Forget this book?") },
                            text = { Text("Its reading position, bookmarks, highlights and notes are deleted. The book file itself is not touched.") },
                            confirmButton = { QTextButton(onClick = { vm.forget(b); confirmForget = false; onDismiss() }) { Text("Forget") } },
                            dismissButton = { QTextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
                        )
                    }
                    Text("Collections", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                }
                items(collections, key = { it.id }) { c ->
                    val inIt = c.id in member
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(inIt, { if (it) vm.addToCollection(b.id, c.id) else vm.removeFromCollection(b.id, c.id) })
                        Text(c.name)
                    }
                }
                item { QTextButton(onClick = onNewCollection) { Text("+ New collection…") } }
            }
        },
        confirmButton = { QTextButton(onClick = onDismiss) { Text("Close") } },
    )
}
