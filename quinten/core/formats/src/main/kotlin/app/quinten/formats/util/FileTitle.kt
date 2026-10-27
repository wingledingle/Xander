package app.quinten.formats.util

import java.io.File

object FileTitle {
    /**
     * Title derived from the file name, used when a book has no title of its own. Returns null
     * when the name is meaningless: files opened through a file descriptor (`/proc/self/fd/57`)
     * or temporary copies named by number. The library layer then uses the display name.
     */
    fun of(file: File): String? {
        val path = file.path.replace('\\', '/')
        if (path.startsWith("/proc/self/fd") || path.startsWith("/proc/") && path.contains("/fd/")) return null
        val name = file.nameWithoutExtension.replace('_', ' ').trim()
        if (name.isEmpty() || name.all { it.isDigit() || it == ' ' || it == '-' || it == '.' }) return null
        return name
    }
}
