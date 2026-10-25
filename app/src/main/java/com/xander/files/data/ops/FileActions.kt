package com.xander.files.data.ops

import android.app.WallpaperManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.xander.files.MainActivity
import com.xander.files.R
import com.xander.files.data.open.FileOpener
import com.xander.files.data.transfer.CopyEngine
import com.xander.files.domain.fs.FileCategory
import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FileSystemRegistry
import com.xander.files.domain.fs.FsPath
import com.xander.files.domain.fs.MimeTypes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** Smaller file actions that are not long-running transfers. */
@Singleton
class FileActions @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: FileSystemRegistry,
    private val opener: FileOpener,
) {
    suspend fun shareIntent(entries: List<FileEntry>): Intent {
        val uris = ArrayList(entries.filter { !it.isDirectory }.map { opener.shareableUri(it.path) })
        val mime = entries.map { it.mimeType ?: "*/*" }.distinct().let { if (it.size == 1) it.first() else commonMime(it) }
        val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        i.type = mime
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = android.content.ClipData.newRawUri("", uris.first()).apply { uris.drop(1).forEach { addItem(android.content.ClipData.Item(it)) } }
        return Intent.createChooser(i, context.getString(R.string.share))
    }

    private fun commonMime(m: List<String>): String {
        val tops = m.map { it.substringBefore('/') }.distinct()
        return if (tops.size == 1) "${tops.first()}/*" else "*/*"
    }

    suspend fun duplicate(entry: FileEntry): FsPath = withContext(Dispatchers.IO) {
        val p = registry.provider(entry.path)
        val parent = entry.path.parent!!
        val name = CopyEngine.uniqueName(p, parent, entry.name)
        p.copyInternal(entry.path, parent, name, null) ?: run {
            if (entry.isDirectory) throw com.xander.files.domain.fs.FsException.Unsupported("duplicate folder here; use copy")
            p.openInput(entry.path).use { i -> p.openOutput(parent.child(name), entry.size).use { o -> i.copyTo(o, CopyEngine.BUFFER) } }
            parent.child(name)
        }
    }

    /** Hide = prefix with '.', unhide = strip leading dots. */
    suspend fun setHidden(entry: FileEntry, hidden: Boolean): FsPath {
        val p = registry.provider(entry.path)
        val newName = if (hidden) ".${entry.name}" else entry.name.trimStart('.').ifEmpty { entry.name }
        if (newName == entry.name) return entry.path
        return p.rename(entry.path, newName)
    }

    suspend fun checksum(path: FsPath, algorithm: String, onProgress: (Long) -> Unit = {}): String = withContext(Dispatchers.IO) {
        val md = MessageDigest.getInstance(algorithm)
        registry.provider(path).openInput(path).use { input ->
            val buf = ByteArray(1 shl 16); var total = 0L
            while (true) {
                coroutineContext.ensureActive()
                val n = input.read(buf); if (n < 0) break
                md.update(buf, 0, n); total += n; onProgress(total)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun setAsWallpaper(entry: FileEntry) = withContext(Dispatchers.IO) {
        val f = opener.materialize(entry.path)
        f.inputStream().use { WallpaperManager.getInstance(context).setStream(it) }
    }

    /** Intent that lets the user pick lock/home/both in the system wallpaper UI. */
    suspend fun setAsIntent(entry: FileEntry): Intent {
        val uri = opener.shareableUri(entry.path)
        return Intent.createChooser(
            Intent(Intent.ACTION_ATTACH_DATA).setDataAndType(uri, entry.mimeType ?: MimeTypes.fromName(entry.name))
                .putExtra("mimeType", entry.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            context.getString(R.string.set_as)
        )
    }

    fun canWriteSettings(): Boolean = Build.VERSION.SDK_INT < 23 || Settings.System.canWrite(context)

    fun writeSettingsIntent(): Intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))

    /** Copies the audio into MediaStore (required for ringtones on Android 10+) and sets it as default. */
    suspend fun setAsRingtone(entry: FileEntry, type: Int = RingtoneManager.TYPE_RINGTONE) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, entry.name)
            put(MediaStore.MediaColumns.MIME_TYPE, entry.mimeType ?: "audio/mpeg")
            put(MediaStore.Audio.Media.IS_RINGTONE, type == RingtoneManager.TYPE_RINGTONE)
            put(MediaStore.Audio.Media.IS_NOTIFICATION, type == RingtoneManager.TYPE_NOTIFICATION)
            put(MediaStore.Audio.Media.IS_ALARM, type == RingtoneManager.TYPE_ALARM)
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, "Ringtones/")
        }
        val collection = if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val uri = context.contentResolver.insert(collection, values) ?: error("MediaStore insert failed")
        context.contentResolver.openOutputStream(uri)!!.use { out -> registry.provider(entry.path).openInput(entry.path).use { it.copyTo(out) } }
        RingtoneManager.setActualDefaultRingtoneUri(context, type, uri)
    }

    fun supportsPinnedShortcuts() = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /** Home-screen shortcut opening the folder in the browser, or the file with its viewer. */
    fun createShortcut(entry: FileEntry): Boolean {
        val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
            .putExtra(MainActivity.EXTRA_PATH, entry.path.toString())
            .putExtra(MainActivity.EXTRA_OPEN_FILE, !entry.isDirectory)
        val icon = IconCompat.createWithResource(context, when (entry.category) {
            FileCategory.FOLDER -> R.drawable.ic_shortcut_folder
            else -> R.drawable.ic_shortcut_file
        })
        val info = ShortcutInfoCompat.Builder(context, "path:" + entry.path.toString().hashCode())
            .setShortLabel(entry.name.take(24)).setLongLabel(entry.name).setIcon(icon).setIntent(intent).build()
        return ShortcutManagerCompat.requestPinShortcut(context, info, null)
    }
}
