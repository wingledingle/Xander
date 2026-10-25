package com.xander.files.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        SafRootEntity::class, BookmarkEntity::class, RecentFileEntity::class, HistoryEntity::class,
        RecycleEntity::class, ClipboardEntity::class, ConnectionEntity::class, CloudAccountEntity::class,
        TransferEntity::class, FileIndexEntity::class, MediaPositionEntity::class, PlaylistEntity::class,
        PlaylistItemEntity::class, FolderPrefEntity::class, DefaultAppEntity::class, TvDeviceEntity::class,
        NsfwScoreEntity::class,
    ],
    version = 3,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3, spec = AppDatabase.DropMarqoScores::class)],
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    /** v3 replaced the Marqo NSFW scores (which mis-flagged drawings) with GantMan class probabilities. */
    @androidx.room.DeleteTable(tableName = "nsfw_scores")
    class DropMarqoScores : androidx.room.migration.AutoMigrationSpec

    abstract fun safRoots(): SafRootDao
    abstract fun bookmarks(): BookmarkDao
    abstract fun recents(): RecentDao
    abstract fun history(): HistoryDao
    abstract fun recycle(): RecycleDao
    abstract fun clipboard(): ClipboardDao
    abstract fun connections(): ConnectionDao
    abstract fun cloudAccounts(): CloudAccountDao
    abstract fun transfers(): TransferDao
    abstract fun fileIndex(): FileIndexDao
    abstract fun mediaPositions(): MediaPositionDao
    abstract fun playlists(): PlaylistDao
    abstract fun folderPrefs(): FolderPrefDao
    abstract fun defaultApps(): DefaultAppDao
    abstract fun tvDevices(): TvDeviceDao
    abstract fun nsfw(): NsfwDao
}
