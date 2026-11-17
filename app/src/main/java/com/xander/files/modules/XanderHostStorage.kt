package com.xander.files.modules

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSource
import com.xander.files.data.media.FsUri
import com.xander.files.data.media.RoutingDataSourceFactory
import com.xander.files.domain.fs.FileCategory
import com.xander.files.domain.fs.FileSystemRegistry
import com.xander.files.domain.fs.FsPath
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.InputStream

/** Xander's storage for web modules: every backend the file manager has (SMB, cloud, ...) and its shared index. */
class XanderHostStorage(private val context: Context, private val registry: FileSystemRegistry) : HostStorage {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps { fun registry(): FileSystemRegistry }

    override suspend fun stat(path: String): HostStorage.Info {
        val p = FsPath.parse(path)
        val e = registry.provider(p).stat(p)
        return HostStorage.Info(e.name, e.mimeType, e.size)
    }

    override fun open(path: String, offset: Long): InputStream = FsPath.parse(path).let { p -> runBlocking { registry.provider(p).openInput(p, offset) } }

    override fun localFile(path: String): String? = FsPath.parse(path).takeIf { it.isLocal && File(it.path).canRead() }?.path

    override fun mediaUri(path: String): Uri = FsUri.of(FsPath.parse(path))

    override fun dataSources(context: Context): DataSource.Factory = RoutingDataSourceFactory(context, registry)

    override fun mediaIndex(kind: String): List<HostStorage.Media> =
        SharedIndex.byCategory(context, if (kind == "audio") FileCategory.AUDIO else FileCategory.VIDEO)
            .map { HostStorage.Media(it.path, it.name, it.size, it.modified) }

    companion object {
        fun from(c: Context) = XanderHostStorage(c.applicationContext, EntryPointAccessors.fromApplication(c.applicationContext, Deps::class.java).registry())
    }
}
