package app.quinten.formats.util

import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipException
import java.util.zip.ZipFile

object Zips {
    private fun charset(name: String): Charset? = try { Charset.forName(name) } catch (_: Exception) { null }

    /**
     * Opens a zip whose entry names may not be UTF-8 (old Windows tools write the OEM codepage,
     * Japanese tools Shift_JIS). UTF-8 is tried first (names flagged as UTF-8 are always read as
     * such), then [fallbacks] in order, then CP437, which accepts any byte sequence.
     */
    fun open(file: File, fallbacks: List<String> = emptyList()): ZipFile {
        val charsets = listOf(Charsets.UTF_8) + fallbacks.mapNotNull { charset(it) } + listOfNotNull(charset("IBM437"))
        var last: Exception? = null
        for (cs in charsets) {
            var zip: ZipFile? = null
            try {
                zip = ZipFile(file, cs)
                // Entry names are decoded lazily on some platforms: decode them all now.
                val e = zip.entries()
                while (e.hasMoreElements()) e.nextElement().name
                return zip
            } catch (ex: IllegalArgumentException) {
                zip?.close(); last = ex
            } catch (ex: ZipException) {
                zip?.close()
                // "invalid CEN header (bad entry name)" is a name decoding failure; anything else is a broken zip.
                if (ex.message?.contains("entry name", ignoreCase = true) != true && ex.message?.contains("MALFORMED") != true) throw ex
                last = ex
            }
        }
        throw ZipException("Cannot read zip entry names: ${last?.message}")
    }
}
