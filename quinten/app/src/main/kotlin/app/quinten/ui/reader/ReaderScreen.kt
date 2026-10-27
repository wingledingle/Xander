package app.quinten.ui.reader

import app.quinten.ui.common.QButton
import app.quinten.ui.common.QOutlinedButton
import app.quinten.ui.common.QTextButton
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.RectF
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import app.quinten.model.LinkTarget
import app.quinten.model.Locator
import app.quinten.render.Pos
import app.quinten.render.ReaderListener
import app.quinten.render.ReaderTheme
import app.quinten.render.ReaderView
import app.quinten.tts.TtsController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Hook so the activity can route volume keys to the open book. */
object ReaderKeys {
    /** One handler per window, so each open book gets its own volume keys. */
    val handlers = java.util.WeakHashMap<Activity, (forward: Boolean) -> Boolean>()
}

enum class Panel { NONE, TYPOGRAPHY, CONTENTS, CLEAN, SEARCH, TTS }

data class SelectionState(val start: Pos, val end: Pos, val text: String, val anchor: RectF)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReaderScreen(bookId: Long, initialLocator: String? = null, onBack: () -> Unit) {
    val vm: ReaderViewModel = viewModel()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Android 13+: the read-aloud notification (pause/stop, lock screen) needs permission.
    val notificationPermission = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
    fun askNotifications() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) runCatching { notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
    }

    // The link's position (e.g. from Notes) applies once; after process death the saved place wins.
    var linkUsed by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(bookId) { vm.open(bookId, if (linkUsed) null else Locator.decode(initialLocator)); linkUsed = true }

    val load by vm.load.collectAsState()
    val settings by vm.readerSettings.collectAsState()
    val appPrefs by vm.appPrefs.collectAsState()
    val book by vm.book.collectAsState()
    val progress by vm.progress.collectAsState()
    val bookmarks by vm.bookmarks.collectAsState()
    val ttsState by vm.ttsState.collectAsState()
    val ocrProgress by vm.ocrProgress.collectAsState()

    var menu by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf(Panel.NONE) }
    var selection by remember { mutableStateOf<SelectionState?>(null) }
    var footnote by remember { mutableStateOf<String?>(null) }
    var externalLink by remember { mutableStateOf<String?>(null) }
    var removalInfo by remember { mutableStateOf<String?>(null) }
    var imageResource by remember { mutableStateOf<String?>(null) }
    var noteEditor by remember { mutableStateOf<SelectionState?>(null) }
    val linkHistory = remember { ArrayList<Pos>() }
    var readerView by remember { mutableStateOf<ReaderView?>(null) }
    var bookmarked by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    // Auto-scroll: the controls stay while paused so it can be resumed.
    var autoBar by remember { mutableStateOf(false) }
    var autoRunning by remember { mutableStateOf(false) }
    var autoSpeed by remember { mutableStateOf(6) }

    // Immersive reading: system bars only with the menu.
    val view = LocalView.current
    val barsWanted = menu || panel != Panel.NONE
    val applyBars = rememberUpdatedState {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            val c = WindowCompat.getInsetsController(window, view)
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (barsWanted) c.show(WindowInsetsCompat.Type.systemBars()) else c.hide(WindowInsetsCompat.Type.systemBars())
        }
    }
    DisposableEffect(barsWanted) { applyBars.value(); onDispose {} }
    // Popups, the keyboard, other apps and split-screen changes bring the bars back; hide them again
    // whenever the reader gets focus back, and whenever they reappear while reading.
    DisposableEffect(view) {
        val focus = android.view.ViewTreeObserver.OnWindowFocusChangeListener { has -> if (has) view.post { applyBars.value() } }
        view.viewTreeObserver.addOnWindowFocusChangeListener(focus)
        val owner = view.context as? androidx.lifecycle.LifecycleOwner
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) applyBars.value() }
        owner?.lifecycle?.addObserver(obs)
        onDispose {
            view.viewTreeObserver.removeOnWindowFocusChangeListener(focus)
            owner?.lifecycle?.removeObserver(obs)
        }
    }
    // Bars swiped in (or left behind by something else) while reading: hide them again after a moment.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val nav = WindowInsets.navigationBars
    val navVisible = nav.getBottom(density) > 0 || nav.getLeft(density, androidx.compose.ui.unit.LayoutDirection.Ltr) > 0 ||
        nav.getRight(density, androidx.compose.ui.unit.LayoutDirection.Ltr) > 0
    LaunchedEffect(navVisible, barsWanted) {
        if (navVisible && !barsWanted) { kotlinx.coroutines.delay(2500); applyBars.value() }
    }
    DisposableEffect(appPrefs.keepScreenOn) {
        view.keepScreenOn = appPrefs.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
    // Reading brightness (only while reading; -1 follows the phone's setting).
    DisposableEffect(appPrefs.screenBrightness) {
        val window = (view.context as? Activity)?.window
        window?.let { w ->
            w.attributes = w.attributes.apply {
                screenBrightness = if (appPrefs.screenBrightness < 0f) android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                else appPrefs.screenBrightness.coerceIn(0.01f, 1f)
            }
        }
        onDispose {
            window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE } }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            (view.context as? Activity)?.let { ReaderKeys.handlers.remove(it) }
            (view.context as? Activity)?.window?.let { WindowCompat.getInsetsController(it, view).show(WindowInsetsCompat.Type.systemBars()) }
            vm.finishSession()
        }
    }
    (view.context as? Activity)?.let { act ->
        if (appPrefs.volumeKeysTurnPages) ReaderKeys.handlers[act] = { forward ->
            readerView?.let { if (forward) it.nextPageAction() else it.previousPageAction() }; readerView != null
        } else ReaderKeys.handlers.remove(act)
    }
    // Each window shows its book's title in the recent-apps list.
    LaunchedEffect(book?.title) {
        val t = book?.title ?: return@LaunchedEffect
        (view.context as? Activity)?.setTaskDescription(android.app.ActivityManager.TaskDescription(t))
    }

    BackHandler(enabled = panel != Panel.NONE || menu || selection != null || linkHistory.isNotEmpty()) {
        when {
            selection != null -> { readerView?.clearSelection(); selection = null }
            panel != Panel.NONE -> panel = Panel.NONE
            menu -> menu = false
            linkHistory.isNotEmpty() -> readerView?.jumpTo(linkHistory.removeAt(linkHistory.size - 1))
        }
    }

    fun goToLocator(loc: Locator, highlightLen: Int = 0) {
        val rv = readerView ?: return
        vm.controllerOrNull?.searchHighlight = if (highlightLen > 0) loc to highlightLen else null
        rv.navigateTo(loc)
    }

    val bg = Color(settings.theme.background)
    Box(Modifier.fillMaxSize().background(bg)) {
        when (val l = load) {
            is ReaderViewModel.Load.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is ReaderViewModel.Load.Error -> Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(l.message, style = MaterialTheme.typography.titleMedium, color = Color(settings.theme.text))
                Spacer(Modifier.padding(8.dp))
                QTextButton(onClick = onBack) { Text("Back to library") }
            }
            is ReaderViewModel.Load.NeedsPassword -> PasswordPrompt(l.wrong, onOpen = { vm.submitPassword(it) }, onBack = onBack)
            is ReaderViewModel.Load.Ready -> {
                val rc = l.controller
                AndroidView(
                    modifier = Modifier.fillMaxSize().semantics { contentDescription = "Book text" },
                    factory = { ctx ->
                        ReaderView(ctx).also { rv ->
                            rv.controller = rc
                            readerView = rv
                            rv.listener = object : ReaderListener {
                                override fun onPositionChanged(pos: Pos, progress: Float) {
                                    vm.currentPageEnd = rv.currentPageEnd
                                    vm.onPosition(pos, progress)
                                    bookmarked = vm.bookmarks.value.any { b -> Locator.decode(b.position)?.let { vm.isOnCurrentPage(it) } == true }
                                }
                                override fun onCenterTap() { menu = !menu; panel = Panel.NONE }
                                override fun onLink(target: String, footnote: Boolean, from: Pos) {
                                    if (LinkTarget.isExternal(target)) { externalLink = target; return }
                                    if (footnote) {
                                        scope.launch { val t = vm.footnoteText(target); if (t != null) footnoteSet(t) else jump(target, from) }
                                    } else jump(target, from)
                                }
                                private fun footnoteSet(t: String) { footnote = t }
                                private fun jump(target: String, from: Pos) {
                                    val loc = vm.locatorOfLink(target) ?: return
                                    linkHistory += from
                                    goToLocator(loc)
                                }
                                override fun onImage(resource: String) { imageResource = resource }
                                override fun onSelection(start: Pos, end: Pos, text: String, anchor: RectF) { selection = SelectionState(start, end, text, anchor) }
                                override fun onSelectionCleared() { selection = null }
                                override fun onRemovalTap(removalId: String) { removalInfo = removalId }
                                override fun onEndOfBook() { Toast.makeText(ctx, "End of the book", Toast.LENGTH_SHORT).show() }
                                override fun onAutoScrollChanged(running: Boolean) { autoRunning = running }
                            }
                        }
                    },
                    update = { rv ->
                        if (rv.controller !== rc) rv.controller = rc
                        rv.applySettings(settings)
                    },
                )
                LaunchedEffect(rc, readerView) {
                    val rv0 = readerView ?: return@LaunchedEffect
                    // First open: the saved or requested place. Recreated screen: where the reader was.
                    (vm.pendingLocator?.also { vm.pendingLocator = null } ?: vm.lastLocator)?.let { loc -> rv0.navigateTo(loc) }
                    launch { vm.jumpRequests.collect { loc -> readerView?.navigateTo(loc) } }
                    vm.tts?.onSpeaking = { pos, len ->
                        if (pos.chapter < 0) rc.ttsRange = null
                        else {
                            rc.ttsRange = pos to len
                            readerView?.let { rv -> keepInView(rv, pos) }
                        }
                        readerView?.invalidate()
                    }
                }
            }
        }

        ocrProgress?.let { msg ->
            Surface(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(8.dp), shape = MaterialTheme.shapes.medium, tonalElevation = 4.dp) {
                Text(msg, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
            }
        }

        // Top bar
        AnimatedVisibility(menu, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    Column(Modifier.weight(1f)) {
                        Text(book?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        book?.authorList?.firstOrNull()?.let { Text(it, maxLines = 1, style = MaterialTheme.typography.bodySmall) }
                    }
                    IconButton(onClick = { panel = Panel.SEARCH }) { Icon(Icons.Default.Search, "Search in book") }
                    IconButton(onClick = { vm.toggleBookmark(); bookmarked = !bookmarked }) {
                        Icon(if (bookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, if (bookmarked) "Remove bookmark" else "Add bookmark")
                    }
                    Box {
                        IconButton(onClick = { moreMenu = true }) { Icon(Icons.Default.MoreVert, "More options") }
                        ReaderMoreMenu(moreMenu, { moreMenu = false }, vm, book?.format, onAutoScroll = {
                            readerView?.let { rv -> rv.autoScrollSpeed = autoSpeed; rv.startAutoScroll(); autoBar = true; menu = false }
                        })
                    }
                }
            }
        }

        // Bottom bar
        AnimatedVisibility(menu, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility).padding(horizontal = 12.dp, vertical = 4.dp)) {
                    var drag by remember { mutableStateOf<Float?>(null) }
                    val shown = drag ?: progress
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${(shown * 100).toInt()}%", Modifier.width(48.dp), style = MaterialTheme.typography.labelMedium)
                        Slider(
                            value = shown,
                            onValueChange = { drag = it },
                            onValueChangeFinished = {
                                drag?.let { p -> readerView?.let { rv -> linkHistory += rv.position(); rv.navigateToProgress(p) } }
                                drag = null
                            },
                            modifier = Modifier.weight(1f).semantics { contentDescription = "Reading progress" },
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                        IconButton(onClick = { panel = Panel.CONTENTS }) { Icon(Icons.AutoMirrored.Filled.List, "Contents, bookmarks and notes") }
                        IconButton(onClick = { panel = Panel.TYPOGRAPHY }) { Icon(Icons.Default.FormatSize, "Text size and layout") }
                        IconButton(onClick = { panel = Panel.CLEAN }) { Icon(Icons.Default.AutoFixHigh, "Clean reading mode") }
                        IconButton(onClick = { panel = Panel.TTS }) { Icon(Icons.Default.RecordVoiceOver, "Read aloud") }
                        IconButton(onClick = { vm.updateSettings { it.copy(paginated = !it.paginated) } }) {
                            Icon(
                                if (settings.paginated) Icons.Default.SwapVert else Icons.Default.AutoStories,
                                if (settings.paginated) "Switch to vertical scrolling" else "Switch to page turning",
                            )
                        }
                        IconButton(onClick = {
                            val night = settings.theme.isDark
                            vm.updateSettings { it.copy(theme = if (night) ReaderTheme.DAY else ReaderTheme.byId(appPrefs.nightTheme)) }
                        }) { Icon(if (settings.theme.isDark) Icons.Default.LightMode else Icons.Default.DarkMode, "Toggle night mode") }
                    }
                }
            }
        }

        if (autoBar && !menu) {
            Surface(
                Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility).padding(12.dp),
                shape = MaterialTheme.shapes.large, tonalElevation = 6.dp, shadowElevation = 6.dp,
            ) {
                Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { autoSpeed = (autoSpeed - 1).coerceAtLeast(1); readerView?.autoScrollSpeed = autoSpeed }) { Icon(Icons.Default.Remove, "Slower") }
                    Text("Speed $autoSpeed", style = MaterialTheme.typography.labelLarge)
                    IconButton(onClick = { autoSpeed = (autoSpeed + 1).coerceAtMost(20); readerView?.autoScrollSpeed = autoSpeed }) { Icon(Icons.Default.Add, "Faster") }
                    IconButton(onClick = { readerView?.let { if (autoRunning) it.stopAutoScroll() else it.startAutoScroll() } }) {
                        Icon(if (autoRunning) Icons.Default.Pause else Icons.Default.PlayArrow, if (autoRunning) "Pause auto-scroll" else "Resume auto-scroll")
                    }
                    IconButton(onClick = { readerView?.stopAutoScroll(); autoBar = false }) { Icon(Icons.Default.Close, "Stop auto-scroll") }
                }
            }
        }

        selection?.let { sel ->
            SelectionBar(
                modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility).padding(12.dp),
                onHighlight = { color -> vm.addHighlight(sel.start, sel.end, sel.text, color); readerView?.clearSelection(); selection = null },
                onNote = { noteEditor = sel; readerView?.clearSelection(); selection = null },
                onCopy = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Quote", sel.text))
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    readerView?.clearSelection(); selection = null
                },
                onShare = {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "“${sel.text}”\n— ${book?.title ?: ""}"), "Share quote"))
                    readerView?.clearSelection(); selection = null
                },
                onSpeak = { askNotifications(); vm.tts?.play(sel.start); readerView?.clearSelection(); selection = null },
                onSearch = { panel = Panel.SEARCH; vm.search(sel.text.take(60)); readerView?.clearSelection(); selection = null },
                onLookUp = {
                    // Hands the word to an installed dictionary/translator (offline ones stay offline).
                    val i = android.content.Intent(android.content.Intent.ACTION_PROCESS_TEXT).setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_PROCESS_TEXT, sel.text.trim().take(200))
                        .putExtra(android.content.Intent.EXTRA_PROCESS_TEXT_READONLY, true)
                    val apps = context.packageManager.queryIntentActivities(i, 0)
                    if (apps.isEmpty()) Toast.makeText(context, "No dictionary app is installed. Install one (an offline dictionary works without internet).", Toast.LENGTH_LONG).show()
                    else runCatching { context.startActivity(android.content.Intent.createChooser(i, "Look up \"${sel.text.trim().take(40)}\"")) }
                },
            )
        }
    }

    when (panel) {
        Panel.TYPOGRAPHY -> TypographySheet(vm, settings, appPrefs) { panel = Panel.NONE }
        Panel.CONTENTS -> ContentsSheet(vm, onDismiss = { panel = Panel.NONE }, onGo = { loc -> readerView?.position()?.let { linkHistory += it }; goToLocator(loc); panel = Panel.NONE; menu = false })
        Panel.CLEAN -> CleanSheet(vm, appPrefs) { panel = Panel.NONE }
        Panel.SEARCH -> SearchSheet(vm, onDismiss = { panel = Panel.NONE; vm.controllerOrNull?.searchHighlight = null }, onGo = { hit -> readerView?.position()?.let { linkHistory += it }; goToLocator(hit.locator, hit.length); panel = Panel.NONE; menu = false })
        Panel.TTS -> TtsSheet(vm, ttsState, appPrefs, onPlay = { askNotifications(); readerView?.position()?.let { p -> vm.tts?.toggle(p) } }) { panel = Panel.NONE }
        Panel.NONE -> {}
    }

    footnote?.let { text ->
        AlertDialog(
            onDismissRequest = { footnote = null },
            confirmButton = { QTextButton(onClick = { footnote = null }) { Text("Close") } },
            title = { Text("Note") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(text, style = MaterialTheme.typography.bodyLarge.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize * (settings.fontSizeSp / 20f).coerceIn(1f, 2.5f))) } },
        )
    }
    externalLink?.let { url ->
        AlertDialog(
            onDismissRequest = { externalLink = null },
            confirmButton = {
                QTextButton(onClick = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Link", url))
                    externalLink = null
                }) { Text("Copy link") }
            },
            dismissButton = {
                QTextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }; externalLink = null }) { Text("Open") }
            },
            title = { Text("External link") },
            text = { Text(url) },
        )
    }
    removalInfo?.let { id ->
        val r = vm.removals.collectAsState().value.firstOrNull { it.id == id }
        AlertDialog(
            onDismissRequest = { removalInfo = null },
            title = { Text("Hidden by Clean Reading Mode") },
            text = { Text("${r?.category?.label ?: "Removed item"}:\n\n“${r?.text ?: ""}”") },
            confirmButton = { QTextButton(onClick = { vm.restoreRemoval(id); removalInfo = null }) { Text("Restore this") } },
            dismissButton = { r?.let { rr -> QTextButton(onClick = { vm.setCategory(rr.category, false); removalInfo = null }) { Text("Keep all ${rr.category.label.lowercase()}") } } },
        )
    }
    noteEditor?.let { sel ->
        NoteDialog(initial = "", quote = sel.text, onDismiss = { noteEditor = null }) { note ->
            vm.addHighlight(sel.start, sel.end, sel.text, 0x66FFEB3B, note.ifBlank { null })
            noteEditor = null
        }
    }
    imageResource?.let { res ->
        ImageViewer(vm, res) { imageResource = null }
    }
    if (ttsState == TtsController.State.UNAVAILABLE) {
        LaunchedEffect(Unit) { Toast.makeText(context, "No offline text-to-speech voice is installed.", Toast.LENGTH_LONG).show() }
    }
}

/** Turns the page when the spoken sentence leaves the screen. */
private fun keepInView(rv: ReaderView, pos: Pos) {
    if (!rv.isOnScreen(pos)) rv.goTo(pos)
}

private fun ReaderView.position(): Pos = currentPosition

@Composable
private fun ReaderMoreMenu(expanded: Boolean, onDismiss: () -> Unit, vm: ReaderViewModel, format: String?, onAutoScroll: () -> Unit) {
    val prefs by vm.prefs.collectAsState()
    val fixed = format in setOf("PDF", "DJVU", "CBZ", "CBR")
    val ctx = LocalContext.current
    DropdownMenu(expanded, onDismiss) {
        DropdownMenuItem(text = { Text("Auto-scroll") }, onClick = { onAutoScroll(); onDismiss() })
        DropdownMenuItem(text = { Text("Open another book in a new window") }, onClick = { app.quinten.BookWindows.open(ctx, null); onDismiss() })
        if (fixed && format != "CBZ" && format != "CBR") {
            DropdownMenuItem(text = { Text(if (prefs.originalPages) "Reflow text (large print)" else "Show original pages") }, onClick = { vm.setOriginalPages(!prefs.originalPages); onDismiss() })
            DropdownMenuItem(text = { Text(if (prefs.ocrMode == "ALWAYS") "Use embedded text" else "Re-recognize text (OCR)") }, onClick = { vm.setOcrMode(if (prefs.ocrMode == "ALWAYS") "AUTO" else "ALWAYS"); onDismiss() })
        }
        if (format == "CBZ" || format == "CBR") {
            DropdownMenuItem(text = { Text(if (prefs.comicTextMode) "Show comic pages" else "Read comic as text (OCR)") }, onClick = { vm.setComicTextMode(!prefs.comicTextMode); onDismiss() })
        }
    }
}

/** Asks for the password of a protected PDF. */
@Composable
private fun PasswordPrompt(wrong: Boolean, onOpen: (String) -> Unit, onBack: () -> Unit) {
    var pw by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("This PDF is password protected", style = MaterialTheme.typography.titleMedium)
        if (wrong) Text("That password did not work. Try again.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        androidx.compose.material3.OutlinedTextField(
            pw, { pw = it }, label = { Text("Password") }, singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password, imeAction = androidx.compose.ui.text.input.ImeAction.Go),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { if (pw.isNotEmpty()) onOpen(pw) }),
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).semantics { contentDescription = "PDF password" },
        )
        Row {
            QTextButton(onClick = onBack) { Text("Back") }
            Spacer(Modifier.width(12.dp))
            QButton(onClick = { onOpen(pw) }, enabled = pw.isNotEmpty()) { Text("Open") }
        }
    }
}
