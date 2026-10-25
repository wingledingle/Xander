package com.xander.files.data.analyzer

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.xander.files.data.storage.StorageVolumes
import com.xander.files.domain.fs.FileCategory
import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FileSystemRegistry
import com.xander.files.domain.fs.FsPath
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.PriorityQueue
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import com.xander.files.domain.fs.measure

data class CategoryUsage(val category: FileCategory, val count: Int, val size: Long)
data class FolderNode(val path: FsPath, val size: Long, val files: Int, val children: List<FsPath>)
data class DuplicateGroup(val hash: String, val size: Long, val files: List<FileEntry>) { val wasted get() = size * (files.size - 1) }
enum class JunkKind { APP_CACHE, THUMBNAILS, TEMP, LOG, EMPTY_FILE, LOST_DIR, RESIDUAL }
data class JunkItem(val entry: FileEntry, val kind: JunkKind, val size: Long, val note: String? = null)
data class OldApk(val entry: FileEntry, val packageName: String, val versionName: String, val reason: String)

data class AnalysisResult(
    val root: FsPath,
    val totalBytes: Long,
    val files: Int,
    val folders: Int,
    val volumeTotal: Long,
    val volumeFree: Long,
    val categories: List<CategoryUsage>,
    val largest: List<FileEntry>,
    val folderTree: Map<FsPath, FolderNode>,
    val recent: List<FileEntry>,
    val duplicates: List<DuplicateGroup>,
    val emptyFolders: List<FileEntry>,
    val junk: List<JunkItem>,
    val oldApks: List<OldApk>,
    val finishedAt: Long = System.currentTimeMillis(),
) {
    fun largestFolders(n: Int = 100) = folderTree.values.filter { it.path != root }.sortedByDescending { it.size }.take(n)
}

data class AnalyzerProgress(val phase: String, val files: Int, val bytes: Long, val current: String?)

/** Walks a location once and derives every analysis view from that pass (plus hashing for duplicates). */
@Singleton
class StorageAnalyzer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: FileSystemRegistry,
    private val volumes: StorageVolumes,
) {
    private val _progress = MutableStateFlow<AnalyzerProgress?>(null)
    val progress: StateFlow<AnalyzerProgress?> = _progress
    @Volatile var last: AnalysisResult? = null
        private set

    suspend fun analyze(root: FsPath): AnalysisResult = withContext(Dispatchers.IO) {
        val provider = registry.provider(root)
        val cats = HashMap<FileCategory, LongArray>()
        val largest = PriorityQueue<FileEntry>(compareBy { it.size })
        val recent = ArrayList<FileEntry>()
        val since = System.currentTimeMillis() - 7 * 86_400_000L
        val bySize = HashMap<Long, MutableList<FileEntry>>()
        val empty = ArrayList<FileEntry>()
        val junk = ArrayList<JunkItem>()
        val apks = ArrayList<FileEntry>()
        val sizes = HashMap<FsPath, Long>()
        val counts = HashMap<FsPath, Int>()
        val kids = HashMap<FsPath, MutableList<FsPath>>()
        var files = 0; var folders = 0; var total = 0L
        val primary = volumes.primaryRoot

        // Iterative post-order walk so folder sizes roll up to parents.
        data class Frame(val dir: FsPath, val children: List<FileEntry>)
        val stack = ArrayDeque<Frame>()
        fun listOf(p: FsPath): List<FileEntry> = runCatching { kotlinx.coroutines.runBlocking { provider.list(p) } }.getOrDefault(emptyList())
        stack.add(Frame(root, listOf(root)))
        val visitedChildren = HashSet<FsPath>()
        while (stack.isNotEmpty()) {
            coroutineContext.ensureActive()
            val top = stack.last()
            if (visitedChildren.add(top.dir)) {
                var dirSize = 0L; var dirFiles = 0
                val subdirs = ArrayList<FsPath>()
                for (c in top.children) {
                    if (c.isDirectory) {
                        if (c.isSymlink || c.name == ".XanderRecycle") continue
                        subdirs += c.path
                    } else {
                        files++; dirFiles++; dirSize += c.size; total += c.size
                        val cat = c.category
                        cats.getOrPut(cat) { LongArray(2) }.let { it[0]++; it[1] += c.size }
                        largest.add(c); if (largest.size > 200) largest.poll()
                        if (c.lastModified >= since) recent += c
                        if (c.size >= 1024) bySize.getOrPut(c.size) { ArrayList(2) }.add(c)
                        if (cat == FileCategory.APK) apks += c
                        classifyJunk(c, primary)?.let { junk += it }
                        if (files % 500 == 0) _progress.value = AnalyzerProgress("scan", files, total, top.dir.path)
                    }
                }
                sizes[top.dir] = dirSize; counts[top.dir] = dirFiles
                kids[top.dir] = subdirs
                if (top.children.isEmpty() && top.dir != root) empty += FileEntry(top.dir, true)
                for (s in subdirs.asReversed()) { folders++; stack.add(Frame(s, listOf(s))) }
                continue
            }
            stack.removeLast()
            // Children are complete: add their totals to this folder.
            kids[top.dir]?.forEach { k -> sizes[top.dir] = (sizes[top.dir] ?: 0) + (sizes[k] ?: 0); counts[top.dir] = (counts[top.dir] ?: 0) + (counts[k] ?: 0) }
        }

        if (root.isLocal) {
            junk += runCatching { residuals(provider, primary) }.getOrDefault(emptyList())
            junk += runCatching { appCaches(provider, primary) }.getOrDefault(emptyList())
        }
        val tree = sizes.keys.associateWith { FolderNode(it, sizes[it] ?: 0, counts[it] ?: 0, kids[it].orEmpty().sortedByDescending { k -> sizes[k] ?: 0 }) }
        val dups = duplicates(provider, bySize)
        val space = provider.spaceInfo(root)
        val result = AnalysisResult(
            root = root, totalBytes = total, files = files, folders = folders,
            volumeTotal = space?.total ?: 0, volumeFree = space?.free ?: 0,
            categories = cats.map { (c, v) -> CategoryUsage(c, v[0].toInt(), v[1]) }.sortedByDescending { it.size },
            largest = largest.toList().sortedByDescending { it.size },
            folderTree = tree,
            recent = recent.sortedByDescending { it.lastModified }.take(300),
            duplicates = dups,
            emptyFolders = empty.sortedBy { it.path.path },
            junk = junk.distinctBy { it.entry.path }.sortedByDescending { it.size },
            oldApks = oldApks(apks),
        )
        _progress.value = null
        last = result
        result
    }

    private fun classifyJunk(e: FileEntry, primary: String): JunkItem? {
        val p = e.path.path; val n = e.name.lowercase()
        return when {
            p.contains("/.thumbnails/") -> JunkItem(e, JunkKind.THUMBNAILS, e.size)
            p.contains("/LOST.DIR/") -> JunkItem(e, JunkKind.LOST_DIR, e.size)
            n.endsWith(".tmp") || n.endsWith(".temp") || n.endsWith(".part") || n.endsWith(".crdownload") || n == ".ds_store" || n == "thumbs.db" -> JunkItem(e, JunkKind.TEMP, e.size)
            n.endsWith(".log") || n.endsWith(".log.1") || n.endsWith(".trace") -> JunkItem(e, JunkKind.LOG, e.size)
            e.size == 0L && !n.startsWith(".") && n != ".nomedia" -> JunkItem(e, JunkKind.EMPTY_FILE, 0)
            else -> null
        }
    }

    private fun installed(): Set<String> = runCatching {
        context.packageManager.getInstalledApplications(PackageManager.GET_META_DATA).map { it.packageName }.toSet()
    }.getOrDefault(emptySet())

    /** Android/data and Android/obb folders of apps that are no longer installed. */
    private suspend fun residuals(provider: com.xander.files.domain.fs.FileSystemProvider, primary: String): List<JunkItem> {
        val pkgs = installed()
        if (pkgs.isEmpty()) return emptyList()
        val out = ArrayList<JunkItem>()
        for (sub in listOf("Android/data", "Android/obb", "Android/media")) {
            val dir = FsPath.local("$primary/$sub")
            val p = registry.provider(dir)
            val children = runCatching { p.list(dir) }.getOrDefault(emptyList())
            for (c in children) if (c.isDirectory && c.name.contains('.') && c.name !in pkgs) {
                val size = runCatching { p.measure(c.path).first }.getOrDefault(0L)
                out += JunkItem(c, JunkKind.RESIDUAL, size, c.name)
            }
        }
        return out
    }

    /** Per-app cache folders under Android/data/<pkg>/cache (needs Shizuku/root/SAF on Android 11+). */
    private suspend fun appCaches(provider: com.xander.files.domain.fs.FileSystemProvider, primary: String): List<JunkItem> {
        val dir = FsPath.local("$primary/Android/data")
        val p = registry.provider(dir)
        val apps = runCatching { p.list(dir) }.getOrDefault(emptyList())
        return apps.filter { it.isDirectory }.mapNotNull { app ->
            val cache = app.path.child("cache")
            val size = runCatching { p.measure(cache).first }.getOrNull() ?: return@mapNotNull null
            if (size <= 0) null else JunkItem(FileEntry(cache, true, size), JunkKind.APP_CACHE, size, app.name)
        }
    }

    private suspend fun duplicates(provider: com.xander.files.domain.fs.FileSystemProvider, bySize: Map<Long, List<FileEntry>>): List<DuplicateGroup> {
        val candidates = bySize.values.filter { it.size > 1 }
        val out = ArrayList<DuplicateGroup>()
        var done = 0
        for (group in candidates) {
            coroutineContext.ensureActive()
            // Cheap partial hash first, full hash only for matching heads.
            val byHead = group.groupBy { e -> runCatching { hash(provider, e.path, 64 * 1024) }.getOrNull() }.filterKeys { it != null }.values.filter { it.size > 1 }
            for (h in byHead) {
                val byFull = h.groupBy { e -> runCatching { if (e.size <= 64 * 1024) "s" + hash(provider, e.path, 64 * 1024) else hash(provider, e.path, Long.MAX_VALUE) }.getOrNull() }
                byFull.forEach { (k, v) -> if (k != null && v.size > 1) out += DuplicateGroup(k, v.first().size, v.sortedBy { it.path.path }) }
            }
            done++
            if (done % 20 == 0) _progress.value = AnalyzerProgress("duplicates", done, candidates.size.toLong(), group.first().name)
        }
        return out.sortedByDescending { it.wasted }
    }

    private suspend fun hash(provider: com.xander.files.domain.fs.FileSystemProvider, path: FsPath, limit: Long): String {
        val md = MessageDigest.getInstance("SHA-1")
        provider.openInput(path).use { s ->
            val buf = ByteArray(64 * 1024); var left = limit
            while (left > 0) { val n = s.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) break; md.update(buf, 0, n); left -= n }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** APK files that are already installed in the same or a newer version, or older copies of the same package. */
    private fun oldApks(apks: List<FileEntry>): List<OldApk> {
        val pm = context.packageManager
        data class Info(val e: FileEntry, val pkg: String, val code: Long, val name: String)
        val infos = apks.filter { it.path.isLocal }.mapNotNull { e ->
            val pi = runCatching { pm.getPackageArchiveInfo(e.path.path, 0) }.getOrNull() ?: return@mapNotNull null
            val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
            Info(e, pi.packageName, code, pi.versionName ?: "")
        }
        val out = ArrayList<OldApk>()
        for (i in infos) {
            val inst = runCatching { pm.getPackageInfo(i.pkg, 0) }.getOrNull()
            val instCode = inst?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong() }
            when {
                instCode != null && instCode >= i.code -> out += OldApk(i.e, i.pkg, i.name, "installed")
                infos.any { it.pkg == i.pkg && it.code > i.code } -> out += OldApk(i.e, i.pkg, i.name, "newer copy")
            }
        }
        return out
    }
}
