package app.quinten.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelTest {
    @Test fun formatDetection() {
        assertEquals(BookFormat.FB2, BookFormat.fromFileName("Book.FB2.ZIP"))
        assertEquals(BookFormat.EPUB, BookFormat.fromFileName("a.epub"))
        assertEquals(BookFormat.DJVU, BookFormat.fromFileName("a.djv"))
        assertNull(BookFormat.fromFileName("a.zip"))
        assertEquals(BookFormat.PDF, BookFormat.sniff("%PDF-1.7".toByteArray()))
        assertEquals(BookFormat.CBZ, BookFormat.sniff("PK..".toByteArray(), "x.cbr"))
    }

    @Test fun builderCollapsesWhitespaceAndSpans() {
        val b = ContentBuilder()
        b.beginBlock()
        b.text("  Hello   ")
        b.pushStyle(InlineStyle.BOLD); b.text("big\n world"); b.popStyle(InlineStyle.BOLD)
        b.text("  ")
        b.beginBlock(BlockKind.HEADING, 1)
        b.anchor("h"); b.text("Title")
        val blocks = b.build()
        assertEquals(2, blocks.size)
        val p = blocks[0] as TextBlock
        assertEquals("Hello big world", p.text)
        assertEquals(StyleSpan(6, 15, InlineStyle.BOLD), p.spans.single())
        assertEquals(listOf("h"), blocks[1].anchors)
        assertEquals(1, blocks[1].firstUnit)
    }

    @Test fun sniffsKindleTopazAndKfx() {
        assertEquals(BookFormat.AZW3, BookFormat.sniff("TPZ0xxxx".toByteArray()))
        assertEquals(BookFormat.AZW3, BookFormat.sniff(byteArrayOf('C'.code.toByte(), 'O'.code.toByte(), 'N'.code.toByte(), 'T'.code.toByte(), 2, 0, 0, 0)))
        assertNull(BookFormat.sniff("CONTENTS of the book".toByteArray()))
    }

    @Test fun zeroWidthSpaceIsDropped() {
        val b = ContentBuilder()
        b.beginBlock()
        b.text("ภาษา​ไทย 日本​語­x")
        assertEquals("ภาษาไทย 日本語x", (b.build().single() as TextBlock).text)
    }

    @Test fun locatorRoundTrip() {
        val blocks = listOf(
            TextBlock("abc def", units = intArrayOf(10, 20), unitStarts = intArrayOf(0, 4)),
            TextBlock("ghi", units = intArrayOf(30)),
        )
        val ch = Chapter(2, null, blocks)
        val loc = ch.locatorAt(0, 5)
        assertEquals(Locator(2, 20, 1), loc)
        assertEquals(0 to 5, ch.positionOf(loc))
        // unit removed (e.g. clean mode): falls back to previous unit start
        assertEquals(0 to 4, ch.positionOf(Locator(2, 25, 0)))
        assertEquals(Locator(1, 2, 3), Locator.decode(Locator(1, 2, 3).encode()))
        assertEquals(LocatorRange(Locator(1, 2, 3), Locator(4, 5, 6)), LocatorRange.decode("1:2:3-4:5:6"))
    }

    @Test fun rangeEndAtParagraphEndKeepsLastCharacter() {
        val b = TextBlock("Hello world.", units = intArrayOf(7))
        val ch = Chapter(0, null, listOf(b))
        val end = ch.locatorAt(0, b.text.length, isEnd = true)
        assertEquals(0 to b.text.length, ch.positionOf(end, isEnd = true))
        // Ordinary positions still point at a character.
        assertEquals(0 to b.text.length - 1, ch.positionOf(ch.locatorAt(0, b.text.length)))
    }
}
