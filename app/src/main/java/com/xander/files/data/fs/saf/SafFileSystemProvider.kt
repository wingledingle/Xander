package com.xander.files.data.fs.saf

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.xander.files.domain.fs.Capability
import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FileSystemProvider
import com.xander.files.domain.fs.FsException
import com.xander.files.domain.fs.FsPath
import com.xander.files.domain.fs.MimeTypes
import com.xander.files.domain.fs.ProgressListener
import com.xander.files.domain.fs.SpaceInfo
import com.xander.files.data.storage.StorageVolumes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Storage Access Framework backend used for SD cards / USB drives (pre-Android 11) and Android/data grants. */
@Singleton
class SafFileSystemProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val roots: SafRootStore,
    private val volumes: StorageVolumes,
) : FileSystemProvider {

    override val scheme = FsPath.SCHEME_SAF
    private val resolver: ContentResolver get() = context.contentResolver
    /** Cache of path -> documentId for providers with opaque ids. */
    private val idCache = ConcurrentHashMap<String, String>()

    override fun capabilities(path: FsPath) = setOf(Capability.WRITE, Capability.RENAME, Capability.SERVER_SIDE_MOVE, Capability.SPACE_INFO)

    private suspend fun tree(path: FsPath): Uri {
        val root = roots.get(path.authority) ?: throw FsException.NotFound(path)
        return Uri.parse(root.treeUri)
    }

    private fun isExternalStorage(tree: Uri) = tree.authority == SafRootStore.EXTERNAL_STORAGE_AUTHORITY

    private suspend fun docId(path: FsPath): String {
        val tree = tree(path)
        val treeId = DocumentsContract.getTreeDocumentId(tree)
        if (path.isRoot) return treeId
        if (isExternalStorage(tree)) {
            val rel = path.path.removePrefix("/")
            return if (treeId.endsWith(":")) treeId + rel else "$treeId/$rel"
        }
        idCache[path.toString()]?.let { return it }
        var current = treeId
        var p = FsPath(path.scheme, path.authority, "/")
        for (seg in path.path.removePrefix("/").split('/')) {
            p = p.child(seg)
            val cached = idCache[p.toString()]
            if (cached != null) { current = cached; continue }
            val child = queryChildren(tree, current).firstOrNull { it.name == seg } ?: throw FsException.NotFound(path)
            current = child.id
            idCache[p.toString()] = current
        }
        return current
    }

    private data class Row(val id: String, val name: String, val mime: String, val size: Long, val modified: Long, val flags: Int)

    private val projection = arrayOf(
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS
    )

    private fun Cursor.row() = Row(
        getString(0), getString(1) ?: "", getString(2) ?: "application/octet-stream",
        if (isNull(3)) 0 else getLong(3), if (isNull(4)) 0 else getLong(4), if (isNull(5)) 0 else getInt(5)
    )

    private fun queryChildren(tree: Uri, parentId: String): List<Row> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val out = ArrayList<Row>()
        resolver.query(uri, projection, null, null, null)?.use { c -> while (c.moveToNext()) out += c.row() }
            ?: throw FsException.PermissionDenied(null)
        return out
    }

    private fun queryOne(tree: Uri, id: String, path: FsPath): Row {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        return try {
            resolver.query(uri, projection, null, null, null)?.use { c -> if (c.moveToFirst()) c.row() else null }
        } catch (e: Exception) { null } ?: throw FsException.NotFound(path)
    }

    private fun Row.toEntry(path: FsPath) = FileEntry(
        path = path,
        isDirectory = mime == Document.MIME_TYPE_DIR,
        size = size, lastModified = modified,
        mimeType = if (mime == Document.MIME_TYPE_DIR) null else mime,
        canWrite = flags and (Document.FLAG_SUPPORTS_WRITE or Document.FLAG_DIR_SUPPORTS_CREATE) != 0,
        backendId = id,
    )

    fun documentUri(tree: Uri, id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    suspend fun contentUri(path: FsPath): Uri = withContext(Dispatchers.IO) { documentUri(tree(path), docId(path)) }

    override suspend fun list(dir: FsPath): List<FileEntry> = io(dir) {
        val tree = tree(dir)
        queryChildren(tree, docId(dir)).map { r ->
            val p = dir.child(r.name)
            idCache[p.toString()] = r.id
            r.toEntry(p)
        }
    }

    override suspend fun stat(path: FsPath): FileEntry = io(path) {
        val tree = tree(path)
        queryOne(tree, docId(path), path).toEntry(path)
    }

    override suspend fun openInput(path: FsPath, offset: Long): InputStream = io(path) {
        val s = resolver.openInputStream(documentUri(tree(path), docId(path))) ?: throw FsException.NotFound(path)
        if (offset > 0) { var left = offset; while (left > 0) { val k = s.skip(left); if (k <= 0) break; left -= k } }
        s
    }

    override suspend fun openOutput(path: FsPath, size: Long): OutputStream = io(path) {
        val tree = tree(path)
        val id = try { docId(path) } catch (e: FsException.NotFound) {
            val parent = path.parent ?: throw e
            val created = DocumentsContract.createDocument(
                resolver, documentUri(tree, docId(parent)), MimeTypes.fromName(path.name), path.name
            ) ?: throw FsException.PermissionDenied(path)
            val newId = DocumentsContract.getDocumentId(created)
            idCache[path.toString()] = newId
            newId
        }
        resolver.openOutputStream(documentUri(tree, id), "wt") ?: throw FsException.PermissionDenied(path)
    }

    override suspend fun mkdir(parent: FsPath, name: String): FsPath = io(parent) {
        val tree = tree(parent)
        val target = parent.child(name)
        if (runCatching { docId(target); queryOne(tree, docId(target), target) }.isSuccess) throw FsException.AlreadyExists(target)
        val uri = DocumentsContract.createDocument(resolver, documentUri(tree, docId(parent)), Document.MIME_TYPE_DIR, name)
            ?: throw FsException.PermissionDenied(parent)
        idCache[target.toString()] = DocumentsContract.getDocumentId(uri)
        target
    }

    override suspend fun rename(path: FsPath, newName: String): FsPath = io(path) {
        val tree = tree(path)
        val uri = DocumentsContract.renameDocument(resolver, documentUri(tree, docId(path)), newName)
            ?: throw FsException.PermissionDenied(path)
        invalidate(path)
        val np = path.parent!!.child(newName)
        idCache[np.toString()] = DocumentsContract.getDocumentId(uri)
        np
    }

    override suspend fun delete(path: FsPath) = io(path) {
        val tree = tree(path)
        if (!DocumentsContract.deleteDocument(resolver, documentUri(tree, docId(path)))) throw FsException.PermissionDenied(path)
        invalidate(path)
    }

    override suspend fun moveInternal(src: FsPath, dstParent: FsPath, name: String): FsPath? = io(src) {
        if (Build.VERSION.SDK_INT < 24 || src.authority != dstParent.authority) return@io null
        val tree = tree(src)
        val row = queryOne(tree, docId(src), src)
        if (row.flags and Document.FLAG_SUPPORTS_MOVE == 0) return@io null
        val srcParent = src.parent ?: return@io null
        val moved = try {
            DocumentsContract.moveDocument(resolver, documentUri(tree, docId(src)), documentUri(tree, docId(srcParent)), documentUri(tree, docId(dstParent)))
        } catch (e: Exception) { null } ?: return@io null
        invalidate(src)
        var result = dstParent.child(src.name)
        if (name != src.name) {
            DocumentsContract.renameDocument(resolver, moved, name)
            result = dstParent.child(name)
        }
        result
    }

    override suspend fun copyInternal(src: FsPath, dstParent: FsPath, name: String, progress: ProgressListener?): FsPath? = io(src) {
        if (Build.VERSION.SDK_INT < 24 || src.authority != dstParent.authority || name != src.name) return@io null
        val tree = tree(src)
        val row = queryOne(tree, docId(src), src)
        if (row.flags and Document.FLAG_SUPPORTS_COPY == 0) return@io null
        try {
            DocumentsContract.copyDocument(resolver, documentUri(tree, docId(src)), documentUri(tree, docId(dstParent)))
        } catch (e: Exception) { null }?.let { progress?.onBytes(row.size); dstParent.child(name) }
    }

    override suspend fun spaceInfo(path: FsPath): SpaceInfo? {
        val root = roots.get(path.authority) ?: return null
        val (t, f) = volumes.space(root.volumePath)
        return if (t > 0) SpaceInfo(t, f) else null
    }

    override suspend fun rootTitle(authority: String): String = roots.get(authority)?.label ?: authority

    private fun invalidate(path: FsPath) {
        val key = path.toString()
        idCache.keys.removeAll { it == key || it.startsWith("$key/") }
    }

    private suspend fun <T> io(path: FsPath, block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try { block() } catch (e: Exception) { throw FsException.wrap(e, path) }
    }
}
