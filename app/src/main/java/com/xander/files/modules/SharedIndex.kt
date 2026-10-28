package com.xander.files.modules

import android.content.Context
import com.xander.files.data.db.FileIndexDao
import com.xander.files.data.db.FileIndexEntity
import com.xander.files.domain.fs.FileCategory
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.zip.ZipFile

/**
 * Xander's one storage index, shared with the modules so they don't each scan the phone again: Oleander's game
 * list, Quinten's library and the Studio's media browser all read it. Calls block; use them off the main thread.
 */
object SharedIndex {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps { fun index(): FileIndexDao }

    private fun dao(c: Context) = EntryPointAccessors.fromApplication(c.applicationContext, Deps::class.java).index()

    @JvmStatic fun byCategory(c: Context, cat: FileCategory): List<FileIndexEntity> = runBlocking { dao(c).byCategory(cat.name) }

    private val GBA = setOf("gba", "agb", "mb")

    /** GBA ROMs: .gba/.agb/.mb files, plus zips that contain one (playable without extracting by hand). */
    @JvmStatic fun gbaRoms(c: Context): List<File> {
        val plain = byCategory(c, FileCategory.GAME).filter { it.name.substringAfterLast('.').lowercase() in GBA }.map { File(it.path) }
        val zips = byCategory(c, FileCategory.ARCHIVE).filter { it.name.endsWith(".zip", true) }.map { File(it.path) }.filter { isGbaZip(c, it) }
        return (plain + zips).filter { it.canRead() }
    }

    /** What a zip holds that a module can open: a GBA game (Oleander) or a book (Quinten). */
    enum class ZipKind { GBA, BOOK, NONE }

    private val BOOK = setOf("epub", "pdf", "mobi", "azw", "azw3", "kf8", "prc", "fb2", "djvu", "djv", "txt", "rtf", "doc", "docx", "cbz", "cbr")

    /** Answers are cached by path, size and date, so rescans and taps are cheap. */
    @JvmStatic fun zipKind(c: Context, f: File): ZipKind {
        val prefs = c.getSharedPreferences("shared_index_zips2", Context.MODE_PRIVATE)
        val stamp = "${f.length()}:${f.lastModified()}"
        prefs.getString(f.path, null)?.let { v -> if (v.substringBeforeLast(':') == stamp) return runCatching { ZipKind.valueOf(v.substringAfterLast(':')) }.getOrDefault(ZipKind.NONE) }
        val kind = runCatching {
            ZipFile(f).use { z ->
                val files = z.entries().asSequence().filter { !it.isDirectory && !it.name.startsWith("__MACOSX") }.toList()
                val ext = { e: java.util.zip.ZipEntry -> e.name.substringAfterLast('.').lowercase() }
                // A book zip: its largest file is the book (so a program's zip with a readme.txt is not one).
                when {
                    files.any { ext(it) in GBA } -> ZipKind.GBA
                    files.maxByOrNull { it.size }?.let { ext(it) in BOOK } == true -> ZipKind.BOOK
                    else -> ZipKind.NONE
                }
            }
        }.getOrDefault(ZipKind.NONE)
        prefs.edit().putString(f.path, "$stamp:${kind.name}").apply()
        return kind
    }

    @JvmStatic fun isGbaZip(c: Context, f: File): Boolean = zipKind(c, f) == ZipKind.GBA
}
