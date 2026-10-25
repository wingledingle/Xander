package com.xander.files.ui.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import androidx.appcompat.widget.AppCompatEditText

/** EditText with a line-number gutter, undo/redo history and incremental syntax highlighting. */
@SuppressLint("ViewConstructor")
class CodeEditText(context: Context) : AppCompatEditText(context) {
    private val gutterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val gutterBg = Paint()
    private val lineRect = Rect()
    var showLineNumbers = true
        set(v) { field = v; updatePadding(); invalidate() }
    var highlighter: SyntaxHighlighter? = null
        set(v) { field = v; rehighlight() }
    var onChanged: () -> Unit = {}

    private val undo = ArrayDeque<Edit>()
    private val redo = ArrayDeque<Edit>()
    private var applyingHistory = false
    private data class Edit(val start: Int, val before: CharSequence, val after: CharSequence)

    init {
        typeface = Typeface.MONOSPACE
        gravity = Gravity.TOP or Gravity.START
        background = null
        setHorizontallyScrolling(true)
        isVerticalScrollBarEnabled = true
        addTextChangedListener(object : TextWatcher {
            private var before: CharSequence = ""
            private var start = 0
            override fun beforeTextChanged(s: CharSequence, st: Int, count: Int, after: Int) { if (!applyingHistory) { before = s.subSequence(st, st + count).toString(); start = st } }
            override fun onTextChanged(s: CharSequence, st: Int, beforeCount: Int, count: Int) {
                if (!applyingHistory) {
                    undo.addLast(Edit(start, before, s.subSequence(st, st + count).toString()))
                    if (undo.size > 500) undo.removeFirst()
                    redo.clear()
                }
            }
            override fun afterTextChanged(s: Editable) {
                onChanged()
                removeCallbacks(highlightRunnable); postDelayed(highlightRunnable, 250)
                updatePadding()
            }
        })
    }

    private val highlightRunnable = Runnable { rehighlight() }

    fun canUndo() = undo.isNotEmpty()
    fun canRedo() = redo.isNotEmpty()

    fun undo() {
        val e = undo.removeLastOrNull() ?: return
        applyingHistory = true
        text?.replace(e.start, e.start + e.after.length, e.before)
        setSelection((e.start + e.before.length).coerceAtMost(length()))
        applyingHistory = false
        redo.addLast(e)
    }

    fun redo() {
        val e = redo.removeLastOrNull() ?: return
        applyingHistory = true
        text?.replace(e.start, e.start + e.before.length, e.after)
        setSelection((e.start + e.after.length).coerceAtMost(length()))
        applyingHistory = false
        undo.addLast(e)
    }

    fun clearHistory() { undo.clear(); redo.clear() }

    /** Highlights only the visible window (plus a margin) so huge files stay responsive. */
    fun rehighlight() {
        val t = text ?: return
        t.getSpans(0, t.length, ForegroundColorSpan::class.java).forEach { t.removeSpan(it) }
        val h = highlighter ?: return
        val l = layout ?: return
        val firstLine = l.getLineForVertical(scrollY).coerceAtLeast(0)
        val lastLine = l.getLineForVertical(scrollY + height).coerceAtMost(l.lineCount - 1)
        val start = l.getLineStart((firstLine - 200).coerceAtLeast(0))
        val end = l.getLineEnd((lastLine + 200).coerceAtMost(l.lineCount - 1))
        h.apply(t, start, end)
    }

    override fun onScrollChanged(horiz: Int, vert: Int, oldHoriz: Int, oldVert: Int) {
        super.onScrollChanged(horiz, vert, oldHoriz, oldVert)
        if (kotlin.math.abs(vert - oldVert) > 400) { removeCallbacks(highlightRunnable); postDelayed(highlightRunnable, 120) }
    }

    private fun gutterWidth(): Int {
        if (!showLineNumbers) return 0
        val digits = (lineCount.coerceAtLeast(1)).toString().length.coerceAtLeast(2)
        gutterPaint.textSize = textSize * 0.85f
        return (gutterPaint.measureText("0".repeat(digits)) + textSize).toInt()
    }

    private fun updatePadding() {
        val g = gutterWidth() + (8 * resources.displayMetrics.density).toInt()
        if (paddingLeft != g) setPadding(g, paddingTop, paddingRight, paddingBottom)
    }

    var gutterColor: Int = 0x14000000
    var gutterTextColor: Int = 0xFF888888.toInt()

    override fun onDraw(canvas: Canvas) {
        if (showLineNumbers && layout != null) {
            val w = gutterWidth()
            gutterBg.color = gutterColor
            canvas.drawRect(scrollX.toFloat(), scrollY.toFloat(), (scrollX + w).toFloat(), (scrollY + height).toFloat(), gutterBg)
            gutterPaint.color = gutterTextColor
            val l = layout
            val first = l.getLineForVertical(scrollY)
            val last = l.getLineForVertical(scrollY + height)
            var logical = 1
            // Count logical lines up to the first visible visual line.
            val t = text!!
            val firstStart = l.getLineStart(first)
            for (i in 0 until firstStart) if (t[i] == '\n') logical++
            for (line in first..last) {
                val start = l.getLineStart(line)
                if (line == 0 || t[start - 1] == '\n') {
                    getLineBounds(line, lineRect)
                    canvas.drawText(logical.toString(), (scrollX + w - textSize / 3), l.getLineBaseline(line).toFloat() + paddingTop, gutterPaint)
                    logical++
                }
            }
        }
        super.onDraw(canvas)
    }
}

/** Regex-driven highlighter; one instance per language. */
class SyntaxHighlighter(private val rules: List<Pair<Regex, Int>>) {
    fun apply(t: Spannable, start: Int, end: Int) {
        val region = t.subSequence(start, end).toString()
        val taken = BooleanArray(region.length)
        for ((re, color) in rules) {
            for (m in re.findAll(region)) {
                val s = m.range.first; val e = m.range.last + 1
                if (s >= e || taken[s]) continue
                for (i in s until e) taken[i] = true
                t.setSpan(ForegroundColorSpan(color), start + s, start + e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    companion object {
        private const val COMMENT = 0xFF6A9955.toInt(); private const val STRING = 0xFFCE9178.toInt(); private const val KEYWORD = 0xFF569CD6.toInt()
        private const val NUMBER = 0xFFB5CEA8.toInt(); private const val TAG = 0xFF4EC9B0.toInt(); private const val ATTR = 0xFF9CDCFE.toInt()
        private const val DARK_KEYWORD = 0xFF0033B3.toInt(); private const val DARK_STRING = 0xFF067D17.toInt(); private const val DARK_COMMENT = 0xFF8C8C8C.toInt()

        private fun kw(words: String) = Regex("\\b(" + words.trim().split(Regex("\\s+")).joinToString("|") + ")\\b")
        private val cBlock = Regex("/\\*[\\s\\S]*?\\*/"); private val cLine = Regex("//[^\\n]*"); private val hashLine = Regex("#[^\\n]*")
        private val dq = Regex("\"(?:\\\\.|[^\"\\\\\\n])*\""); private val sq = Regex("'(?:\\\\.|[^'\\\\\\n])*'"); private val bt = Regex("`(?:\\\\.|[^`\\\\])*`")
        private val num = Regex("\\b(0x[0-9a-fA-F]+|\\d+(\\.\\d+)?([eE][+-]?\\d+)?)[fFLlUu]?\\b")

        fun forExtension(ext: String, dark: Boolean): SyntaxHighlighter? {
            val k = if (dark) KEYWORD else DARK_KEYWORD; val s = if (dark) STRING else DARK_STRING; val c = if (dark) COMMENT else DARK_COMMENT
            val cLike = { words: String -> SyntaxHighlighter(listOf(cBlock to c, cLine to c, dq to s, sq to s, kw(words) to k, num to NUMBER)) }
            return when (ext) {
                "kt", "kts" -> cLike("package import class interface object fun val var if else when for while do return break continue try catch finally throw is as in out override open abstract sealed data enum companion private protected public internal suspend inline reified null true false this super typealias lateinit by init get set vararg const")
                "java", "gradle" -> cLike("package import class interface enum extends implements new if else switch case default for while do return break continue try catch finally throw throws instanceof static final abstract private protected public void int long short byte char boolean float double null true false this super synchronized volatile transient native strictfp var record")
                "js", "jsx", "ts", "tsx", "vue" -> SyntaxHighlighter(listOf(cBlock to c, cLine to c, dq to s, sq to s, bt to s, kw("var let const function return if else for while do switch case default break continue new delete typeof instanceof in of class extends super this null undefined true false try catch finally throw async await yield import export from as interface type enum implements public private protected readonly static") to k, num to NUMBER))
                "c", "h", "cpp", "hpp", "cc", "cs", "swift", "go", "rs", "dart" -> cLike("auto break case char const continue default do double else enum extern float for goto if int long register return short signed sizeof static struct switch typedef union unsigned void volatile while class namespace using public private protected virtual template typename new delete try catch throw bool true false nullptr func package import var let fn impl mut pub use mod match struct trait where self Self string interface go defer chan select range type map")
                "py" -> SyntaxHighlighter(listOf(Regex("\"\"\"[\\s\\S]*?\"\"\"|'''[\\s\\S]*?'''") to s, hashLine to c, dq to s, sq to s, kw("def class if elif else for while return import from as try except finally raise with lambda yield pass break continue in is not and or None True False global nonlocal async await self print") to k, num to NUMBER))
                "sh", "bash", "rc", "env", "properties", "prop", "ini", "cfg", "conf", "toml", "yaml", "yml" -> SyntaxHighlighter(listOf(hashLine to c, Regex(";[^\\n]*") to c, dq to s, sq to s, Regex("^\\s*\\[[^\\]]+\\]", RegexOption.MULTILINE) to k, Regex("^[\\w.-]+(?=\\s*[=:])", RegexOption.MULTILINE) to ATTR, kw("if then else elif fi for do done while case esac function return export local in true false yes no null") to k, num to NUMBER))
                "json" -> SyntaxHighlighter(listOf(Regex("\"(?:\\\\.|[^\"\\\\])*\"(?=\\s*:)") to ATTR, dq to s, kw("true false null") to k, num to NUMBER))
                "xml", "html", "htm", "svg" -> SyntaxHighlighter(listOf(Regex("<!--[\\s\\S]*?-->") to c, dq to s, sq to s, Regex("</?[A-Za-z][\\w:.-]*") to TAG, Regex("\\b[\\w:-]+(?==)") to ATTR))
                "css", "scss" -> SyntaxHighlighter(listOf(cBlock to c, dq to s, sq to s, Regex("[\\w-]+(?=\\s*:)") to ATTR, Regex("[.#][\\w-]+") to TAG, num to NUMBER))
                "sql" -> SyntaxHighlighter(listOf(Regex("--[^\\n]*") to c, cBlock to c, sq to s, Regex("\\b(?i:select|from|where|insert|into|values|update|set|delete|create|table|drop|alter|join|left|right|inner|outer|on|group|by|order|having|limit|and|or|not|null|as|distinct|union|index|primary|key)\\b") to k, num to NUMBER))
                "md", "markdown" -> SyntaxHighlighter(listOf(Regex("^#{1,6} .*$", RegexOption.MULTILINE) to k, Regex("`[^`\\n]+`") to s, Regex("\\*\\*[^*\\n]+\\*\\*") to TAG, Regex("\\[[^\\]]+\\]\\([^)]+\\)") to ATTR))
                "php", "rb", "pl", "lua" -> cLike("function return if else elseif for foreach while do switch case break continue class public private protected static new echo require include use namespace def end module unless then local nil true false self")
                else -> null
            }
        }
    }
}
