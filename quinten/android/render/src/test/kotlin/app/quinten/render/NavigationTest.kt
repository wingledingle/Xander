package app.quinten.render

import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import app.quinten.formats.BookOpener
import app.quinten.model.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavigationTest {
    private val alice = File("../../core/formats/build/external-fixtures/alice.epub")
    private val sample = File("../../core/formats/src/test/resources/fixtures/sample.epub")

    private fun view(): ReaderView {
        val f = if (alice.isFile) alice else sample
        val c = ReaderController(BookOpener.open(f, BookFormat.EPUB), 2.6f, 2.6f)
        c.settings = ReaderSettings(fontSizeSp = 24f)
        val v = ReaderView(ApplicationProvider.getApplicationContext())
        v.controller = c
        v.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2200, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 1080, 2200)
        return v
    }

    private val frame = android.graphics.Bitmap.createBitmap(1080, 2200, android.graphics.Bitmap.Config.ARGB_8888)

    private fun pump(v: ReaderView, until: () -> Boolean) {
        val end = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            v.draw(android.graphics.Canvas(frame))
            if (until()) return
            Thread.sleep(5)
        }
        error("timeout")
    }

    @Test fun sliderJumpsToTheRequestedPlaceEvenBeforeChaptersLoad() {
        val v = view()
        val c = v.controller!!
        for (target in listOf(0.73f, 0.12f, 0.5f, 0.98f)) {
            v.navigateToProgress(target)
            pump(v) { !v.isNavigating }
            val got = c.progressOf(v.currentPosition)
            println("target $target -> $got")
            assertTrue("target $target got $got", kotlin.math.abs(got - target) < 0.01f)
            assertTrue(v.visibleText().isNotEmpty())
        }
    }

    @Test fun contentsJumpToFarChapter() {
        val v = view()
        val c = v.controller!!
        val doc = c.document
        val last = doc.toc.last()
        val loc = doc.resolveLink(app.quinten.model.LinkTarget(last.chapter, last.anchor).encode())!!
        v.navigateTo(loc)
        pump(v) { !v.isNavigating }
        assertEquals(last.chapter, v.currentPosition.chapter)
        pump(v) { v.visibleText().isNotEmpty() }
    }

    @Test fun switchingBetweenPagesAndScrollingKeepsThePlace() {
        val v = view()
        val c = v.controller!!
        v.navigateToProgress(0.6f)
        pump(v) { !v.isNavigating }
        v.nextPageAction(animated = false); v.nextPageAction(animated = false)
        pump(v) { v.visibleText().isNotEmpty() }
        val before = c.progressOf(v.currentPosition)
        v.applySettings(c.settings.copy(paginated = false))
        pump(v) { v.visibleText().isNotEmpty() }
        val scrolled = c.progressOf(v.currentPosition)
        assertTrue("$before -> $scrolled", kotlin.math.abs(scrolled - before) < 0.003f)
        v.scrollBy(900); v.scrollBy(900)
        val moved = c.progressOf(v.currentPosition)
        assertTrue("scrolling advances: $scrolled -> $moved", moved > scrolled)
        v.applySettings(c.settings.copy(paginated = true))
        pump(v) { v.visibleText().isNotEmpty() }
        val back = c.progressOf(v.currentPosition)
        assertTrue("$moved -> $back", kotlin.math.abs(back - moved) < 0.003f)
    }

    private fun touch(v: ReaderView, action: Int, x: Float, y: Float, t: Long) {
        val e = android.view.MotionEvent.obtain(0L, t, action, x, y, 0)
        v.dispatchTouchEvent(e); e.recycle()
    }

    @Test fun pinchZoomPansInsteadOfTurningAndResetsOnPageTurn() {
        val v = view()
        val c = v.controller!!
        v.navigateToProgress(0.3f)
        pump(v) { !v.isNavigating && v.visibleText().isNotEmpty() }
        val start = v.currentPosition
        val text = v.visibleText()
        v.zoomBy(2.5f, 540f, 1100f)
        assertEquals(2.5f, v.zoom, 0.001f)
        v.zoomBy(10f, 540f, 1100f)
        assertEquals(ReaderView.MAX_ZOOM, v.zoom, 0.001f)
        // A horizontal drag while zoomed pans; it must not turn the page.
        touch(v, android.view.MotionEvent.ACTION_DOWN, 900f, 1100f, 0)
        for (i in 1..10) touch(v, android.view.MotionEvent.ACTION_MOVE, 900f - i * 60f, 1100f, i * 16L)
        touch(v, android.view.MotionEvent.ACTION_UP, 300f, 1100f, 200)
        pump(v) { true }
        assertEquals(start, v.currentPosition)
        assertEquals(text, v.visibleText())
        // Page turn goes back to normal size.
        v.nextPageAction(animated = false)
        assertEquals(1f, v.zoom, 0.001f)
        assertTrue(c.progressOf(v.currentPosition) > c.progressOf(start))
    }

    @Test fun autoScrollMovesOnAndATouchPausesIt() {
        val v = view()
        val c = v.controller!!
        v.navigateToProgress(0.2f)
        pump(v) { !v.isNavigating && v.visibleText().isNotEmpty() }
        // Page mode: speed 20 turns a page every 4 seconds.
        val p0 = c.progressOf(v.currentPosition)
        v.autoScrollSpeed = 20
        v.startAutoScroll()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(9))
        val p1 = c.progressOf(v.currentPosition)
        assertTrue("pages turned: $p0 -> $p1", p1 > p0)
        touch(v, android.view.MotionEvent.ACTION_DOWN, 540f, 1100f, 0)
        touch(v, android.view.MotionEvent.ACTION_UP, 540f, 1100f, 50)
        assertTrue(!v.autoScrolling)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(9))
        assertEquals(p1, c.progressOf(v.currentPosition), 0.0001f)
        // Scroll mode: the text glides on.
        v.applySettings(c.settings.copy(paginated = false))
        pump(v) { v.visibleText().isNotEmpty() }
        val s0 = c.progressOf(v.currentPosition)
        v.startAutoScroll()
        for (i in 0 until 300) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(16)); v.draw(android.graphics.Canvas(frame)) }
        v.stopAutoScroll()
        assertTrue("scrolled: $s0 -> ${c.progressOf(v.currentPosition)}", c.progressOf(v.currentPosition) > s0)
    }

    @Test fun screenReaderScrollActionsTurnPages() {
        val v = view()
        val c = v.controller!!
        v.navigateToProgress(0.3f)
        pump(v) { !v.isNavigating && v.visibleText().isNotEmpty() }
        val info = android.view.accessibility.AccessibilityNodeInfo.obtain()
        v.onInitializeAccessibilityNodeInfo(info)
        assertTrue(info.actionList.any { it.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD })
        val p0 = c.progressOf(v.currentPosition)
        assertTrue(v.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
        pump(v) { v.visibleText().isNotEmpty() }
        val p1 = c.progressOf(v.currentPosition)
        assertTrue(p1 > p0)
        v.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null)
        pump(v) { v.visibleText().isNotEmpty() }
        assertEquals(p0, c.progressOf(v.currentPosition), 0.002f)
    }
}
