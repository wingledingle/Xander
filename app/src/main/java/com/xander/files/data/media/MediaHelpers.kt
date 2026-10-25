package com.xander.files.data.media

import com.xander.files.data.db.MediaPositionDao
import com.xander.files.data.db.MediaPositionEntity
import com.xander.files.domain.fs.FileCategory
import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FileSystemRegistry
import com.xander.files.domain.fs.FsPath
import javax.inject.Inject
import javax.inject.Singleton

/** Folder-based playlists/galleries and resume positions shared by the viewers. */
@Singleton
class MediaLibraryHelper @Inject constructor(
    private val registry: FileSystemRegistry,
    private val positions: MediaPositionDao,
) {
    suspend fun siblings(path: FsPath, categories: Set<FileCategory>): List<FileEntry> {
        val parent = path.parent ?: return emptyList()
        return runCatching { registry.provider(parent).list(parent) }.getOrDefault(emptyList())
            .filter { !it.isDirectory && it.category in categories && !it.isHidden }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    suspend fun position(path: FsPath): Long = positions.get(path.toString())?.positionMs ?: 0L

    suspend fun savePosition(path: FsPath, positionMs: Long, durationMs: Long) {
        // Finished or barely started items restart from the beginning next time.
        val p = if (durationMs > 0 && (positionMs > durationMs - 5_000 || positionMs < 3_000)) 0 else positionMs
        positions.upsert(MediaPositionEntity(path.toString(), p, durationMs))
    }

    suspend fun readerPosition(path: FsPath): String? = positions.get("read:$path")?.extra
    suspend fun saveReaderPosition(path: FsPath, value: String) = positions.upsert(MediaPositionEntity("read:$path", 0, 0, value))

    /** Subtitle files next to a video: same base name with .srt/.vtt/.ass/.ssa (optionally .lang.srt). */
    suspend fun subtitlesFor(video: FsPath): List<FileEntry> {
        val parent = video.parent ?: return emptyList()
        val base = video.name.substringBeforeLast('.')
        return runCatching { registry.provider(parent).list(parent) }.getOrDefault(emptyList())
            .filter { !it.isDirectory && it.extension in setOf("srt", "vtt", "ass", "ssa", "ttml") && it.name.startsWith(base) }
    }
}
