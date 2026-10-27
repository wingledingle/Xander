package app.quinten.formats.util

import java.net.URLDecoder

object Paths {
    /** Directory part of a zip path ("a/b/c.xhtml" -> "a/b/"). */
    fun dir(path: String): String = path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }

    /** Resolves [href] relative to the file at [base], normalising "." and "..". Fragment is removed. */
    fun resolve(base: String, href: String): String {
        val noFrag = href.substringBefore('#').substringBefore('?')
        val decoded = try { URLDecoder.decode(noFrag.replace("+", "%2B"), "UTF-8") } catch (_: Exception) { noFrag }
        val combined = if (decoded.startsWith("/")) decoded.substring(1) else dir(base) + decoded
        val out = ArrayList<String>()
        for (part in combined.split('/')) {
            when (part) {
                "", "." -> {}
                ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else -> out.add(part)
            }
        }
        return out.joinToString("/")
    }

    fun fragment(href: String): String? = href.substringAfter('#', "").ifEmpty { null }

    /** Natural ordering for file names: "page2" < "page10". */
    val naturalOrder: Comparator<String> = Comparator { a, b ->
        var i = 0; var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]; val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ei = i; while (ei < a.length && a[ei].isDigit()) ei++
                var ej = j; while (ej < b.length && b[ej].isDigit()) ej++
                val na = a.substring(i, ei).trimStart('0'); val nb = b.substring(j, ej).trimStart('0')
                if (na.length != nb.length) return@Comparator na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return@Comparator c
                i = ei; j = ej
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return@Comparator c
                i++; j++
            }
        }
        (a.length - i) - (b.length - j)
    }
}
