package app.quinten.ui.reader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.quinten.analysis.paged.PagedBookDocument
import app.quinten.container
import app.quinten.data.BookEntity
import app.quinten.data.BookFiles
import app.quinten.data.BookPrefs
import app.quinten.data.BookmarkEntity
import app.quinten.data.HighlightEntity
import app.quinten.data.OpenedFile
import app.quinten.model.BlockKind
import app.quinten.model.BookFormat
import app.quinten.model.Locator
import app.quinten.model.LocatorRange
import app.quinten.model.Removal
import app.quinten.model.RemovalCategory
import app.quinten.model.TextBlock
import app.quinten.model.TocEntry
import app.quinten.render.Annotation
import app.quinten.render.Pos
import app.quinten.render.ReaderController
import app.quinten.render.ReaderSettings
import app.quinten.settings.AppPrefs
import app.quinten.tts.TtsController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SearchHit(val locator: Locator, val length: Int, val snippet: String, val chapterTitle: String?, val progress: Float)

class ReaderViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    private val repo = c.library

    sealed class Load {
        object Loading : Load()
        data class Error(val message: String) : Load()
        /** A protected PDF: ask for its password ([wrong]: the last one did not work). */
        data class NeedsPassword(val wrong: Boolean) : Load()
        data class Ready(val controller: ReaderController) : Load()
    }

    val load = MutableStateFlow<Load>(Load.Loading)
    val book = MutableStateFlow<BookEntity?>(null)
    val prefs = MutableStateFlow(BookPrefs())
    val position = MutableStateFlow(Pos(0, 0, 0))
    val progress = MutableStateFlow(0f)
    val toc = MutableStateFlow<List<TocEntry>>(emptyList())
    val previewRemovals = MutableStateFlow(false)
    val removals = MutableStateFlow<List<Removal>>(emptyList())
    val searchResults = MutableStateFlow<List<SearchHit>>(emptyList())
    val searching = MutableStateFlow(false)
    val ttsState = MutableStateFlow(TtsController.State.IDLE)
    val ocrProgress = MutableStateFlow<String?>(null)

    val readerSettings: StateFlow<ReaderSettings> = c.settings.reader.stateIn(viewModelScope, SharingStarted.Eagerly, ReaderSettings())
    val appPrefs: StateFlow<AppPrefs> = c.settings.app.stateIn(viewModelScope, SharingStarted.Eagerly, AppPrefs())

    private val bookId = MutableStateFlow(-1L)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val bookmarks = bookId.flatMapLatest { if (it < 0) emptyFlow() else repo.observeBookmarks(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val highlights = bookId.flatMapLatest { if (it < 0) emptyFlow() else repo.observeHighlights(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var opened: OpenedFile? = null
    private var controller: ReaderController? = null
    private var session = -1L
    private var saveJob: Job? = null
    @Volatile private var searchGeneration = 0
    /** A position not yet written (debounced); flushed when the reader closes. */
    @Volatile private var pendingSave: Triple<Long, Float, String>? = null
    private var searchJob: Job? = null
    var tts: TtsController? = null
        private set

    val controllerOrNull get() = controller

    fun open(id: Long, at: Locator? = null) {
        // Already open (e.g. the screen was recreated): keep the reader where it is.
        if (bookId.value == id && (load.value is Load.Ready || load.value is Load.Loading)) return
        bookId.value = id
        viewModelScope.launch {
            val b = repo.book(id) ?: run { load.value = Load.Error("Book not found"); return@launch }
            book.value = b
            prefs.value = repo.prefs(id)
            openDocument(b, at ?: b.position?.let { Locator.decode(it) })
            session = repo.startSession(id, b.progress)
        }
        viewModelScope.launch {
            combine(highlights, readerSettings) { h, _ -> h }.collect { pushAnnotations(it) }
        }
    }

    private suspend fun openDocument(b: BookEntity, at: Locator?, atPage: Int? = null) {
        load.value = Load.Loading
        val ctx = getApplication<Application>()
        val result = withContext(Dispatchers.IO) {
            runCatching {
                opened?.close()
                val of = BookFiles.open(ctx, b.location)
                opened = of
                val doc = c.documents.open(of.file, BookFormat.valueOf(b.format), b.id, prefs.value)
                (doc as? PagedBookDocument)?.progressListener = { page, done, total ->
                    ocrProgress.value = if (done >= total) null else "Preparing page ${page + 1}…"
                }
                val dm = ctx.resources.displayMetrics
                ReaderController(doc, dm.density, dm.scaledDensity)
            }
        }
        result.onSuccess { rc ->
            controller?.close()
            controller = rc
            rc.settings = readerSettings.value
            rc.clean = cleanOptions()
            toc.value = rc.document.toc
            pushAnnotations(highlights.value)
            val startPos = atPage?.let { runCatching { rc.document.locatorForOriginalPage(it) }.getOrNull() } ?: at ?: Locator.START
            // The chapter must be loaded to map the locator; the view handles that through goTo.
            pendingLocator = startPos
            tts?.shutdown()
            tts = TtsController(ctx, rc, speechBackend(ctx)).also {
                it.rate = appPrefs.value.ttsRate; it.pitch = appPrefs.value.ttsPitch
                it.onStateChanged = { s -> ttsState.value = s }
            }
            load.value = Load.Ready(rc)
        }.onFailure { e ->
            if (e is app.quinten.pdf.PdfPasswordException) { load.value = Load.NeedsPassword(e.wrongPassword); return@onFailure }
            load.value = Load.Error(
                when (e) {
                    is app.quinten.data.PermissionLostException -> e.message ?: "Permission to this file was lost — open it again from your file manager."
                    is java.io.FileNotFoundException -> "The file is no longer available. It may have been moved or deleted."
                    is app.quinten.formats.DrmProtectedException -> "This book is DRM-protected and cannot be opened."
                    is app.quinten.pdf.PdfPasswordException -> "This PDF is password protected."
                    else -> e.message ?: "This book could not be opened."
                },
            )
            // The file is still there when only the permission was lost: don't flag it missing.
            if (e is java.io.FileNotFoundException && e !is app.quinten.data.PermissionLostException) repo.markMissing(b.id)
        }
    }

    /** Locator to navigate to once the view is ready (consumed by the screen). */
    var pendingLocator: Locator? = null

    /** Last place the reader showed; restores the page when the screen is recreated. */
    @Volatile var lastLocator: Locator? = null
        private set

    /** Jumps the view must perform (e.g. back to the same text after Clean mode re-flows a chapter). */
    val jumpRequests = kotlinx.coroutines.flow.MutableSharedFlow<Locator>(extraBufferCapacity = 4)

    /** New clean options re-flow chapters and renumber blocks: keep the reader on the same text. */
    private fun applyClean() {
        val rc = controller ?: return
        val loc = rc.locatorOf(position.value)
        tts?.stop()
        rc.clean = cleanOptions()
        loc?.let { jumpRequests.tryEmit(it) }
    }

    fun resolvePending(rc: ReaderController): Pos? {
        val loc = pendingLocator ?: return null
        val ch = rc.chapter(loc.chapter) ?: run { rc.requestChapter(loc.chapter); return null }
        pendingLocator = null
        val (bi, off) = ch.positionOf(loc)
        return Pos(loc.chapter, bi, off)
    }

    fun cleanOptions() = prefs.value.cleanOptions(appPrefs.value.cleanModeDefault, previewRemovals.value)

    private fun pushAnnotations(list: List<HighlightEntity>) {
        controller?.setAnnotations(list.mapNotNull { h ->
            LocatorRange.decode(h.range)?.let { Annotation(h.id, it, h.color, !h.note.isNullOrBlank()) }
        })
    }

    // ---------------- position ----------------

    fun onPosition(pos: Pos, prog: Float) {
        position.value = pos
        progress.value = prog
        val rc = controller ?: return
        rc.chapter(pos.chapter)?.let { removals.value = it.removals }
        val loc = rc.locatorOf(pos) ?: return
        lastLocator = loc
        val id = bookId.value
        saveJob?.cancel()
        pendingSave = Triple(id, prog, loc.encode())
        saveJob = viewModelScope.launch {
            delay(600)
            pendingSave = null
            repo.savePosition(id, prog, loc.encode())
            if (prog >= 0.995f) book.value?.let { if (it.finishedAt == 0L) repo.setFinished(id, true) }
        }
    }

    fun locatorForProgress(p: Float): Locator? {
        val rc = controller ?: return null
        val (ch, frac) = rc.chapterForProgress(p)
        val chapter = rc.chapter(ch) ?: run { rc.requestChapter(ch); return Locator(ch, 0, 0) }
        val pos = rc.posInChapter(chapter, frac)
        return rc.locatorOf(pos)
    }

    // ---------------- bookmarks & highlights ----------------

    fun toggleBookmark() {
        val rc = controller ?: return
        val pos = position.value
        val loc = rc.locatorOf(pos) ?: return
        viewModelScope.launch {
            val existing = bookmarks.value.firstOrNull { b -> Locator.decode(b.position)?.let { isOnCurrentPage(it) } == true }
            if (existing != null) repo.deleteBookmark(existing.id)
            else repo.addBookmark(BookmarkEntity(bookId = bookId.value, position = loc.encode(), progress = progress.value, snippet = snippetAt(pos)))
        }
    }

    var currentPageEnd: Pos? = null

    fun isOnCurrentPage(loc: Locator): Boolean {
        val rc = controller ?: return false
        val p = rc.posOf(loc) ?: return false
        val start = position.value
        // Scroll mode has no page end: a bookmark counts if it is within the same block as the reader.
        val end = currentPageEnd ?: return p.chapter == start.chapter && p.block == start.block
        return p >= start && p < end
    }

    private fun snippetAt(pos: Pos): String {
        val ch = controller?.chapter(pos.chapter) ?: return ""
        val b = ch.blocks.getOrNull(pos.block) as? TextBlock ?: return ch.title ?: ""
        return b.text.substring(pos.offset.coerceIn(0, b.text.length)).take(120)
    }

    fun deleteBookmark(id: Long) = viewModelScope.launch { repo.deleteBookmark(id) }

    fun addHighlight(start: Pos, end: Pos, text: String, color: Int, note: String? = null) {
        val rc = controller ?: return
        val a = rc.locatorOf(start) ?: return
        val b = rc.locatorOf(end, isEnd = true) ?: return
        viewModelScope.launch {
            repo.addHighlight(HighlightEntity(bookId = bookId.value, range = LocatorRange(a, b).encode(), progress = rc.progressOf(start), text = text, color = color, note = note))
        }
    }

    fun updateHighlight(h: HighlightEntity) = viewModelScope.launch { repo.updateHighlight(h) }
    fun deleteHighlight(id: Long) = viewModelScope.launch { repo.deleteHighlight(id) }

    // ---------------- clean mode & display modes ----------------

    private fun updatePrefs(reopen: Boolean = false, f: (BookPrefs) -> BookPrefs) {
        val id = bookId.value
        val newPrefs = f(prefs.value)
        prefs.value = newPrefs
        viewModelScope.launch {
            repo.savePrefs(id, newPrefs)
            if (reopen) {
                val b = book.value ?: return@launch
                val rc = controller
                val loc = rc?.locatorOf(position.value)
                // Fixed-layout positions are page based; carry the page across display modes.
                val page = loc?.let { l -> if (rc.document is app.quinten.formats.comic.ComicDocument) l.chapter else l.unit / 4096 }
                openDocument(b, null, page)
            } else applyClean()
        }
    }

    fun setCleanMode(on: Boolean) = updatePrefs { it.copy(cleanMode = on) }
    /** Unlocks a protected PDF; the password is remembered for this book. */
    fun submitPassword(password: String) {
        val id = bookId.value
        val newPrefs = prefs.value.copy(pdfPassword = password)
        prefs.value = newPrefs
        viewModelScope.launch {
            repo.savePrefs(id, newPrefs)
            val b = book.value ?: return@launch
            // Open at the saved reading position, as a normal open would.
            openDocument(b, b.position?.let { Locator.decode(it) })
        }
    }
    fun setPreview(on: Boolean) { previewRemovals.value = on; applyClean() }
    fun restoreRemoval(id: String) = updatePrefs { it.copy(restoredRemovals = it.restoredRemovals + id) }
    fun reapplyRemoval(id: String) = updatePrefs { it.copy(restoredRemovals = it.restoredRemovals - id) }
    fun setCategory(cat: RemovalCategory, enabled: Boolean) = updatePrefs {
        it.copy(disabledCategories = if (enabled) it.disabledCategories - cat.name else it.disabledCategories + cat.name)
    }
    fun resetRemovals() = updatePrefs { it.copy(restoredRemovals = emptySet(), disabledCategories = emptySet()) }
    fun setOriginalPages(on: Boolean) = updatePrefs(reopen = true) { it.copy(originalPages = on) }
    fun setComicTextMode(on: Boolean) = updatePrefs(reopen = true) { it.copy(comicTextMode = on) }
    fun setOcrMode(mode: String) {
        book.value?.let { c.documents.clearOcrCache(it.id) }
        updatePrefs(reopen = true) { it.copy(ocrMode = mode) }
    }

    fun updateSettings(f: (ReaderSettings) -> ReaderSettings) = viewModelScope.launch { c.settings.updateReader(f) }
    fun updateApp(f: (AppPrefs) -> AppPrefs) = viewModelScope.launch { c.settings.updateApp(f) }

    // ---------------- read-aloud voices ----------------

    /** Kokoro when chosen and installed, otherwise the phone's own voices. */
    private fun speechBackend(ctx: Application): app.quinten.tts.SpeechBackend =
        if (appPrefs.value.ttsEngine == "kokoro" && app.quinten.tts.KokoroModels.isInstalled(ctx))
            app.quinten.tts.KokoroSpeech(ctx) { appPrefs.value.kokoroVoice }
        else app.quinten.tts.SystemSpeech(ctx)

    fun setTtsEngine(engine: String) {
        updateApp { it.copy(ttsEngine = engine) }
        val ctx = getApplication<Application>()
        val b = if (engine == "kokoro" && app.quinten.tts.KokoroModels.isInstalled(ctx)) app.quinten.tts.KokoroSpeech(ctx) { appPrefs.value.kokoroVoice }
        else app.quinten.tts.SystemSpeech(ctx)
        tts?.setBackend(b)
    }

    fun downloadKokoro() {
        val ctx = getApplication<Application>()
        app.quinten.tts.KokoroModels.startDownload(ctx) { if (!cleared) setTtsEngine("kokoro") }
    }

    @Volatile private var cleared = false

    fun importKokoro(uri: android.net.Uri, onResult: (String?) -> Unit) = viewModelScope.launch {
        val ctx = getApplication<Application>()
        val err = app.quinten.tts.KokoroModels.importZip(ctx, uri)
        if (err == null) setTtsEngine("kokoro")
        onResult(err)
    }

    // ---------------- search ----------------

    fun search(query: String) {
        searchJob?.cancel()
        val rc = controller ?: return
        val q = query.trim()
        if (q.length < 2) { searchResults.value = emptyList(); return }
        searching.value = true
        searchResults.value = emptyList()
        val gen = ++searchGeneration
        searchJob = viewModelScope.launch(Dispatchers.Default) {
            val out = ArrayList<SearchHit>()
            for (ci in 0 until rc.chapterCount) {
                // A newer search replaces this one: stop at once and never publish stale results.
                ensureActive()
                val ch = try { rc.loadChapterBlocking(ci) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { continue }
                ensureActive()
                val chapterProgress = rc.progressOf(Pos(ci, 0, 0))
                for ((bi, b) in ch.blocks.withIndex()) {
                    val tb = b as? TextBlock ?: continue
                    var idx = tb.text.indexOf(q, ignoreCase = true)
                    while (idx >= 0 && out.size < 1000) {
                        val s = (idx - 40).coerceAtLeast(0); val e = (idx + q.length + 60).coerceAtMost(tb.text.length)
                        val snippet = (if (s > 0) "…" else "") + tb.text.substring(s, e).replace('\n', ' ') + (if (e < tb.text.length) "…" else "")
                        out += SearchHit(ch.locatorAt(bi, idx), q.length, snippet, ch.title ?: rc.document.chapterTitle(ci), chapterProgress)
                        idx = tb.text.indexOf(q, idx + q.length, ignoreCase = true)
                    }
                }
                if (gen != searchGeneration) return@launch
                searchResults.value = ArrayList(out)
                if (out.size >= 1000) break
            }
            if (gen == searchGeneration) searching.value = false
        }
    }

    // ---------------- footnotes ----------------

    /** Text of a footnote target (the anchored block and its continuation). */
    suspend fun footnoteText(target: String): String? = withContext(Dispatchers.Default) {
        val rc = controller ?: return@withContext null
        val loc = runCatching { rc.document.resolveLink(target) }.getOrNull() ?: return@withContext null
        val ch = runCatching { rc.loadChapterBlocking(loc.chapter) }.getOrNull() ?: return@withContext null
        val (start, _) = ch.positionOf(loc)
        val sb = StringBuilder()
        for (i in start until minOf(ch.blocks.size, start + 6)) {
            val b = ch.blocks[i]
            if (i > start && b.anchors.isNotEmpty()) break
            if (b is TextBlock) {
                if (i > start && b.kind == BlockKind.HEADING) break
                if (sb.isNotEmpty()) sb.append("\n\n")
                sb.append(b.text)
            }
        }
        sb.toString().ifBlank { null }
    }

    fun locatorOfLink(target: String): Locator? = runCatching { controller?.document?.resolveLink(target) }.getOrNull()

    // ---------------- lifecycle ----------------

    fun finishSession() {
        val id = session
        val prog = progress.value
        if (id >= 0) c.appScope.launch { repo.endSession(id, prog) }
    }

    override fun onCleared() {
        cleared = true
        // The last page turn may still be waiting for its debounced save: write it now.
        pendingSave?.let { (id, prog, loc) -> c.appScope.launch { repo.savePosition(id, prog, loc) } }
        finishSession()
        tts?.shutdown()
        if (TtsController.active === tts) TtsController.active = null
        controller?.close()
        opened?.close()
        super.onCleared()
    }
}
