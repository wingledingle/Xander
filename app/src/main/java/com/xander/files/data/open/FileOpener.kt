package com.xander.files.data.open

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.xander.files.data.db.DefaultAppDao
import com.xander.files.data.db.DefaultAppEntity
import com.xander.files.data.fs.saf.SafFileSystemProvider
import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FileSystemRegistry
import com.xander.files.domain.fs.FsPath
import com.xander.files.domain.fs.MimeTypes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Turns any FsPath into a content:// URI other apps can read (copying to cache for non-local backends). */
@Singleton
class FileOpener @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: FileSystemRegistry,
    private val saf: SafFileSystemProvider,
    private val defaults: DefaultAppDao,
) {
    val authority = "${context.packageName}.files"

    suspend fun shareableUri(path: FsPath): Uri = withContext(Dispatchers.IO) {
        when (path.scheme) {
            FsPath.SCHEME_FILE -> {
                val f = File(path.path)
                if (f.canRead()) FileProvider.getUriForFile(context, authority, f) else cacheCopy(path)
            }
            FsPath.SCHEME_SAF -> saf.contentUri(path)
            else -> cacheCopy(path)
        }
    }

    /** Materialises a remote/privileged file into the app cache and returns a FileProvider URI. */
    suspend fun cacheCopy(path: FsPath): Uri = withContext(Dispatchers.IO) {
        val f = materialize(path)
        FileProvider.getUriForFile(context, authority, f)
    }

    suspend fun materialize(path: FsPath): File = withContext(Dispatchers.IO) {
        if (path.scheme == FsPath.SCHEME_FILE && File(path.path).canRead()) return@withContext File(path.path)
        val dir = File(context.cacheDir, "open/${path.toString().hashCode().toUInt()}").apply { mkdirs() }
        val out = File(dir, path.name.ifEmpty { "file" })
        val provider = registry.provider(path)
        val st = provider.stat(path)
        if (!(out.exists() && out.length() == st.size && out.lastModified() >= st.lastModified)) {
            provider.openInput(path).use { input -> out.outputStream().use { input.copyTo(it, 256 * 1024) } }
        }
        out
    }

    suspend fun viewIntent(entry: FileEntry, mimeOverride: String? = null): Intent {
        val uri = shareableUri(entry.path)
        val mime = mimeOverride ?: entry.mimeType ?: MimeTypes.fromName(entry.name)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Opens with the remembered default app if any, otherwise returns the plain intent for a chooser. */
    suspend fun openExternal(entry: FileEntry): Intent {
        val intent = viewIntent(entry)
        defaults.get(entry.extension)?.let { d ->
            val explicit = Intent(intent).setComponent(ComponentName(d.packageName, d.activityName))
            if (context.packageManager.resolveActivity(explicit, 0) != null) return explicit
            defaults.delete(entry.extension)
        }
        return intent
    }

    suspend fun rememberDefault(extension: String, component: ComponentName) =
        defaults.upsert(DefaultAppEntity(extension, component.packageName, component.className))

    suspend fun clearDefaults() = defaults.clear()

    fun safeStart(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (e: ActivityNotFoundException) { false }
}
