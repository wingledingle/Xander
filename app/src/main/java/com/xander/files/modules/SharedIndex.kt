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

    /** True when [f] is a zip holding a GBA ROM. Answers are cached by path, size and date, so rescans are cheap. */
    @JvmStatic fun isGbaZip(c: Context, f: File): Boolean {
        val prefs = c.getSharedPreferences("shared_index_zips", Context.MODE_PRIVATE)
        val stamp = "${f.length()}:${f.lastModified()}"
        prefs.getString(f.path, null)?.let { if (it.substringBeforeLast(':') == stamp) return it.endsWith(":1") }
        val yes = runCatching { ZipFile(f).use { z -> z.entries().asSequence().any { !it.isDirectory && it.name.substringAfterLast('.').lowercase() in GBA } } }.getOrDefault(false)
        prefs.edit().putString(f.path, "$stamp:${if (yes) 1 else 0}").apply()
        return yes
    }
}
