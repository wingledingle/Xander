package com.xander.files.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "saf_roots")
data class SafRootEntity(
    @PrimaryKey val id: String,
    val treeUri: String,
    val label: String,
    val volumeUuid: String?,
    val volumePath: String?,
    val kind: String,
    val addedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "bookmarks", indices = [Index(value = ["path"], unique = true)])
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    val title: String,
    val isFolder: Boolean,
    val position: Int,
    val pinnedHome: Boolean = true,
    val addedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "recent_files", indices = [Index("openedAt")])
data class RecentFileEntity(
    @PrimaryKey val path: String,
    val name: String,
    val mime: String?,
    val size: Long,
    val openedAt: Long,
)

@Entity(tableName = "history", indices = [Index("visitedAt")])
data class HistoryEntity(
    @PrimaryKey val path: String,
    val title: String,
    val visitedAt: Long,
)

@Entity(tableName = "recycle_bin", indices = [Index("deletedAt")])
data class RecycleEntity(
    @PrimaryKey val id: String,
    val originalPath: String,
    val binPath: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val deletedAt: Long,
)

@Entity(tableName = "clipboard")
data class ClipboardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val operation: String,
    val pathsJson: String,
    val label: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "connections")
data class ConnectionEntity(
    @PrimaryKey val id: String,
    val type: String,
    val name: String,
    val host: String,
    val port: Int,
    val username: String = "",
    val secret: String = "",
    val domain: String = "",
    val remotePath: String = "/",
    val optionsJson: String = "{}",
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "cloud_accounts")
data class CloudAccountEntity(
    @PrimaryKey val id: String,
    val service: String,
    val displayName: String,
    val email: String,
    val secret: String,
    val extraJson: String = "{}",
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "transfers", indices = [Index("state")])
data class TransferEntity(
    @PrimaryKey val id: String,
    val type: String,
    val state: String,
    val title: String,
    val sourcesJson: String,
    val destination: String?,
    val optionsJson: String = "{}",
    val totalBytes: Long = 0,
    val doneBytes: Long = 0,
    val totalItems: Int = 0,
    val doneItems: Int = 0,
    val currentName: String? = null,
    val speedBps: Long = 0,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "file_index",
    indices = [Index("name"), Index("category"), Index("size"), Index("modified"), Index("parent"), Index("volume")]
)
data class FileIndexEntity(
    @PrimaryKey val path: String,
    val name: String,
    val parent: String,
    val size: Long,
    val modified: Long,
    val category: String,
    val isDirectory: Boolean,
    val volume: String,
    /** Run stamp of the indexing pass that last saw this row; stale rows are pruned after a full pass. */
    val seen: Long = 0,
)

@Entity(tableName = "media_positions")
data class MediaPositionEntity(
    @PrimaryKey val key: String,
    val positionMs: Long,
    val durationMs: Long,
    val extra: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "playlist_items", primaryKeys = ["playlistId", "position"], indices = [Index("playlistId")])
data class PlaylistItemEntity(
    val playlistId: Long,
    val position: Int,
    val uri: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
)

@Entity(tableName = "folder_prefs")
data class FolderPrefEntity(
    @PrimaryKey val path: String,
    val viewMode: String,
    val sortBy: String,
    val ascending: Boolean,
    val foldersFirst: Boolean,
    val itemSize: Int,
)

@Entity(tableName = "default_apps")
data class DefaultAppEntity(
    @PrimaryKey val extension: String,
    val packageName: String,
    val activityName: String,
)

@Entity(tableName = "tv_devices")
data class TvDeviceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val token: String,
    val lastSeen: Long = System.currentTimeMillis(),
)
