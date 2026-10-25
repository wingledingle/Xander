package com.xander.files.domain.fs

data class FileEntry(
    val path: FsPath,
    val isDirectory: Boolean,
    val size: Long = 0L,
    val lastModified: Long = 0L,
    val mimeType: String? = if (isDirectory) null else MimeTypes.fromName(path.name),
    val childCount: Int? = null,
    val permissions: String? = null,
    val owner: String? = null,
    val group: String? = null,
    val isSymlink: Boolean = false,
    val linkTarget: String? = null,
    val created: Long? = null,
    val canRead: Boolean = true,
    val canWrite: Boolean = true,
    /** Backend specific id (cloud file id, SAF document id...). */
    val backendId: String? = null,
    val thumbnailUrl: String? = null,
) {
    /** Always derived from the path so copies with a new path stay consistent. */
    val name: String get() = path.name
    val isHidden: Boolean get() = name.startsWith(".")
    val extension: String get() = if (isDirectory) "" else name.substringAfterLast('.', "").lowercase()
    val category: FileCategory get() = if (isDirectory) FileCategory.FOLDER else FileCategory.of(name, mimeType)
}

data class SpaceInfo(val total: Long, val free: Long) {
    val used: Long get() = (total - free).coerceAtLeast(0)
    val fraction: Float get() = if (total <= 0) 0f else used.toFloat() / total
}

enum class Capability {
    WRITE, RENAME, SERVER_SIDE_COPY, SERVER_SIDE_MOVE, THUMBNAILS, SEARCH, POSIX, SYMLINKS,
    SHARE_LINK, RANDOM_ACCESS, APPEND, SPACE_INFO, WATCH
}

data class SearchQuery(
    val text: String = "",
    val categories: Set<FileCategory> = emptySet(),
    val minSize: Long? = null,
    val maxSize: Long? = null,
    val modifiedAfter: Long? = null,
    val modifiedBefore: Long? = null,
    val includeHidden: Boolean = false,
    val includeFolders: Boolean = true,
    val recursive: Boolean = true,
) {
    private val regex: Regex? by lazy {
        if (text.contains('*') || text.contains('?')) {
            Regex(text.split('*').joinToString(".*") { part -> part.split('?').joinToString(".") { Regex.escape(it) } }, RegexOption.IGNORE_CASE)
        } else null
    }

    fun matches(e: FileEntry): Boolean {
        if (!includeHidden && e.isHidden) return false
        if (e.isDirectory && (!includeFolders || categories.isNotEmpty() || minSize != null || maxSize != null)) {
            return includeFolders && categories.isEmpty() && minSize == null && maxSize == null && matchesText(e.name) && matchesDate(e)
        }
        if (categories.isNotEmpty() && e.category !in categories) return false
        if (minSize != null && e.size < minSize) return false
        if (maxSize != null && e.size > maxSize) return false
        return matchesDate(e) && matchesText(e.name)
    }

    private fun matchesDate(e: FileEntry): Boolean =
        (modifiedAfter == null || e.lastModified >= modifiedAfter) && (modifiedBefore == null || e.lastModified <= modifiedBefore)

    private fun matchesText(name: String): Boolean {
        if (text.isBlank()) return true
        val r = regex
        return r?.matches(name) ?: name.contains(text.trim(), ignoreCase = true)
    }
}
