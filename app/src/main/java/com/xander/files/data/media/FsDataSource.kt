package com.xander.files.data.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.xander.files.domain.fs.FileSystemRegistry
import com.xander.files.domain.fs.FsPath
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.InputStream

/** URIs of the form xfs://<encoded FsPath> let Media3 play files from any backend (SMB, SFTP, cloud, archives...). */
object FsUri {
    const val SCHEME = "xfs"
    fun of(path: FsPath): Uri = Uri.Builder().scheme(SCHEME).authority("f").appendQueryParameter("p", path.toString()).build()
    fun parse(uri: Uri): FsPath? = uri.getQueryParameter("p")?.let(FsPath::parse)
}

@UnstableApi
class FsDataSource(private val registry: FileSystemRegistry) : BaseDataSource(true) {
    private var input: InputStream? = null
    private var uri: Uri? = null
    private var remaining = C.LENGTH_UNSET.toLong()

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val path = FsUri.parse(dataSpec.uri) ?: throw IOException("Bad media URI")
        val provider = registry.provider(path)
        val size = runBlocking { runCatching { provider.stat(path).size }.getOrDefault(-1L) }
        input = runBlocking { provider.openInput(path, dataSpec.position) }
        remaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            size > 0 -> size - dataSpec.position
            else -> C.LENGTH_UNSET.toLong()
        }
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val toRead = if (remaining == C.LENGTH_UNSET.toLong()) length else minOf(remaining, length.toLong()).toInt()
        val n = input!!.read(buffer, offset, toRead)
        if (n < 0) return C.RESULT_END_OF_INPUT
        if (remaining != C.LENGTH_UNSET.toLong()) remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try { input?.close() } finally {
            input = null
            if (uri != null) { uri = null; transferEnded() }
        }
    }

    class Factory(private val registry: FileSystemRegistry) : DataSource.Factory {
        override fun createDataSource(): DataSource = FsDataSource(registry)
    }
}

/** Routes xfs:// to [FsDataSource] and everything else (http, content, file) to the default factory. */
@UnstableApi
class RoutingDataSourceFactory(private val context: android.content.Context, private val registry: FileSystemRegistry) : DataSource.Factory {
    private val default = androidx.media3.datasource.DefaultDataSource.Factory(context)
    override fun createDataSource(): DataSource = object : DataSource {
        private var current: DataSource? = null
        private val listeners = ArrayList<androidx.media3.datasource.TransferListener>()
        override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) { listeners += transferListener }
        override fun open(dataSpec: DataSpec): Long {
            val ds = if (dataSpec.uri.scheme == FsUri.SCHEME) FsDataSource(registry) else default.createDataSource()
            listeners.forEach { ds.addTransferListener(it) }
            current = ds
            return ds.open(dataSpec)
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int) = current!!.read(buffer, offset, length)
        override fun getUri() = current?.uri
        override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()
        override fun close() { current?.close(); current = null }
    }
}
