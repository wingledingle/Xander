package com.xander.files.data.index

import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import com.xander.files.data.db.FileIndexDao
import com.xander.files.data.db.FileIndexEntity
import com.xander.files.domain.fs.FileCategory
import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FsPath
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

enum class LibraryKind { IMAGES, VIDEOS, MUSIC, DOCUMENTS, APKS, ARCHIVES, GAMES, ADULT, DOWNLOADS, RECENT, LARGE }

data class LibraryStat(val kind: LibraryKind, val count: Int, val size: Long)

@Singleton
class LibraryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: FileIndexDao,
    private val nsfw: com.xander.files.data.nsfw.NsfwRepository,
) {
    private val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath

    fun categoriesOf(kind: LibraryKind): Set<FileCategory> = when (kind) {
        LibraryKind.IMAGES -> setOf(FileCategory.IMAGE)
        LibraryKind.VIDEOS -> setOf(FileCategory.VIDEO)
        LibraryKind.MUSIC -> setOf(FileCategory.AUDIO)
        LibraryKind.DOCUMENTS -> setOf(FileCategory.DOCUMENT, FileCategory.PDF, FileCategory.TEXT, FileCategory.EBOOK)
        LibraryKind.APKS -> setOf(FileCategory.APK)
        LibraryKind.ARCHIVES -> setOf(FileCategory.ARCHIVE)
        LibraryKind.GAMES -> setOf(FileCategory.GAME)
        else -> emptySet()
    }

    val stats: Flow<List<LibraryStat>> = combine(
        dao.observeCategoryStats(),
        combine(dao.observeDownloadCount(downloadDir), dao.observeDownloadSize(downloadDir)) { c, s -> c to s },
        dao.observeLargeCount(LARGE_FILE), dao.observeLargeSize(LARGE_FILE),
        combine(dao.observeObbStats(), nsfw.observeFlagged()) { o, a -> o to a },
    ) { cats, dl, lc, ls, extra ->
        val (dc, ds) = dl
        val (obb, adult) = extra
        val byCat = cats.associateBy { it.category }
        fun sum(k: LibraryKind) = categoriesOf(k).map { byCat[it.name] }.let { l -> LibraryStat(k, l.sumOf { it?.count ?: 0 }, l.sumOf { it?.size ?: 0 }) }
        listOf(
            sum(LibraryKind.IMAGES), sum(LibraryKind.VIDEOS), sum(LibraryKind.MUSIC), sum(LibraryKind.DOCUMENTS),
            sum(LibraryKind.APKS), sum(LibraryKind.ARCHIVES),
            sum(LibraryKind.GAMES).let { it.copy(count = it.count + obb.count, size = it.size + obb.size) },
            LibraryStat(LibraryKind.ADULT, adult.count, adult.size),
            LibraryStat(LibraryKind.DOWNLOADS, dc, ds), LibraryStat(LibraryKind.LARGE, lc, ls),
        )
    }

    private val gameApkCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** APK files whose manifest declares them as games (cached per path + mtime). */
    /** Zips that hold a GBA game (they open in Oleander). */
    private suspend fun romZips(): List<FileIndexEntity> = dao.byCategory(FileCategory.ARCHIVE.name)
        .filter { it.name.endsWith(".zip", true) && com.xander.files.modules.SharedIndex.isGbaZip(context, java.io.File(it.path)) }

    private fun gameApks(): List<FileIndexEntity> = kotlinx.coroutines.runBlocking { dao.byCategory(FileCategory.APK.name) }.filter { e ->
        gameApkCache.getOrPut("${e.path}|${e.modified}") {
            runCatching {
                val ai = context.packageManager.getPackageArchiveInfo(e.path, 0)?.applicationInfo ?: return@runCatching false
                @Suppress("DEPRECATION")
                (android.os.Build.VERSION.SDK_INT >= 26 && ai.category == android.content.pm.ApplicationInfo.CATEGORY_GAME) ||
                    ai.flags and android.content.pm.ApplicationInfo.FLAG_IS_GAME != 0
            }.getOrDefault(false)
        }
    }

    /** ISO/BIN images are only games when they live in an emulator-style folder. */
    private suspend fun gameDiscImages(): List<FileIndexEntity> {
        val hint = Regex("(?i)/(roms?|games?|emu[a-z]*|retroarch|psp|ps1|ps2|psx|gamecube|wii|dreamcast|saturn|iso)(/|$)")
        return dao.byCategory(FileCategory.ARCHIVE.name).filter { it.name.substringAfterLast('.').lowercase() in setOf("iso", "bin", "img") && hint.containsMatchIn(it.parent) } +
            dao.byCategory(FileCategory.OTHER.name).filter { it.name.substringAfterLast('.').lowercase() in setOf("bin", "img") && hint.containsMatchIn(it.parent) }
    }

    val fileCount: Flow<Int> = dao.observeFileCount()

    fun appCount(): Int = runCatching { context.packageManager.getInstalledApplications(PackageManager.GET_META_DATA).size }.getOrDefault(0)

    suspend fun files(kind: LibraryKind, recentDays: Int = 30): List<FileEntry> = withContext(Dispatchers.IO) {
        val rows: List<FileIndexEntity> = when (kind) {
            LibraryKind.DOWNLOADS -> dao.under(downloadDir)
            LibraryKind.RECENT -> dao.recent(System.currentTimeMillis() - recentDays * 86_400_000L)
            LibraryKind.LARGE -> dao.large(LARGE_FILE)
            LibraryKind.GAMES -> (dao.byCategory(FileCategory.GAME.name) + dao.obbFiles() + gameApks() + gameDiscImages() + romZips()).distinctBy { it.path }.sortedByDescending { it.modified }
            LibraryKind.ADULT -> nsfw.flaggedPaths().mapNotNull { dao.get(it) }
            else -> categoriesOf(kind).flatMap { dao.byCategory(it.name) }.sortedByDescending { it.modified }
        }
        rows.map { it.toEntry() }
    }

    suspend fun searchIndex(text: String, category: FileCategory?, minSize: Long, maxSize: Long, after: Long, before: Long, root: String): List<FileEntry> =
        withContext(Dispatchers.IO) {
            val q = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            dao.search(q, category?.name, minSize, maxSize, after, before, root).map { it.toEntry() }
        }

    companion object {
        const val LARGE_FILE = 100L shl 20
        fun FileIndexEntity.toEntry() = FileEntry(FsPath.local(path), isDirectory, size, modified)
    }
}
