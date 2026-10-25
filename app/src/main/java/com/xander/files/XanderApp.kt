package com.xander.files

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.crossfade
import com.topjohnwu.superuser.Shell
import com.xander.files.data.thumbs.FileEntryFetcher
import com.xander.files.data.thumbs.FileEntryKeyer
import com.xander.files.data.thumbs.ThumbnailService
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class XanderApp : Application(), SingletonImageLoader.Factory {

    @Inject lateinit var thumbnails: ThumbnailService

    override fun onCreate() {
        super.onCreate()
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(Shell.Builder.create().setFlags(Shell.FLAG_MOUNT_MASTER).setTimeout(10))
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(FileEntryKeyer())
                add(FileEntryFetcher.Factory(thumbnails))
            }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.2).build() }
            .crossfade(true)
            .build()
}
