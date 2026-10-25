package com.xander.files.domain.fs

import android.webkit.MimeTypeMap

object MimeTypes {
    private val extra = mapOf(
        "apk" to "application/vnd.android.package-archive",
        "apks" to "application/zip", "xapk" to "application/zip", "apkm" to "application/zip",
        "obb" to "application/octet-stream",
        "kt" to "text/x-kotlin", "kts" to "text/x-kotlin", "md" to "text/markdown", "json" to "application/json",
        "yaml" to "text/yaml", "yml" to "text/yaml", "toml" to "text/plain", "log" to "text/plain", "ini" to "text/plain",
        "srt" to "application/x-subrip", "vtt" to "text/vtt", "ass" to "text/x-ssa",
        "mkv" to "video/x-matroska", "webm" to "video/webm", "flac" to "audio/flac", "opus" to "audio/opus",
        "m4a" to "audio/mp4", "heic" to "image/heic", "avif" to "image/avif", "webp" to "image/webp",
        "7z" to "application/x-7z-compressed", "rar" to "application/vnd.rar", "tar" to "application/x-tar",
        "gz" to "application/gzip", "tgz" to "application/gzip", "bz2" to "application/x-bzip2", "xz" to "application/x-xz",
        "epub" to "application/epub+zip", "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "doc" to "application/msword", "xls" to "application/vnd.ms-excel", "ppt" to "application/vnd.ms-powerpoint",
        FileCategory.ENCRYPTED_EXTENSION to "application/octet-stream",
    )

    fun fromName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return "application/octet-stream"
        extra[ext]?.let { return it }
        return runCatching { MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) }.getOrNull()
            ?: "application/octet-stream"
    }

    fun extensionFor(mime: String): String? =
        extra.entries.firstOrNull { it.value == mime }?.key
            ?: runCatching { MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) }.getOrNull()
}
