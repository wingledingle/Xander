package app.quinten.speech

/** Numbers as English words ("1895" read as a year, "21st", "3.5", "£2"), like num2words in misaki. */
object Numbers {
    private val ones = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
        "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    )
    private val tens = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    private val scales = listOf(1_000_000_000_000L to "trillion", 1_000_000_000L to "billion", 1_000_000L to "million", 1_000L to "thousand")

    fun cardinal(n: Long): String {
        if (n < 0) return "minus " + cardinal(-n)
        if (n < 20) return ones[n.toInt()]
        if (n < 100) return tens[(n / 10).toInt()] + if (n % 10 != 0L) "-" + ones[(n % 10).toInt()] else ""
        if (n < 1000) return ones[(n / 100).toInt()] + " hundred" + if (n % 100 != 0L) " and " + cardinal(n % 100) else ""
        for ((v, name) in scales) if (n >= v) {
            val rest = n % v
            return cardinal(n / v) + " " + name + when {
                rest == 0L -> ""
                rest < 100 -> " and " + cardinal(rest)
                else -> " " + cardinal(rest)
            }
        }
        return n.toString()
    }

    fun ordinal(n: Long): String {
        val c = cardinal(n)
        val lastSep = maxOf(c.lastIndexOf(' '), c.lastIndexOf('-'))
        val head = c.substring(0, lastSep + 1); val last = c.substring(lastSep + 1)
        val o = when (last) {
            "one" -> "first"; "two" -> "second"; "three" -> "third"; "five" -> "fifth"; "eight" -> "eighth"
            "nine" -> "ninth"; "twelve" -> "twelfth"
            else -> if (last.endsWith("y")) last.dropLast(1) + "ieth" else last + "th"
        }
        return head + o
    }

    /** "1895" -> "eighteen ninety-five", "1900" -> "nineteen hundred", "2005" -> "two thousand and five". */
    fun year(n: Int): String {
        if (n < 1000 || n > 9999 || (n in 2000..2009) || n % 1000 == 0) return cardinal(n.toLong())
        val hi = n / 100; val lo = n % 100
        return cardinal(hi.toLong()) + when {
            lo == 0 -> " hundred"
            lo < 10 -> " oh " + cardinal(lo.toLong())
            else -> " " + cardinal(lo.toLong())
        }
    }

    private val currencies = mapOf('$' to ("dollar" to "cent"), '£' to ("pound" to "pence"), '€' to ("euro" to "cent"))

    /**
     * Words for a number token: [currency] symbol before it, digits with "," and ".", an optional
     * suffix ("st", "s", "'s"). Returns null for things that are not numbers.
     */
    fun words(token: String, currency: Char? = null): String? {
        val m = Regex("^(-?)([0-9][0-9,]*(?:\\.[0-9]+)?|\\.[0-9]+)(st|nd|rd|th|s|'s|’s)?$").matchEntire(token) ?: return null
        val minus = m.groupValues[1].isNotEmpty()
        val num = m.groupValues[2].replace(",", "")
        val suffix = m.groupValues[3]
        val sb = StringBuilder()
        if (minus) sb.append("minus ")
        val cur = currency?.let { currencies[it] }
        when {
            suffix in setOf("st", "nd", "rd", "th") && '.' !in num -> sb.append(ordinal(num.toLong()))
            cur != null -> {
                val parts = num.split('.')
                val whole = parts[0].ifEmpty { "0" }.toLong()
                val cents = parts.getOrNull(1)?.let { (it + "0").take(2).toLong() } ?: 0L
                if (whole > 0 || cents == 0L) sb.append(cardinal(whole)).append(' ').append(cur.first).append(if (whole == 1L) "" else "s")
                if (cents > 0) {
                    if (whole > 0) sb.append(" and ")
                    sb.append(cardinal(cents)).append(' ').append(if (cur.second == "pence" || cents == 1L) cur.second else cur.second + "s")
                }
            }
            '.' in num -> {
                val (a, b) = num.split('.', limit = 2)
                if (a.isNotEmpty()) sb.append(cardinal(a.toLong())).append(' ')
                sb.append("point")
                for (d in b) sb.append(' ').append(ones[d - '0'])
            }
            num.length == 4 && m.groupValues[2].none { it == ',' } && !minus -> sb.append(year(num.toInt()))
            num.length > 1 && num[0] == '0' -> sb.append(num.map { if (it == '0') "oh" else ones[it - '0'] }.joinToString(" "))
            num.length > 15 -> sb.append(num.map { ones[it - '0'] }.joinToString(" "))
            else -> sb.append(cardinal(num.toLong()))
        }
        var out = sb.toString()
        if (suffix == "s" || suffix == "'s" || suffix == "’s") {
            // "1920s" -> "nineteen twenties", "5s" -> "fives".
            out = if (out.endsWith("y")) out.dropLast(1) + "ies" else if (out.endsWith("x")) out + "es" else out + "s"
        }
        return out
    }
}
