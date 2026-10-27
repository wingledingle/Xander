package app.quinten.render

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThemeTest {
    @Test fun themesAreDistinctAndCustomColoursRoundTrip() {
        assertEquals(ReaderTheme.ALL.size, ReaderTheme.ALL.map { it.id }.toSet().size)
        for (t in ReaderTheme.ALL) assertEquals(t, ReaderTheme.byId(t.id))
        assertTrue(ReaderTheme.TERMINAL.isDark)
        val c = ReaderTheme.custom(0xF0E1BC, 0x24160A)
        val back = ReaderTheme.byId(c.id)
        assertEquals(c.background, back.background)
        assertEquals(Color.rgb(0x24, 0x16, 0x0A), back.text)
        assertFalse(back.isDark)
        assertTrue(ReaderTheme.byId(ReaderTheme.custom(0x101010, 0x40FF6E).id).isDark)
        assertEquals(ReaderTheme.DAY, ReaderTheme.byId("custom:zz"))
    }
}
