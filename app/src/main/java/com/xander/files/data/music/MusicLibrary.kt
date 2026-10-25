package com.xander.files.data.music

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.xander.files.data.db.PlaylistDao
import com.xander.files.data.db.PlaylistEntity
import com.xander.files.data.db.PlaylistItemEntity
import com.xander.files.domain.fs.FsPath
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class Song(val id: Long, val title: String, val artist: String, val album: String, val albumId: Long, val durationMs: Long, val path: String, val track: Int, val year: Int) {
    val fsPath get() = FsPath.local(path)
    val folder get() = path.substringBeforeLast('/')
}

data class Album(val id: Long, val title: String, val artist: String, val songs: List<Song>)
data class Artist(val name: String, val songs: List<Song>) { val albums get() = songs.map { it.album }.distinct().size }
data class MusicFolder(val path: String, val songs: List<Song>) { val name get() = path.substringAfterLast('/') }

@Singleton
class MusicLibrary @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playlists: PlaylistDao,
) {
    @Volatile private var cache: List<Song>? = null

    suspend fun songs(refresh: Boolean = false): List<Song> = withContext(Dispatchers.IO) {
        if (!refresh) cache?.let { return@withContext it }
        val proj = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.TRACK, MediaStore.Audio.Media.YEAR)
        val out = ArrayList<Song>()
        runCatching {
            context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, "${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.DURATION} > 30000", null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE")?.use { c ->
                while (c.moveToNext()) {
                    val path = c.getString(6) ?: continue
                    out += Song(c.getLong(0), c.getString(1) ?: path.substringAfterLast('/'), c.getString(2)?.takeIf { it != "<unknown>" } ?: "Unknown artist",
                        c.getString(3) ?: "", c.getLong(4), c.getLong(5), path, c.getInt(7) % 1000, c.getInt(8))
                }
            }
        }
        cache = out
        out
    }

    suspend fun albums() = songs().groupBy { it.albumId }.map { (id, s) -> Album(id, s.first().album, s.first().artist, s.sortedBy { it.track }) }.sortedBy { it.title.lowercase() }
    suspend fun artists() = songs().groupBy { it.artist }.map { (n, s) -> Artist(n, s) }.sortedBy { it.name.lowercase() }
    suspend fun folders() = songs().groupBy { it.folder }.map { (p, s) -> MusicFolder(p, s.sortedBy { it.path }) }.sortedBy { it.name.lowercase() }

    fun contentUri(s: Song) = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, s.id)

    // ---- playlists (stored in Room, may reference any backend) ----
    val playlistFlow = playlists.observePlaylists()
    fun items(id: Long) = playlists.observeItems(id)
    suspend fun itemsOf(id: Long) = playlists.items(id)
    suspend fun createPlaylist(name: String) = playlists.insert(PlaylistEntity(name = name))
    suspend fun renamePlaylist(id: Long, name: String) = playlists.rename(id, name)
    suspend fun deletePlaylist(id: Long) { playlists.clearItems(id); playlists.deletePlaylist(id) }
    suspend fun addToPlaylist(id: Long, songs: List<Song>) {
        val cur = playlists.items(id)
        playlists.replaceItems(id, cur + songs.map { PlaylistItemEntity(id, 0, FsPath.local(it.path).toString(), it.title, it.artist, it.album, it.durationMs) })
    }
    suspend fun removeFromPlaylist(id: Long, position: Int) {
        playlists.replaceItems(id, playlists.items(id).filterIndexed { i, _ -> i != position })
    }
    suspend fun movePlaylistItem(id: Long, from: Int, to: Int) {
        val l = playlists.items(id).toMutableList(); if (from !in l.indices || to !in l.indices) return
        val x = l.removeAt(from); l.add(to, x); playlists.replaceItems(id, l)
    }
}
