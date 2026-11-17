package dev.groovesmith.studio

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import com.xander.files.modules.HostStorage
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/** Plain phone storage for the standalone app: file paths, content:// URIs and MediaStore for "On phone". */
class LocalHostStorage(private val context: Context) : HostStorage {
    private fun uri(path: String): Uri = if (path.contains("://")) Uri.parse(path) else Uri.fromFile(File(path))

    override suspend fun stat(path: String): HostStorage.Info {
        val u = uri(path)
        if (u.scheme == "file") File(u.path!!).let { return HostStorage.Info(it.name, null, it.length()) }
        var name = u.lastPathSegment?.substringAfterLast('/') ?: "file"
        var size = -1L
        context.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) { c.getString(0)?.let { name = it }; if (!c.isNull(1)) size = c.getLong(1) }
        }
        return HostStorage.Info(name, context.contentResolver.getType(u), size)
    }

    override fun open(path: String, offset: Long): InputStream {
        val u = uri(path)
        if (u.scheme == "file") return FileInputStream(u.path!!).also { it.channel.position(offset) }
        val input = context.contentResolver.openInputStream(u) ?: error("Cannot open $path")
        var left = offset
        while (left > 0) { val s = input.skip(left); if (s <= 0) { if (input.read() < 0) break; left-- } else left -= s }
        return input
    }

    override fun localFile(path: String): String? = uri(path).takeIf { it.scheme == "file" }?.path?.takeIf { File(it).canRead() }

    override fun mediaUri(path: String): Uri = uri(path)

    override fun dataSources(context: Context): DataSource.Factory = DefaultDataSource.Factory(context)

    override fun mediaIndex(kind: String): List<HostStorage.Media> {
        val table = if (kind == "audio") MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val cols = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED)
        val out = ArrayList<HostStorage.Media>()
        context.contentResolver.query(table, cols, null, null, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { c ->
            while (c.moveToNext()) out += HostStorage.Media(ContentUris.withAppendedId(table, c.getLong(0)).toString(),
                c.getString(1) ?: "", c.getLong(2), c.getLong(3) * 1000)
        }
        return out
    }
}
