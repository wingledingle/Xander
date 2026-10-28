package app.quinten.render

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.TextPaint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import app.quinten.model.ImageBlock
import app.quinten.model.LinkSpan
import app.quinten.model.RemovedSpan
import app.quinten.model.TextBlock
import kotlin.math.abs

/** Callbacks from the reading surface to the screen that hosts it. */
interface ReaderListener {
    fun onPositionChanged(pos: Pos, progress: Float) {}
    fun onCenterTap() {}
    fun onLink(target: String, footnote: Boolean, from: Pos) {}
    fun onImage(resource: String) {}
    fun onSelection(start: Pos, end: Pos, text: String, anchor: RectF) {}
    fun onSelectionCleared() {}
    fun onRemovalTap(removalId: String) {}
    fun onEndOfBook() {}
    /** Auto-scroll started, paused by a touch, or stopped. */
    fun onAutoScrollChanged(running: Boolean) {}
}

/**
 * Custom reading surface. Draws reflowed text and images with the current [ReaderSettings],
 * in paginated mode (tap/swipe, with a sliding page turn) or continuous scroll mode.
 */
class ReaderView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var controller: ReaderController? = null
        set(value) {
            field = value
            value?.onContentChanged = { onContentChanged() }
            // A view that already has its size gets no size change for a new controller (book reopened): give it
            // the page size now, or it keeps its 1x1 placeholder and lays out one letter per line and per page.
            if (width > 0 && height > 0) { updateViewport(); page = null; nextPage = null; prevPage = null }
            requestLayout(); invalidate()
        }
    var listener: ReaderListener? = null

    private val density = resources.displayMetrics.density
    private var page: Page? = null
    private var nextPage: Page? = null
    private var prevPage: Page? = null
    private var pendingStart: Pos = Pos(0, 0, 0)

    // scroll mode state: anchor block and pixel offset into it
    private var scrollAnchor = Pos(0, 0, 0)
    private var scrollOffset = 0
    private val scroller = OverScroller(context)
    private var stoppedFling = false
    private var lastFlingY = 0

    // page-turn drag animation
    private var dragDx = 0f

    // ---- pinch zoom: content is drawn scaled by [zoom] and shifted by [panX]/[panY] ----
    var zoom = 1f
        private set
    private var panX = 0f
    private var panY = 0f
    /** Zoom the pictures were last decoded for; raised when a pinch settles so zoomed comics stay sharp. */
    private var renderZoom = 1f
    private val zoomed get() = zoom > 1.01f
    private val toContent = android.graphics.Matrix()
    private var rawX = 0f
    private var pinched = false

    private val scaleDetector = android.view.ScaleGestureDetector(context, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: android.view.ScaleGestureDetector): Boolean {
            if (selecting) return false
            animator?.cancel(); dragDx = 0f
            if (!scroller.isFinished) scroller.forceFinished(true)
            return true
        }
        override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
            zoomBy(d.scaleFactor, d.focusX, d.focusY)
            return true
        }
        override fun onScaleEnd(d: android.view.ScaleGestureDetector) {
            if (zoom < 1.05f) resetZoom() else { renderZoom = zoom; invalidate() }
        }
    })

    /** Zooms by [factor] keeping the screen point ([fx], [fy]) still. */
    fun zoomBy(factor: Float, fx: Float, fy: Float) {
        val nz = (zoom * factor).coerceIn(1f, MAX_ZOOM)
        val f = nz / zoom
        panX = fx - (fx - panX) * f
        panY = fy - (fy - panY) * f
        zoom = nz
        clampPan()
        invalidate()
    }

    /** Back to normal size (page turns, jumps, layout changes). */
    fun resetZoom() {
        if (zoom == 1f && panX == 0f && panY == 0f) return
        zoom = 1f; panX = 0f; panY = 0f; renderZoom = 1f
        controller?.releaseZoomedBitmap()
        invalidate()
    }

    private fun clampPan() {
        panX = panX.coerceIn(width - width * zoom, 0f)
        if (settings.paginated) panY = panY.coerceIn(height - height * zoom, 0f)
        else if (panY != 0f) {
            // Continuous mode: vertical movement scrolls the book instead of panning past its end.
            val d = (-panY / zoom).toInt()
            panY = 0f
            if (d != 0) scrollBy(d)
        }
    }
    private var animator: ValueAnimator? = null

    // selection
    private var selecting = false
    private var selAnchor: Pos? = null

    private class Placed(val chapter: Int, val block: Int, val rowStart: Int, val rowEnd: Int, val drawTop: Float, val clipTop: Float, val clipBottom: Float, val layout: BlockLayout)
    private val placed = ArrayList<Placed>()

    private val statusPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val messagePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val placeholderPaint = Paint()

    private val settings get() = controller?.settings ?: ReaderSettings()
    private val marginH get() = (settings.marginHorizontalDp * density).toInt()
    private val marginV get() = (settings.marginVerticalDp * density).toInt()
    // The page number / % line is a transparent overlay in the bottom margin, not a band of its own:
    // pages only give up the height the margin is missing for it; scrolled text runs underneath.
    private val statusHeight get() =
        if (settings.showStatusBar && settings.paginated) ((18 * density).toInt() - marginV).coerceAtLeast(0) else 0

    /** Position just after the last character on screen (paginated mode). */
    val currentPageEnd: Pos? get() = if (settings.paginated) ensurePage()?.end else null

    val currentPosition: Pos get() = if (settings.paginated) (page?.start ?: pendingStart) else firstVisible()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    // ---------------- navigation ----------------

    // Navigation waiting for a chapter to load (table of contents, progress slider, links).
    private var pendingNav: ((ReaderController) -> Pos?)? = null
    private var pendingChapter: (ReaderController) -> Int = { -1 }

    /**
     * Navigates as soon as the target can be resolved; never times out. [chapterOf] names the chapter
     * being waited for, so a chapter that fails to load ends the wait and shows its error.
     */
    fun navigate(chapterOf: (ReaderController) -> Int = { -1 }, resolver: (ReaderController) -> Pos?) {
        pendingNav = resolver
        pendingChapter = chapterOf
        if (!tryNavigate()) invalidate()
    }

    fun navigateTo(loc: app.quinten.model.Locator) = navigate({ loc.chapter }) { c ->
        c.chapter(loc.chapter)?.let { ch -> val (b, o) = ch.positionOf(loc); Pos(loc.chapter, b, o) }
    }

    /** Jumps to an overall reading progress (0..1), exact within the target chapter. */
    fun navigateToProgress(progress: Float) = navigate({ c -> c.chapterForProgress(progress).first }) { c ->
        val (ch, frac) = c.chapterForProgress(progress)
        c.chapter(ch)?.let { c.posInChapter(it, frac) }
    }

    val isNavigating get() = pendingNav != null

    private fun tryNavigate(): Boolean {
        val c = controller ?: return false
        val r = pendingNav ?: return false
        if (width == 0) return false
        val p = r(c)
        if (p == null) {
            // The chapter could not be loaded: stop waiting and show its error instead of "Loading…" forever.
            val ch = pendingChapter(c)
            if (ch >= 0 && c.chapterError(ch) != null) { pendingNav = null; goTo(Pos(ch, 0, 0)); return true }
            return false
        }
        pendingNav = null
        goTo(p)
        return true
    }

    /** Whether [pos] is visible now (paged: on this page; scrolling: inside the drawn area). */
    fun isOnScreen(pos: Pos): Boolean {
        if (settings.paginated) {
            val start = currentPosition
            val end = currentPageEnd
            return !(pos < start || (end != null && pos >= end))
        }
        val p = placed.firstOrNull { it.chapter == pos.chapter && it.block == pos.block } ?: return false
        val l = p.layout as? BlockLayout.Text ?: return p.clipTop >= 0 && p.clipBottom <= height
        val row = l.rowForOffset(pos.offset)
        val top = p.drawTop + l.rowTop(row)
        val bottom = p.drawTop + l.rowBottom(row)
        return top >= 0 && bottom <= height - statusHeight
    }

    // ---------------- auto-scroll ----------------

    /** Speed 1..20: in scroll mode how fast the text glides up, in page mode how often pages turn. */
    var autoScrollSpeed = 6
        set(v) { field = v.coerceIn(1, 20); if (autoScrolling) restartPageTimer() }
    var autoScrolling = false
        private set
    private var lastFrame = 0L
    private var carry = 0f
    private val autoHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val glide = object : Runnable {
        override fun run() {
            if (!autoScrolling || settings.paginated) return
            val now = android.os.SystemClock.uptimeMillis()
            val dt = if (lastFrame == 0L) 16 else (now - lastFrame).coerceIn(1, 100)
            lastFrame = now
            // Speed 1 ~ 3 dp/s, speed 20 ~ 60 dp/s.
            carry += autoScrollSpeed * 3f * density * dt / 1000f
            val step = carry.toInt()
            if (step > 0) {
                carry -= step
                val before = firstVisible()
                scrollBy(step)
                if (firstVisible() == before && step > 2 && isAtEnd()) { stopAutoScroll(); listener?.onEndOfBook(); return }
            }
            autoHandler.postDelayed(this, 16)
        }
    }
    private val pageTurn = Runnable {
        if (!autoScrolling || !settings.paginated) return@Runnable
        if (ensurePage()?.end == null) { stopAutoScroll(); listener?.onEndOfBook(); return@Runnable }
        nextPageAction(animated = false)
        restartPageTimer()
    }
    private fun pageSeconds() = (63 - autoScrollSpeed * 3).coerceAtLeast(4)
    private fun restartPageTimer() {
        autoHandler.removeCallbacks(pageTurn)
        if (autoScrolling && settings.paginated) autoHandler.postDelayed(pageTurn, pageSeconds() * 1000L)
    }
    private fun isAtEnd(): Boolean {
        val c = controller ?: return true
        return c.progressOf(firstVisible()) > 0.999f
    }

    fun startAutoScroll() {
        autoScrolling = true
        lastFrame = 0L; carry = 0f
        if (settings.paginated) restartPageTimer() else autoHandler.post(glide)
        listener?.onAutoScrollChanged(true)
    }

    override fun onDetachedFromWindow() {
        stopAutoScroll()
        super.onDetachedFromWindow()
    }

    fun stopAutoScroll() {
        if (!autoScrolling) return
        autoScrolling = false
        autoHandler.removeCallbacks(pageTurn); autoHandler.removeCallbacks(glide)
        listener?.onAutoScrollChanged(false)
    }

    /** An explicit jump by the reader (e.g. back from a link): replaces any jump still loading. */
    fun jumpTo(pos: Pos) {
        pendingNav = null
        goTo(pos)
    }

    fun goTo(pos: Pos) {
        clearSelection()
        resetZoom()
        pendingStart = pos
        scrollAnchor = Pos(pos.chapter, pos.block, 0)
        scrollOffset = 0
        val c = controller
        if (c != null && !settings.paginated) {
            c.layout(pos.chapter, pos.block)?.let { l -> scrollOffset = rowTopOf(l, pos.offset) }
        }
        page = null; nextPage = null; prevPage = null
        invalidate()
        notifyPosition()
    }

    private fun rowTopOf(l: BlockLayout, offset: Int): Int = if (l is BlockLayout.Text) l.rowTop(l.rowForOffset(offset)) else 0

    fun nextPageAction(animated: Boolean = true) {
        if (pendingNav != null) return
        if (settings.paginated) resetZoom()
        if (!settings.paginated) { scrollBy(height - marginV * 2 - statusHeight - (settings.fontSizeSp * density).toInt()); return }
        val p = ensurePage() ?: return
        val end = p.end ?: run { listener?.onEndOfBook(); return }
        if (animated) animateTurn(forward = true) else { pendingStart = end; page = null; invalidate(); notifyPosition() }
    }

    fun previousPageAction(animated: Boolean = true) {
        if (pendingNav != null) return
        if (settings.paginated) resetZoom()
        if (!settings.paginated) { scrollBy(-(height - marginV * 2 - statusHeight - (settings.fontSizeSp * density).toInt())); return }
        val c = controller ?: return
        val p = ensurePage() ?: return
        val prev = c.previousPageStart(p.start) ?: return
        if (animated) animateTurn(forward = false) else { pendingStart = prev; page = null; invalidate(); notifyPosition() }
    }

    private fun animateTurn(forward: Boolean) {
        animator?.cancel()
        val target = if (forward) -width.toFloat() else width.toFloat()
        animator = ValueAnimator.ofFloat(dragDx, target).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { dragDx = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    commitTurn(forward)
                }
            })
            start()
        }
    }

    private fun commitTurn(forward: Boolean) {
        val c = controller ?: return
        val p = page ?: return
        val newStart = if (forward) p.end else c.previousPageStart(p.start)
        dragDx = 0f
        if (newStart != null) {
            pendingStart = newStart
            page = if (forward) nextPage else prevPage
            nextPage = null; prevPage = null
            if (page?.start != newStart) page = null
        }
        invalidate()
        notifyPosition()
    }

    private fun notifyPosition() {
        val c = controller ?: return
        // While a jump is still loading, the old place is not the reader's position: don't save it.
        if (pendingNav != null) return
        val pos = currentPosition
        listener?.onPositionChanged(pos, c.progressOf(pos))
        c.prefetchAround(pos.chapter)
    }

    private fun onContentChanged() {
        if (tryNavigate()) return
        page = null; nextPage = null; prevPage = null
        invalidate()
    }

    /** Applies new settings, keeping the position read under the old ones (e.g. paged -> scroll). */
    fun applySettings(s: ReaderSettings) {
        val c = controller ?: return
        if (c.settings == s) return
        val pos = currentPosition
        c.settings = s
        relayout(pos)
    }

    /** Re-paginates after settings or viewport changes, keeping the reading position. */
    fun relayout(pos: Pos = currentPosition) {
        updateViewport()
        goTo(pos)
    }

    private fun updateViewport() {
        controller?.setViewport(width - marginH * 2, height - marginV * 2 - statusHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val pos = currentPosition
        updateViewport()
        page = null; nextPage = null; prevPage = null
        if (tryNavigate()) return
        if (!settings.paginated) goTo(pos)
    }

    private fun ensurePage(): Page? {
        val c = controller ?: return null
        // Safety net: never paginate with the 1x1 placeholder size while this view has a real one.
        if (width > marginH * 2 && height > marginV * 2 && (c.contentWidth <= 1 || c.contentHeight <= 1)) { updateViewport(); page = null }
        page?.let { if (!it.isLoading) return it }
        val start = c.normalize(pendingStart) ?: pendingStart
        val p = c.pageFrom(start)
        page = p
        if (p.isLoading) c.requestChapter(p.loadingChapter)
        return p
    }

    // ---------------- drawing ----------------

    override fun onDraw(canvas: Canvas) {
        val c = controller
        val s = settings
        canvas.drawColor(s.theme.background)
        if (c == null) return
        placed.clear()
        if (pendingNav != null && !tryNavigate()) { drawMessage(canvas, "Loading…"); return }
        val zoomedNow = zoomed
        if (zoomedNow) { canvas.save(); canvas.translate(panX, panY); canvas.scale(zoom, zoom) }
        if (s.paginated) {
            val p = ensurePage() ?: return
            if (dragDx != 0f) {
                // Neighbouring page slides in next to the current one.
                val neighbour = if (dragDx < 0) (nextPage ?: p.end?.let { c.pageFrom(it) }?.also { nextPage = it })
                else (prevPage ?: c.previousPageStart(p.start)?.let { c.pageFrom(it, p.start) }?.also { prevPage = it })
                canvas.save()
                canvas.translate(dragDx, 0f)
                drawPage(canvas, p, true)
                canvas.restore()
                if (neighbour != null) {
                    canvas.save()
                    canvas.translate(dragDx + (if (dragDx < 0) width else -width), 0f)
                    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply { color = s.theme.background })
                    drawPage(canvas, neighbour, false)
                    canvas.restore()
                }
            } else drawPage(canvas, p, true)
            if (zoomedNow) canvas.restore()
            drawStatus(canvas, p.start)
        } else {
            drawScroll(canvas)
            if (zoomedNow) canvas.restore()
            drawStatus(canvas, firstVisible())
        }
    }

    private fun drawPage(canvas: Canvas, p: Page, record: Boolean) {
        val c = controller ?: return
        if (p.isLoading) { drawMessage(canvas, c.chapterError(p.loadingChapter) ?: loadingText(p.loadingChapter)); return }
        if (p.rows.isEmpty()) { drawMessage(canvas, c.chapterError(p.start.chapter) ?: ""); return }
        val top = marginV.toFloat()
        for (r in p.rows) {
            val l = c.layout(r.chapter, r.block) ?: continue
            val rowTopInBlock = l.rowTop(r.rowStart) + r.trimTop
            val drawTop = top + r.y - rowTopInBlock
            val clipTop = top + r.y
            val clipBottom = top + r.y + r.height
            drawBlock(canvas, l, drawTop, clipTop, clipBottom)
            if (record) placed += Placed(r.chapter, r.block, r.rowStart, r.rowEnd, drawTop, clipTop, clipBottom, l)
        }
    }

    private fun loadingText(chapter: Int): String {
        val doc = controller?.document ?: return "Loading…"
        return if (doc.originalPageCount > 0) "Preparing pages… (text recognition may take a moment)" else "Loading…"
    }

    private fun drawBlock(canvas: Canvas, l: BlockLayout, drawTop: Float, clipTop: Float, clipBottom: Float) {
        val left = marginH.toFloat()
        canvas.save()
        canvas.clipRect(0f, clipTop, width.toFloat(), clipBottom)
        when (l) {
            is BlockLayout.Text -> {
                canvas.translate(left + l.left, drawTop + l.padTop)
                l.layout.draw(canvas)
            }
            is BlockLayout.Image -> {
                val rect = RectF(left + l.left, drawTop + l.padTop, left + l.left + l.width, drawTop + l.padTop + l.imageHeight)
                val c0 = controller
                val sharp = if (renderZoom > 1.2f && c0 != null) c0.bitmap(l.block.resource, (l.width * renderZoom).toInt().coerceAtMost(MAX_BITMAP_WIDTH)) else null
                val bmp = sharp ?: controller?.bitmap(l.block.resource, l.width)
                if (bmp != null) {
                    if (settings.theme.isDark && !l.block.fullPage) imagePaint.alpha = 225 else imagePaint.alpha = 255
                    canvas.drawBitmap(bmp, null, rect, imagePaint)
                } else {
                    placeholderPaint.color = if (settings.theme.isDark) 0x22FFFFFF else 0x11000000
                    canvas.drawRect(rect, placeholderPaint)
                }
            }
            is BlockLayout.Rule -> {
                val p = controller?.paint() ?: TextPaint()
                p.color = settings.theme.secondaryText
                val text = "*   *   *"
                canvas.drawText(text, left + (width - marginH * 2 - p.measureText(text)) / 2, drawTop + l.height * 0.6f, p)
            }
        }
        canvas.restore()
    }

    private fun drawMessage(canvas: Canvas, text: String) {
        messagePaint.color = settings.theme.secondaryText
        messagePaint.textSize = 16 * resources.displayMetrics.scaledDensity
        messagePaint.textAlign = Paint.Align.CENTER
        canvas.drawText(text, width / 2f, height / 2f, messagePaint)
    }

    private fun drawStatus(canvas: Canvas, pos: Pos) {
        if (!settings.showStatusBar) return
        val c = controller ?: return
        statusPaint.color = settings.theme.secondaryText
        statusPaint.textSize = 12 * resources.displayMetrics.scaledDensity
        // Centred in the bottom margin; over scrolling text a soft halo in the page colour keeps it legible.
        val band = maxOf(marginV + statusHeight, (18 * density).toInt())
        val y = height - band / 2f - (statusPaint.ascent() + statusPaint.descent()) / 2f
        if (!settings.paginated) statusPaint.setShadowLayer(3 * density, 0f, 0f, settings.theme.background) else statusPaint.clearShadowLayer()
        val title = c.document.chapterTitle(pos.chapter) ?: c.document.metadata.title ?: ""
        val progress = c.progressOf(pos)
        val total = c.estimatedPages()
        val right = "${(progress * total).toInt() + 1} / $total  ·  ${(progress * 100).toInt()}%"
        val rightW = statusPaint.measureText(right)
        val maxTitle = width - marginH * 2 - rightW - 16 * density
        val t = android.text.TextUtils.ellipsize(title, statusPaint, maxTitle.coerceAtLeast(0f), android.text.TextUtils.TruncateAt.END).toString()
        statusPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(t, marginH.toFloat(), y, statusPaint)
        statusPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(right, (width - marginH).toFloat(), y, statusPaint)
    }

    // ---------------- scroll mode ----------------

    private fun drawScroll(canvas: Canvas) {
        val c = controller ?: return
        normalizeScroll()
        var ch = scrollAnchor.chapter
        var b = scrollAnchor.block
        var y = marginV.toFloat() - scrollOffset
        val bottom = (height - statusHeight).toFloat()
        while (y < bottom) {
            val chapter = c.chapter(ch)
            if (chapter == null) {
                if (ch >= c.chapterCount) break
                messagePaint.color = settings.theme.secondaryText
                messagePaint.textAlign = Paint.Align.CENTER
                messagePaint.textSize = 16 * resources.displayMetrics.scaledDensity
                canvas.drawText(c.chapterError(ch) ?: loadingText(ch), width / 2f, maxOf(y, 0f) + 60 * density, messagePaint)
                break
            }
            if (b >= chapter.blocks.size) { ch++; b = 0; continue }
            val l = c.layout(ch, b) ?: break
            drawBlock(canvas, l, y, maxOf(y, marginV.toFloat() * 0), minOf(y + l.height, bottom))
            placed += Placed(ch, b, 0, l.rowCount, y, y, y + l.height, l)
            y += l.height
            b++
        }
    }

    /** Moves the scroll anchor so that the offset lies inside the anchor block. */
    private fun normalizeScroll() {
        val c = controller ?: return
        var guard = 0
        while (scrollOffset < 0 && guard++ < 10_000) {
            // move to previous block
            var ch = scrollAnchor.chapter; var b = scrollAnchor.block - 1
            if (b < 0) {
                if (ch == 0) { scrollOffset = 0; break }
                ch--
                val prev = c.chapter(ch) ?: run { scrollOffset = 0; return }
                b = prev.blocks.size - 1
                if (b < 0) { scrollAnchor = Pos(ch, 0, 0); continue }
            }
            val l = c.layout(ch, b) ?: run { scrollOffset = 0; return }
            scrollAnchor = Pos(ch, b, 0)
            scrollOffset += l.height
        }
        guard = 0
        while (guard++ < 10_000) {
            val l = c.layout(scrollAnchor.chapter, scrollAnchor.block)
            if (l == null) {
                // Chapter still loading: hold here instead of accumulating a jump for when it arrives.
                val ch = c.chapter(scrollAnchor.chapter) ?: run { scrollOffset = 0; scroller.forceFinished(true); return }
                if (scrollAnchor.block >= ch.blocks.size) {
                    if (scrollAnchor.chapter + 1 >= c.chapterCount) { scrollAnchor = Pos(scrollAnchor.chapter, (ch.blocks.size - 1).coerceAtLeast(0), 0); scrollOffset = 0; return }
                    scrollAnchor = Pos(scrollAnchor.chapter + 1, 0, 0); continue
                }
                return
            }
            if (scrollOffset < l.height) return
            scrollOffset -= l.height
            scrollAnchor = Pos(scrollAnchor.chapter, scrollAnchor.block + 1, 0)
        }
    }

    override fun scrollBy(x: Int, y: Int) = scrollBy(y)

    fun scrollBy(dy: Int) {
        if (pendingNav != null) return
        scrollOffset += dy
        normalizeScroll()
        invalidate()
        notifyPosition()
    }

    private fun firstVisible(): Pos {
        val c = controller ?: return scrollAnchor
        val l = c.layout(scrollAnchor.chapter, scrollAnchor.block) ?: return scrollAnchor
        if (l is BlockLayout.Text) {
            var row = 0
            while (row < l.rowCount - 1 && l.rowBottom(row) <= scrollOffset) row++
            return Pos(scrollAnchor.chapter, scrollAnchor.block, l.rowStart(row))
        }
        return scrollAnchor
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            val y = scroller.currY
            scrollBy(y - lastFlingY)
            lastFlingY = y
            postInvalidateOnAnimation()
        }
    }

    // ---------------- hit testing ----------------

    /** Finds the character position under a point. */
    fun hit(x: Float, y: Float): Pair<Pos, BlockLayout>? {
        val p = placed.firstOrNull { y >= it.clipTop && y < it.clipBottom } ?: placed.minByOrNull { minOf(abs(it.clipTop - y), abs(it.clipBottom - y)) } ?: return null
        val l = p.layout
        return when (l) {
            is BlockLayout.Text -> {
                val ly = (y - p.drawTop - l.padTop).toInt()
                val line = l.layout.getLineForVertical(ly).coerceIn(p.rowStart, (p.rowEnd - 1).coerceAtLeast(p.rowStart))
                val off = l.layout.getOffsetForHorizontal(line, x - marginH - l.left)
                Pos(p.chapter, p.block, off) to l
            }
            else -> Pos(p.chapter, p.block, 0) to l
        }
    }

    private fun wordAt(l: BlockLayout.Text, offset: Int): IntRange {
        val t = l.block.text
        if (t.isEmpty()) return 0 until 0
        var s = offset.coerceIn(0, t.length - 1)
        var e = s
        while (s > 0 && t[s - 1].isLetterOrDigit()) s--
        while (e < t.length && t[e].isLetterOrDigit()) e++
        if (e == s) e = (s + 1).coerceAtMost(t.length)
        return s until e
    }

    fun clearSelection() {
        if (controller?.selection != null) {
            controller?.selection = null
            listener?.onSelectionCleared()
            page = null
            invalidate()
        }
        selecting = false; selAnchor = null
    }

    private fun selectionText(a: Pos, b: Pos): String {
        val c = controller ?: return ""
        val sb = StringBuilder()
        for (ch in a.chapter..b.chapter) {
            val chapter = c.chapter(ch) ?: continue
            val from = if (ch == a.chapter) a.block else 0
            val to = if (ch == b.chapter) b.block else chapter.blocks.size - 1
            for (bi in from..to.coerceAtMost(chapter.blocks.size - 1)) {
                val blk = chapter.blocks[bi] as? TextBlock ?: continue
                val s = if (ch == a.chapter && bi == a.block) a.offset else 0
                val e = if (ch == b.chapter && bi == b.block) b.offset else blk.text.length
                if (e > s) { if (sb.isNotEmpty()) sb.append('\n'); sb.append(blk.text, s.coerceIn(0, blk.text.length), e.coerceIn(0, blk.text.length)) }
            }
        }
        return sb.toString()
    }

    private fun updateSelection(to: Pos) {
        val c = controller ?: return
        val anchor = selAnchor ?: return
        val (a, b) = if (to < anchor) to to anchor else anchor to to
        c.selection = a to b
        page = null
        invalidate()
    }

    private fun finishSelection() {
        val c = controller ?: return
        val (a, b) = c.selection ?: return
        val anchorPlaced = placed.firstOrNull { it.chapter == b.chapter && it.block == b.block } ?: placed.lastOrNull()
        val rect = RectF(marginH.toFloat(), anchorPlaced?.clipTop ?: 0f, (width - marginH).toFloat(), anchorPlaced?.clipBottom ?: 0f)
        listener?.onSelection(a, b, selectionText(a, b), rect)
    }

    // ---------------- touch ----------------

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            stoppedFling = !scroller.isFinished
            if (stoppedFling) scroller.forceFinished(true)
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (controller?.selection != null) { clearSelection(); return true }
            // A tap that only stopped a scroll fling should not also jump a screen.
            if (stoppedFling) { stoppedFling = false; return true }
            val h = hit(e.x, e.y)
            if (h != null) {
                val (pos, l) = h
                if (l is BlockLayout.Text) {
                    val span = l.block.spans.firstOrNull { it is LinkSpan && pos.offset >= it.start && pos.offset < it.end } as? LinkSpan
                    if (span != null) { listener?.onLink(span.target, span.footnote, pos); return true }
                    val removed = l.block.spans.firstOrNull { it is RemovedSpan && pos.offset >= it.start && pos.offset < it.end } as? RemovedSpan
                    val rid = removed?.removalId ?: l.block.removalId
                    if (rid != null) { listener?.onRemovalTap(rid); return true }
                }
                // Pictures and comic pages open full-screen with a long press; a tap in the middle
                // always brings up the reader menu.
            }
            // Tap zones are on the screen, also when zoomed in.
            when {
                rawX < width * 0.3f -> previousPageAction()
                rawX > width * 0.7f -> nextPageAction()
                else -> listener?.onCenterTap()
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val (pos, l) = hit(e.x, e.y) ?: return
            if (l !is BlockLayout.Text) { (l as? BlockLayout.Image)?.let { listener?.onImage(it.block.resource) }; return }
            val w = wordAt(l, pos.offset)
            selAnchor = Pos(pos.chapter, pos.block, w.first)
            selecting = true
            controller?.selection = Pos(pos.chapter, pos.block, w.first) to Pos(pos.chapter, pos.block, w.last + 1)
            page = null
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            invalidate()
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (selecting) return true
            if (zoomed) {
                // Distances arrive in content units; pan in screen units.
                panX -= distanceX * zoom
                panY -= distanceY * zoom
                clampPan(); invalidate()
                return true
            }
            if (settings.paginated) {
                if (e1 != null) { dragDx = e2.x - e1.x; invalidate() }
            } else scrollBy(distanceY.toInt())
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (selecting) return true
            if (zoomed && settings.paginated) return true
            if (settings.paginated) {
                if (abs(velocityX) > abs(velocityY) && abs(velocityX) > 400) {
                    if (velocityX < 0) nextPageAction() else previousPageAction()
                    return true
                }
            } else {
                lastFlingY = 0
                scroller.fling(0, 0, 0, -velocityY.toInt(), 0, 0, Int.MIN_VALUE / 2, Int.MAX_VALUE / 2)
                postInvalidateOnAnimation()
            }
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(raw: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(raw)
        rawX = raw.x
        // After a pinch, the finger left on the screen must not turn the page or scroll: everything is
        // ignored until all fingers are up.
        if (pinched) {
            if (raw.actionMasked == MotionEvent.ACTION_UP || raw.actionMasked == MotionEvent.ACTION_CANCEL) { pinched = false; dragDx = 0f; invalidate() }
            return true
        }
        if (scaleDetector.isInProgress || raw.pointerCount > 1) {
            pinched = true
            if (raw.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                // Stop the first finger's pending long press / swipe.
                val cancel = MotionEvent.obtain(raw).apply { action = MotionEvent.ACTION_CANCEL }
                gestures.onTouchEvent(cancel); cancel.recycle()
                dragDx = 0f
            }
            return true
        }
        if (!zoomed) return handleTouch(raw)
        toContent.reset(); toContent.postTranslate(-panX, -panY); toContent.postScale(1f / zoom, 1f / zoom)
        val ev = MotionEvent.obtain(raw)
        ev.transform(toContent)
        try { return handleTouch(ev) } finally { ev.recycle() }
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        // Touching the page pauses auto-scroll (the controls bar resumes it).
        if (event.actionMasked == MotionEvent.ACTION_DOWN && autoScrolling) stopAutoScroll()
        if (selecting) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    hit(event.x, event.y)?.let { (pos, l) ->
                        val end = if (l is BlockLayout.Text) Pos(pos.chapter, pos.block, wordAt(l, pos.offset).last + 1) else pos
                        updateSelection(end)
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    selecting = false
                    finishSelection()
                    return true
                }
            }
        }
        val handled = gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (settings.paginated && dragDx != 0f && (animator == null || animator?.isRunning == false)) {
                when {
                    dragDx < -width * 0.25f && page?.end != null -> animateTurn(true)
                    dragDx > width * 0.25f && page?.start?.let { controller?.previousPageStart(it) } != null -> animateTurn(false)
                    else -> {
                        animator = ValueAnimator.ofFloat(dragDx, 0f).apply {
                            duration = 160
                            addUpdateListener { dragDx = it.animatedValue as Float; invalidate() }
                            start()
                        }
                    }
                }
            }
        }
        return handled || super.onTouchEvent(event)
    }

    /** Plain text currently on screen (accessibility, sharing a page, tests). */
    fun visibleText(): String {
        val c = controller ?: return ""
        val sb = StringBuilder()
        fun add(chapter: Int, block: Int, rowStart: Int, rowEnd: Int) {
            val l = c.layout(chapter, block) as? BlockLayout.Text ?: return
            val s = l.rowStart(rowStart.coerceAtMost(l.rowCount - 1))
            val e = if (rowEnd >= l.rowCount) l.block.text.length else l.rowStart(rowEnd)
            if (e <= s) return
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(l.block.text, s, e)
        }
        if (settings.paginated) {
            if (width == 0) return ""
            val p = ensurePage() ?: return ""
            for (r in p.rows) add(r.chapter, r.block, r.rowStart, r.rowEnd)
        } else for (p in placed) add(p.chapter, p.block, p.rowStart, p.rowEnd)
        return sb.toString()
    }

    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.text = visibleText()
        // TalkBack: two-finger swipes / scroll actions turn pages (or scroll by a screen).
        info.isScrollable = true
        info.isFocusable = true
        info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }

    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
        val forward = action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        val backward = action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        if (!forward && !backward) return super.performAccessibilityAction(action, arguments)
        if (forward) nextPageAction(animated = false) else previousPageAction(animated = false)
        // Read the new page out: the screen reader gets the text that is now visible.
        post {
            sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SCROLLED)
            val text = visibleText()
            if (text.isNotBlank()) announceForAccessibility(text.take(4000))
        }
        return true
    }

    @Suppress("unused")
    private val tmpRect = Rect()
    @Suppress("unused")
    private fun isImage(p: Pos) = controller?.chapter(p.chapter)?.blocks?.getOrNull(p.block) is ImageBlock

    companion object {
        const val MAX_ZOOM = 5f
        private const val MAX_BITMAP_WIDTH = 2400
    }
}
