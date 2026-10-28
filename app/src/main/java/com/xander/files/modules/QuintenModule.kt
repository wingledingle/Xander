package com.xander.files.modules

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import com.xander.files.R
import com.xander.files.domain.fs.FileCategory

/** Quinten (Dingo Reader): e-reader with reflow, OCR and read-aloud (quinten/). Needs Android 8+. */
object QuintenModule : ActivityModule(
    id = "quinten",
    title = R.string.module_quinten,
    summary = R.string.module_quinten_summary,
    icon = Icons.AutoMirrored.Filled.MenuBook,
    activity = app.quinten.MainActivity::class.java,
    opens = setOf("epub", "pdf", "fb2", "mobi", "azw", "azw3", "djvu", "cbz", "cbr", "doc", "docx", "rtf", "txt", "zip"),
    // Formats Xander has no viewer for open here on tap; the rest keep Xander's viewers and offer "Open in…".
    defaultFor = setOf("txt", "mobi", "azw", "azw3", "djvu", "cbz", "cbr"),
) {
    override val minSdk = 26

    init {
        // Quinten's library reads book files from Xander's index instead of walking storage a second time.
        app.quinten.data.ExternalBookIndex.provider = { c ->
            listOf(FileCategory.EBOOK, FileCategory.PDF, FileCategory.DOCUMENT, FileCategory.TEXT, FileCategory.ARCHIVE)
                .flatMap { SharedIndex.byCategory(c, it) }.map { java.io.File(it.path) }
        }
    }
}
