package com.xander.files.data.open

import com.xander.files.domain.fs.FileCategory
import com.xander.files.domain.fs.FileEntry

enum class ViewerKind { IMAGE, VIDEO, AUDIO, TEXT, PDF, OFFICE, BOOK, EXTERNAL }

/** Picks the built-in viewer for a file; everything else goes to other apps. */
object ViewerRouter {
    private val office = setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "csv")
    private val books = setOf("epub", "fb2")
    private val textLike = setOf("txt", "log", "md", "markdown", "ini", "cfg", "conf", "properties", "prop", "srt", "vtt", "ass", "nfo", "json", "xml", "csv", "yaml", "yml", "toml", "env", "rc")

    fun kindOf(e: FileEntry): ViewerKind {
        val ext = e.extension
        return when {
            e.category == FileCategory.IMAGE && ext !in setOf("svg", "raw", "cr2", "nef", "arw", "dng") -> ViewerKind.IMAGE
            e.category == FileCategory.VIDEO -> ViewerKind.VIDEO
            e.category == FileCategory.AUDIO -> ViewerKind.AUDIO
            e.category == FileCategory.PDF -> ViewerKind.PDF
            ext in books -> ViewerKind.BOOK
            ext == "csv" -> ViewerKind.OFFICE
            ext in office -> ViewerKind.OFFICE
            e.category == FileCategory.CODE || e.category == FileCategory.TEXT || ext in textLike -> ViewerKind.TEXT
            else -> ViewerKind.EXTERNAL
        }
    }
}
