package app.quinten.render

import android.view.View
import androidx.test.core.app.ApplicationProvider
import app.quinten.formats.BookOpener
import app.quinten.model.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * A book reopened in a reader view that is already on screen (same size, so no size change follows) must get that
 * view's page size. It used to keep the new controller's 1x1 placeholder: one letter per line and per page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ControllerSwapTest {
    private val sample = File("../../core/formats/src/test/resources/fixtures/sample.epub")
    private fun controller() = ReaderController(BookOpener.open(sample, BookFormat.EPUB), 2f, 2f)

    @Test fun reattachedControllerGetsTheViewSize() {
        val rv = ReaderView(ApplicationProvider.getApplicationContext())
        rv.controller = controller()
        rv.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.EXACTLY))
        rv.layout(0, 0, 1000, 1800)
        val first = rv.controller!!
        val second = controller()
        rv.controller = second // e.g. the book reopened after a setting change; the view keeps its size
        assertEquals(first.contentWidth, second.contentWidth)
        assertEquals(first.contentHeight, second.contentHeight)
    }

    @Test fun drawingNeverPaginatesAtThePlaceholderSize() {
        val rv = ReaderView(ApplicationProvider.getApplicationContext())
        rv.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.EXACTLY))
        rv.layout(0, 0, 1000, 1800)
        val c = controller()
        // Attached without the setter's fix path (as if sized before any size callback), then drawn.
        val f = ReaderView::class.java.getDeclaredField("controller").apply { isAccessible = true }
        f.set(rv, c)
        rv.draw(android.graphics.Canvas(android.graphics.Bitmap.createBitmap(1000, 1800, android.graphics.Bitmap.Config.ARGB_8888)))
        org.junit.Assert.assertTrue("width ${c.contentWidth}", c.contentWidth > 500)
    }
}
