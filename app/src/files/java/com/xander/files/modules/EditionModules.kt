package com.xander.files.modules

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.SportsEsports
import com.xander.files.R

/**
 * Xander Files on its own: Groovesmith, Oleander and Quinten are separate apps. Each shows up here (drawer entry,
 * "Open in ...", tap-to-open) only while it is installed, and gets the file handed over.
 */
object EditionModules {
    val list: List<XanderModule> = listOf(
        ExternalAppModule("studio", R.string.module_studio, R.string.module_studio_summary, Icons.Filled.GraphicEq, "dev.groovesmith.studio",
            opens = setOf("gsproj", "mp4", "mkv", "webm", "mov", "3gp", "avi", "m4v", "ts", "wav", "mp3", "flac", "ogg", "opus", "m4a", "aac", "aif", "aiff"),
            defaultFor = setOf("gsproj")),
        ExternalAppModule("oleander", R.string.module_oleander, R.string.module_oleander_summary, Icons.Filled.SportsEsports, "dev.oleander.app",
            opens = setOf("gba", "agb", "mb", "zip"), defaultFor = setOf("gba", "agb"), zipKind = SharedIndex.ZipKind.GBA),
        ExternalAppModule("quinten", R.string.module_quinten, R.string.module_quinten_summary, Icons.AutoMirrored.Filled.MenuBook, "app.quinten",
            opens = setOf("epub", "pdf", "fb2", "mobi", "azw", "azw3", "djvu", "cbz", "cbr", "doc", "docx", "rtf", "txt", "zip"),
            defaultFor = setOf("epub", "pdf", "fb2", "mobi", "azw", "azw3", "djvu", "cbz", "cbr", "doc", "docx", "rtf", "txt"),
            zipKind = SharedIndex.ZipKind.BOOK, minSdk = 26),
    )
}
