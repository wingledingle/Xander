package app.quinten.render

import android.graphics.Color

enum class TextAlign { START, JUSTIFY }

/** Visual theme of the reading surface. */
data class ReaderTheme(
    val id: String,
    val name: String,
    val background: Int,
    val text: Int,
    val secondaryText: Int,
    val link: Int,
    val selection: Int,
    val isDark: Boolean,
) {
    companion object {
        val DAY = ReaderTheme("day", "Day", Color.rgb(255, 255, 255), Color.rgb(28, 28, 28), Color.rgb(110, 110, 110), Color.rgb(25, 98, 196), 0x553388FF, false)
        val SEPIA = ReaderTheme("sepia", "Sepia", Color.rgb(244, 236, 216), Color.rgb(67, 52, 34), Color.rgb(130, 110, 85), Color.rgb(140, 70, 20), 0x55B08040, false)
        val PAPER = ReaderTheme("paper", "Paper", Color.rgb(238, 238, 232), Color.rgb(40, 40, 40), Color.rgb(110, 110, 110), Color.rgb(25, 98, 196), 0x553388FF, false)
        val GREEN = ReaderTheme("green", "Green", Color.rgb(207, 232, 207), Color.rgb(30, 50, 30), Color.rgb(80, 110, 80), Color.rgb(20, 90, 140), 0x55336633, false)
        val NIGHT = ReaderTheme("night", "Night", Color.rgb(32, 33, 36), Color.rgb(205, 205, 200), Color.rgb(140, 140, 140), Color.rgb(120, 170, 255), 0x557799FF, true)
        val BLACK = ReaderTheme("black", "Black (AMOLED)", Color.rgb(0, 0, 0), Color.rgb(190, 190, 185), Color.rgb(120, 120, 120), Color.rgb(120, 170, 255), 0x557799FF, true)
        val NIGHT_AMBER = ReaderTheme("amber", "Night amber", Color.rgb(20, 16, 10), Color.rgb(230, 170, 90), Color.rgb(150, 110, 60), Color.rgb(240, 190, 120), 0x55AA7733, true)
        val SEPIA_CONTRAST = ReaderTheme("sepia_hc", "Sepia contrast", Color.rgb(240, 225, 188), Color.rgb(36, 22, 8), Color.rgb(96, 70, 40), Color.rgb(130, 50, 0), 0x55A06020, false)
        val DARK_SEPIA = ReaderTheme("sepia_dark", "Dark sepia", Color.rgb(46, 37, 27), Color.rgb(228, 208, 172), Color.rgb(160, 138, 105), Color.rgb(240, 180, 110), 0x55B08040, true)
        val TERMINAL = ReaderTheme("terminal", "Terminal", Color.rgb(8, 10, 8), Color.rgb(64, 255, 110), Color.rgb(30, 160, 70), Color.rgb(120, 255, 210), 0x5540FF6E, true)
        val TERMINAL_AMBER = ReaderTheme("terminal_amber", "Amber terminal", Color.rgb(10, 7, 0), Color.rgb(255, 176, 0), Color.rgb(170, 115, 0), Color.rgb(255, 215, 120), 0x55FFB000, true)
        val HIGH_CONTRAST = ReaderTheme("hc_light", "High contrast", Color.rgb(255, 255, 255), Color.rgb(0, 0, 0), Color.rgb(60, 60, 60), Color.rgb(0, 60, 200), 0x660055FF, false)
        val HIGH_CONTRAST_DARK = ReaderTheme("hc_dark", "High contrast night", Color.rgb(0, 0, 0), Color.rgb(255, 255, 255), Color.rgb(200, 200, 200), Color.rgb(255, 230, 0), 0x66FFE600, true)
        val SOLARIZED_LIGHT = ReaderTheme("solar_light", "Solarized light", Color.rgb(253, 246, 227), Color.rgb(55, 70, 76), Color.rgb(101, 123, 131), Color.rgb(38, 139, 210), 0x55268BD2, false)
        val SOLARIZED_DARK = ReaderTheme("solar_dark", "Solarized dark", Color.rgb(0, 43, 54), Color.rgb(170, 184, 184), Color.rgb(101, 123, 131), Color.rgb(42, 161, 152), 0x552AA198, true)
        val NORD = ReaderTheme("nord", "Nord", Color.rgb(46, 52, 64), Color.rgb(216, 222, 233), Color.rgb(140, 150, 170), Color.rgb(136, 192, 208), 0x5588C0D0, true)
        val DRACULA = ReaderTheme("dracula", "Dracula", Color.rgb(40, 42, 54), Color.rgb(248, 248, 242), Color.rgb(150, 155, 180), Color.rgb(189, 147, 249), 0x55BD93F9, true)
        val GRUVBOX = ReaderTheme("gruvbox", "Gruvbox", Color.rgb(40, 40, 40), Color.rgb(235, 219, 178), Color.rgb(168, 153, 132), Color.rgb(250, 189, 47), 0x55FABD2F, true)
        val MIDNIGHT = ReaderTheme("midnight", "Midnight blue", Color.rgb(15, 22, 44), Color.rgb(200, 210, 235), Color.rgb(120, 135, 170), Color.rgb(130, 180, 255), 0x557799FF, true)
        val SLATE = ReaderTheme("slate", "Slate", Color.rgb(58, 61, 66), Color.rgb(222, 222, 220), Color.rgb(150, 150, 150), Color.rgb(140, 190, 255), 0x557799FF, true)
        val SKY = ReaderTheme("sky", "Sky", Color.rgb(224, 237, 250), Color.rgb(20, 35, 60), Color.rgb(80, 100, 130), Color.rgb(20, 90, 190), 0x553388FF, false)
        val ROSE = ReaderTheme("rose", "Rose", Color.rgb(250, 233, 233), Color.rgb(62, 30, 40), Color.rgb(130, 90, 100), Color.rgb(170, 40, 80), 0x55CC5577, false)
        val LAVENDER = ReaderTheme("lavender", "Lavender", Color.rgb(236, 232, 250), Color.rgb(40, 30, 72), Color.rgb(105, 95, 140), Color.rgb(100, 60, 190), 0x557755CC, false)

        val ALL = listOf(
            DAY, PAPER, SEPIA, SEPIA_CONTRAST, GREEN, SKY, ROSE, LAVENDER, HIGH_CONTRAST, SOLARIZED_LIGHT,
            NIGHT, BLACK, NIGHT_AMBER, DARK_SEPIA, TERMINAL, TERMINAL_AMBER, HIGH_CONTRAST_DARK, SOLARIZED_DARK,
            NORD, DRACULA, GRUVBOX, MIDNIGHT, SLATE,
        )

        /** A user-picked pair of colours, stored in the id as "custom:RRGGBB:RRGGBB". */
        fun custom(background: Int, text: Int): ReaderTheme {
            val dark = luminance(background) < 0.45
            val secondary = blend(text, background, 0.4f)
            val link = if (dark) Color.rgb(130, 180, 255) else Color.rgb(25, 98, 196)
            val sel = (0x55 shl 24) or (text and 0xFFFFFF)
            return ReaderTheme("custom:%06X:%06X".format(background and 0xFFFFFF, text and 0xFFFFFF), "Custom", background or (0xFF shl 24), text or (0xFF shl 24), secondary, link, sel, dark)
        }

        fun byId(id: String?): ReaderTheme {
            if (id != null && id.startsWith("custom:")) {
                val parts = id.split(':')
                val bg = parts.getOrNull(1)?.toIntOrNull(16)
                val fg = parts.getOrNull(2)?.toIntOrNull(16)
                if (bg != null && fg != null) return custom(bg, fg)
            }
            return ALL.firstOrNull { it.id == id } ?: DAY
        }

        fun luminance(c: Int): Double = (0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)) / 255.0

        private fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
            (Color.red(a) * (1 - t) + Color.red(b) * t).toInt(),
            (Color.green(a) * (1 - t) + Color.green(b) * t).toInt(),
            (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt(),
        )
    }
}

/**
 * Typography and layout settings applied uniformly to every format. Font sizes go up to very
 * large print; content always reflows to the screen width, so there is never horizontal scrolling.
 */
data class ReaderSettings(
    val fontSizeSp: Float = 20f,
    /** "serif", "sans-serif", "monospace", "sans-serif-condensed", "serif-monospace", or a path to a user font file. */
    val fontFamily: String = "serif",
    val boldText: Boolean = false,
    val lineSpacing: Float = 1.35f,
    /** Extra space between paragraphs, in em. */
    val paragraphSpacing: Float = 0.4f,
    /** First-line indent, in em. */
    val firstLineIndent: Float = 1.2f,
    val letterSpacing: Float = 0f,
    val marginHorizontalDp: Int = 20,
    val marginVerticalDp: Int = 24,
    val align: TextAlign = TextAlign.JUSTIFY,
    val hyphenation: Boolean = true,
    val paginated: Boolean = true,
    val showImages: Boolean = true,
    /** Keep the book's centre/right alignment hints (headings, epigraphs). */
    val respectAlignment: Boolean = true,
    val theme: ReaderTheme = ReaderTheme.DAY,
    val showStatusBar: Boolean = true,
) {
    companion object {
        const val MIN_FONT = 10f
        const val MAX_FONT = 96f
    }
}
