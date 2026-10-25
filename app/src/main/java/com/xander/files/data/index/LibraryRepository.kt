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

enum class LibraryKind { IMAGES, VIDEOS, MUSIC, DOCUMENTS, APKS, ARCHIVES, DOWNLOADS, RECENT, LARGE }

data class LibraryStat(val kind: LibraryKind, val count: Int, val size: Long)

@Singleton
class LibraryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: FileIndexDao,
) {
    private val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath

    fun categoriesOf(kind: LibraryKind): Set<FileCategory> = when (kind) {
        LibraryKind.IMAGES -> setOf(FileCategory.IMAGE)
        LibraryKind.VIDEOS -> setOf(FileCategory.VIDEO)
        LibraryKind.MUSIC -> setOf(FileCategory.AUDIO)
        LibraryKind.DOCUMENTS -> setOf(FileCategory.DOCUMENT, FileCategory.PDF, FileCategory.TEXT, FileCategory.EBOOK)
        LibraryKind.APKS -> setOf(FileCategory.APK)
        LibraryKind.ARCHIVES -> setOf(FileCategory.ARCHIVE)
        else -> emptySet()
    }

    val stats: Flow<List<LibraryStat>> = combine(
        dao.observeCategoryStats(),
        dao.observeDownloadCount(downloadDir), dao.observeDownloadSize(downloadDir),
        dao.observeLargeCount(LARGE_FILE), dao.observeLargeSize(LARGE_FILE),
    ) { cats, dc, ds, lc, ls ->
        val byCat = cats.associateBy { it.category }
        fun sum(k: LibraryKind) = categoriesOf(k).map { byCat[it.name] }.let { l -> LibraryStat(k, l.sumOf { it?.count ?: 0 }, l.sumOf { it?.size ?: 0 }) }
        listOf(
            sum(LibraryKind.IMAGES), sum(LibraryKind.VIDEOS), sum(LibraryKind.MUSIC), sum(LibraryKind.DOCUMENTS),
            sum(LibraryKind.APKS), sum(LibraryKind.ARCHIVES),
            LibraryStat(LibraryKind.DOWNLOADS, dc, ds), LibraryStat(LibraryKind.LARGE, lc, ls),
        )
    }

    val fileCount: Flow<Int> = dao.observeFileCount()

    fun appCount(): Int = runCatching { context.packageManager.getInstalledApplications(PackageManager.GET_META_DATA).size }.getOrDefault(0)

    suspend fun files(kind: LibraryKind, recentDays: Int = 30): List<FileEntry> = withContext(Dispatchers.IO) {
        val rows: List<FileIndexEntity> = when (kind) {
            LibraryKind.DOWNLOADS -> dao.under(downloadDir)
            LibraryKind.RECENT -> dao.recent(System.currentTimeMillis() - recentDays * 86_400_000L)
            LibraryKind.LARGE -> dao.large(LARGE_FILE)
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
