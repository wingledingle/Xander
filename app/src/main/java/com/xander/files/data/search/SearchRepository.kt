package com.xander.files.data.search

import android.os.Build
import com.xander.files.data.fs.root.RootFileSystemProvider
import com.xander.files.data.fs.shizuku.ShizukuFileSystemProvider
import com.xander.files.data.settings.SettingsRepository
import com.xander.files.data.storage.StorageVolumes
import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FileSystemRegistry
import com.xander.files.domain.fs.FsException
import com.xander.files.domain.fs.FsPath
import com.xander.files.domain.fs.SearchQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

data class SearchScope(
    /** null = everywhere the app can reach (all volumes + restricted folders + system when root). */
    val root: FsPath? = null,
)

/** Which privileged areas the last global search could cover, for user-facing hints. */
data class SearchCoverage(val restrictedFolders: Boolean, val systemFolders: Boolean)

@Singleton
class SearchRepository @Inject constructor(
    private val registry: FileSystemRegistry,
    private val volumes: StorageVolumes,
    private val shizuku: ShizukuFileSystemProvider,
    private val root: RootFileSystemProvider,
    private val settings: SettingsRepository,
) {
    suspend fun coverage(): SearchCoverage {
        val rootOn = settings.settings.first().rootMode && root.isAvailable()
        val restricted = Build.VERSION.SDK_INT < 30 || shizuku.isAvailable() || rootOn
        return SearchCoverage(restricted, rootOn)
    }

    fun search(query: SearchQuery, scope: SearchScope): Flow<FileEntry> = channelFlow {
        val target = scope.root
        if (target != null) {
            registry.provider(target).search(target, query).catch { }.collect { send(it) }
            return@channelFlow
        }
        val rootOn = settings.settings.first().rootMode && root.isAvailable()
        val primary = volumes.primaryRoot
        val restricted = if (Build.VERSION.SDK_INT >= 30) listOf("$primary/Android/data", "$primary/Android/obb") else emptyList()

        // 1. All mounted volumes, walked with the local provider, skipping restricted subtrees (searched separately).
        for (v in volumes.volumes()) {
            launch(Dispatchers.IO) {
                walkLocal(v.root, query, skip = restricted.toSet()) { send(it) }
            }
        }
        // 2. Android/data + Android/obb through the fastest privileged route available.
        if (restricted.isNotEmpty()) {
            for (r in restricted) launch(Dispatchers.IO) {
                val local = FsPath.local(r)
                val flow = when {
                    shizuku.isAvailable() -> shizuku.search(local.copy(scheme = FsPath.SCHEME_SHIZUKU), query)
                    rootOn -> root.search(local.copy(scheme = FsPath.SCHEME_ROOT), query)
                    else -> null
                }
                flow?.catch { }?.collect { e -> send(e.copy(path = e.path.copy(scheme = FsPath.SCHEME_FILE))) }
                    ?: walkLocal(local, query, emptySet()) { send(it) } // SAF grant on Android 11/12
            }
        }
        // 3. System partitions when root mode is on.
        if (rootOn) {
            for (sys in SYSTEM_ROOTS) launch(Dispatchers.IO) {
                root.search(FsPath(FsPath.SCHEME_ROOT, "", sys), query).catch { }.collect { send(it) }
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun walkLocal(start: FsPath, query: SearchQuery, skip: Set<String>, emit: suspend (FileEntry) -> Unit) {
        val provider = registry.provider(start)
        val stack = ArrayDeque<FsPath>().apply { add(start) }
        while (stack.isNotEmpty()) {
            coroutineContext.ensureActive()
            val dir = stack.removeLast()
            val children = try { provider.list(dir) } catch (e: FsException) { continue } catch (e: Exception) { continue }
            for (c in children) {
                if (query.matches(c)) emit(c)
                if (c.isDirectory && !c.isSymlink && (query.includeHidden || !c.isHidden) && c.path.path !in skip) stack.add(c.path)
            }
        }
    }

    companion object {
        /** Root-only system locations searched in addition to user storage (shared storage is covered above). */
        val SYSTEM_ROOTS = listOf("/system", "/data", "/vendor", "/product", "/system_ext", "/odm", "/apex", "/cache", "/metadata", "/oem")
    }
}
