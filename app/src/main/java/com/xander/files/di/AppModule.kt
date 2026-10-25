package com.xander.files.di

import android.content.Context
import androidx.room.Room
import com.xander.files.data.db.AppDatabase
import com.xander.files.data.fs.local.LocalFileSystemProvider
import com.xander.files.data.fs.root.RootFileSystemProvider
import com.xander.files.data.fs.saf.SafFileSystemProvider
import com.xander.files.data.fs.shizuku.ShizukuFileSystemProvider
import com.xander.files.data.security.KeystoreSecretCipher
import com.xander.files.data.security.SecretCipher
import com.xander.files.domain.fs.FileSystemProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class AppScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun db(@ApplicationContext c: Context): AppDatabase =
        Room.databaseBuilder(c, AppDatabase::class.java, "xander.db").fallbackToDestructiveMigrationOnDowngrade(true).build()

    @Provides fun safRoots(db: AppDatabase) = db.safRoots()
    @Provides fun bookmarks(db: AppDatabase) = db.bookmarks()
    @Provides fun recents(db: AppDatabase) = db.recents()
    @Provides fun history(db: AppDatabase) = db.history()
    @Provides fun recycle(db: AppDatabase) = db.recycle()
    @Provides fun clipboard(db: AppDatabase) = db.clipboard()
    @Provides fun connections(db: AppDatabase) = db.connections()
    @Provides fun cloudAccounts(db: AppDatabase) = db.cloudAccounts()
    @Provides fun transfers(db: AppDatabase) = db.transfers()
    @Provides fun fileIndex(db: AppDatabase) = db.fileIndex()
    @Provides fun mediaPositions(db: AppDatabase) = db.mediaPositions()
    @Provides fun playlists(db: AppDatabase) = db.playlists()
    @Provides fun folderPrefs(db: AppDatabase) = db.folderPrefs()
    @Provides fun defaultApps(db: AppDatabase) = db.defaultApps()
    @Provides fun tvDevices(db: AppDatabase) = db.tvDevices()

    @Provides @Singleton @AppScope
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ProviderModule {
    @Binds @IntoSet abstract fun local(p: LocalFileSystemProvider): FileSystemProvider
    @Binds @IntoSet abstract fun root(p: RootFileSystemProvider): FileSystemProvider
    @Binds @IntoSet abstract fun saf(p: SafFileSystemProvider): FileSystemProvider
    @Binds @IntoSet abstract fun shizuku(p: ShizukuFileSystemProvider): FileSystemProvider
    @Binds @IntoSet abstract fun archive(p: com.xander.files.data.archive.ArchiveFileSystemProvider): FileSystemProvider
    @Binds abstract fun secretCipher(c: KeystoreSecretCipher): SecretCipher
}
