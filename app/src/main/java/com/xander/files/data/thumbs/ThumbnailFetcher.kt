package com.xander.files.data.thumbs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.graphics.drawable.toBitmap
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import coil3.size.pxOrElse
import com.xander.files.domain.fs.FileCategory
import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FileSystemRegistry
import com.xander.files.domain.fs.FsPath
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Produces thumbnails for any backend: images (downsampled), video frames, embedded audio art,
 * APK icons and the first page of PDFs. Non-local files are first fetched into a size-capped disk cache.
 */
@Singleton
class ThumbnailService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: FileSystemRegistry,
) {
    private val cacheDir = File(context.cacheDir, "thumb_src").apply { mkdirs() }
    private val remoteLimit = Semaphore(3)

    suspend fun load(entry: FileEntry, sizePx: Int): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val provider = registry.provider(entry.path)
            // Backends with native thumbnails (cloud drives) answer first.
            if (entry.path.scheme != FsPath.SCHEME_FILE) provider.thumbnail(entry, sizePx)?.let { return@withContext it }
            val file = localFile(entry) ?: return@withContext null
            when (entry.category) {
                FileCategory.IMAGE -> decodeImage(file, sizePx)
                FileCategory.VIDEO -> videoFrame(file, sizePx)
                FileCategory.AUDIO -> audioArt(file, sizePx)
                FileCategory.APK -> apkIcon(file)
                FileCategory.PDF -> pdfPage(file, sizePx)
                else -> null
            }
        } catch (e: Throwable) {
            null
        }
    }

    /** Local readable file, or a cached copy for privileged/remote backends (skipped for very large files). */
    private suspend fun localFile(entry: FileEntry): File? {
        if (entry.path.scheme == FsPath.SCHEME_FILE) {
            val f = File(entry.path.path)
            if (f.canRead()) return f
        }
        val limit = when (entry.category) { FileCategory.VIDEO -> 0L; FileCategory.APK -> 200L shl 20; else -> 30L shl 20 }
        if (entry.size > limit) return null
        val key = MessageDigest.getInstance("MD5").digest("${entry.path}|${entry.lastModified}|${entry.size}".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val out = File(cacheDir, key + "." + entry.extension)
        if (out.exists() && out.length() == entry.size) return out
        remoteLimit.withPermit {
            registry.provider(entry.path).openInput(entry.path).use { i -> out.outputStream().use { i.copyTo(it) } }
        }
        trimCache()
        return out
    }

    private fun trimCache() {
        val files = cacheDir.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total < 200L shl 20) return
        files.sortedBy { it.lastModified() }.forEach { if (total > 150L shl 20) { total -= it.length(); it.delete() } }
    }

    private fun decodeImage(f: File, size: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= size && o.outHeight / (sample * 2) >= size) sample *= 2
        return BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun videoFrame(f: File, size: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.absolutePath)
            if (Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, size, size)
            else r.getFrameAtTime(1_000_000)
        } finally { runCatching { r.release() } }
    }

    private fun audioArt(f: File, size: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.absolutePath)
            val pic = r.embeddedPicture ?: return null
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(pic, 0, pic.size, o)
            var sample = 1
            while (o.outWidth / (sample * 2) >= size) sample *= 2
            BitmapFactory.decodeByteArray(pic, 0, pic.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } finally { runCatching { r.release() } }
    }

    private fun apkIcon(f: File): Bitmap? {
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(f.absolutePath, 0) ?: return null
        val app = info.applicationInfo ?: return null
        app.sourceDir = f.absolutePath; app.publicSourceDir = f.absolutePath
        return app.loadIcon(pm).toBitmap(192, 192)
    }

    private fun pdfPage(f: File, size: Int): Bitmap? =
        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { r ->
                if (r.pageCount == 0) return null
                r.openPage(0).use { page ->
                    val scale = size.toFloat() / maxOf(page.width, page.height)
                    val bmp = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }
        }
}

/** Coil fetcher so `AsyncImage(model = fileEntry)` works for every backend. */
class FileEntryFetcher(private val entry: FileEntry, private val options: Options, private val service: ThumbnailService) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val px = options.size.width.pxOrElse { 256 }.coerceIn(64, 1024)
        val bmp = service.load(entry, px) ?: return null
        return ImageFetchResult(bmp.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

    class Factory(private val service: ThumbnailService) : Fetcher.Factory<FileEntry> {
        override fun create(data: FileEntry, options: Options, imageLoader: ImageLoader): Fetcher = FileEntryFetcher(data, options, service)
    }
}

/** Memory-cache key for FileEntry models. */
class FileEntryKeyer : coil3.key.Keyer<FileEntry> {
    override fun key(data: FileEntry, options: Options) = "${data.path}|${data.lastModified}|${data.size}"
}

/** Coil model for an installed app's launcher icon. */
data class AppIconModel(val packageName: String)

class AppIconFetcher(private val model: AppIconModel, private val context: android.content.Context) : Fetcher {
    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        val d = runCatching { context.packageManager.getApplicationIcon(model.packageName) }.getOrNull() ?: return@withContext null
        ImageFetchResult(d.toBitmap(144, 144).asImage(), isSampled = false, dataSource = DataSource.DISK)
    }

    class Factory(private val context: android.content.Context) : Fetcher.Factory<AppIconModel> {
        override fun create(data: AppIconModel, options: Options, imageLoader: ImageLoader): Fetcher = AppIconFetcher(data, context)
    }
}
