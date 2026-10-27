package com.xander.files

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.crossfade
import com.topjohnwu.superuser.Shell
import com.xander.files.data.thumbs.FileEntryFetcher
import com.xander.files.data.thumbs.FileEntryKeyer
import com.xander.files.data.thumbs.ThumbnailService
import com.xander.files.data.transfer.TransferWorker
import dagger.hilt.android.HiltAndroidApp
import com.xander.files.data.index.FileIndexer
import com.xander.files.data.index.MaintenanceWorker
import com.xander.files.data.storage.StorageAccess
import com.xander.files.di.AppScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class XanderApp : Application(), SingletonImageLoader.Factory, Configuration.Provider {

    @Inject lateinit var thumbnails: ThumbnailService
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var indexer: FileIndexer
    @Inject lateinit var nsfw: com.xander.files.data.nsfw.NsfwRepository
    @Inject @AppScope lateinit var appScope: CoroutineScope
    @Inject lateinit var appLock: com.xander.files.data.lock.AppLock
    @Inject lateinit var registry: com.xander.files.domain.fs.FileSystemRegistry

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(Shell.Builder.create().setFlags(Shell.FLAG_MOUNT_MASTER).setTimeout(10))
        TransferWorker.ensureChannel(this)
        MaintenanceWorker.schedule(this)
        appLock.install()
        // The full storage walk floods the storage layer; let the first screens and images load before it starts.
        startIndexing(delayMs = 20_000)
    }

    /** Indexes storage in the background once storage access exists (called again after onboarding). */
    fun startIndexing(delayMs: Long = 0) {
        if (StorageAccess.hasFullAccess(this)) appScope.launch {
            kotlinx.coroutines.delay(delayMs)
            runCatching { indexer.indexAll(full = true) }; runCatching { nsfw.scan() }
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(FileEntryKeyer())
                add(FileEntryFetcher.Factory(thumbnails))
                add(com.xander.files.data.thumbs.AppIconFetcher.Factory(this@XanderApp))
                add(com.xander.files.data.thumbs.FullImageKeyer())
                add(com.xander.files.data.thumbs.FullImageFetcher.Factory(registry))
                add(coil3.gif.AnimatedImageDecoder.Factory())
            }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.2).build() }
            .crossfade(true)
            .build()
}
