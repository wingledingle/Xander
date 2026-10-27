package app.quinten.data

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/** A book file opened for reading. For content URIs the file is reached through /proc/self/fd. */
class OpenedFile(val file: File, val displayName: String, private val pfd: ParcelFileDescriptor?) : Closeable {
    override fun close() { try { pfd?.close() } catch (_: Exception) {} }
}

/**
 * The app no longer may read a `content://` book (the temporary grant from "Open with" ended, or a
 * picked folder's permission was revoked). It is a [FileNotFoundException] so callers treat the
 * book as missing rather than crashing on a SecurityException.
 */
class PermissionLostException(val location: String) :
    FileNotFoundException("Permission to this file was lost — open it again from your file manager")

object BookFiles {
    /** Copies of unseekable/unreadable content (pipes, providers without a real file) above this are trimmed. */
    const val OPENED_CACHE_LIMIT = 500L * 1024 * 1024

    fun isContentUri(location: String) = location.startsWith("content://")

    /** Opens [location] read-only. Never writes to the source. */
    fun open(context: Context, location: String): OpenedFile {
        if (!isContentUri(location)) {
            val f = File(location)
            if (!f.canRead()) throw FileNotFoundException(location)
            return OpenedFile(f, f.name, null)
        }
        val uri = Uri.parse(location)
        val pfd = try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (e: SecurityException) {
            throw PermissionLostException(location)
        } ?: throw FileNotFoundException(location)
        val name = displayName(context, uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "book"
        val proc = File("/proc/self/fd/${pfd.fd}")
        // Pipes (some cloud/zip providers) report length 0 and cannot seek: copy them instead.
        if (proc.canRead() && runCatching { proc.length() }.getOrDefault(0L) > 0) return OpenedFile(proc, name, pfd)
        // Fallback: copy into the cache (the original is untouched).
        val cache = File(context.cacheDir, "opened").apply { mkdirs() }
        val copy = ParcelFileDescriptor.AutoCloseInputStream(pfd).use { copyToFingerprinted(it, cache, name) }
        trimDir(cache, OPENED_CACHE_LIMIT, keep = copy)
        return OpenedFile(copy, name, null)
    }

    /**
     * Copies [input] into [dir] as `<fingerprint>-<name>` (or with [nameFor] deciding the file name)
     * and returns the file. An existing identical copy is reused.
     */
    fun copyToFingerprinted(input: InputStream, dir: File, name: String, nameFor: (String) -> String = { fp -> fp + "-" + safeName(name) }): File {
        dir.mkdirs()
        val tmp = File.createTempFile("copy", ".tmp", dir)
        try {
            tmp.outputStream().use { input.copyTo(it) }
            if (tmp.length() <= 0) throw IOException("Empty file: $name")
            val target = File(dir, nameFor(fingerprint(tmp)))
            if (target.exists() && target.length() == tmp.length()) {
                tmp.delete()
                target.setLastModified(System.currentTimeMillis())
            } else if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true); tmp.delete()
            }
            return target
        } catch (e: Throwable) {
            tmp.delete(); throw e
        }
    }

    fun safeName(name: String): String = name.replace(Regex("[/\\\\:*?\"<>|\\u0000-\\u001f]"), "_").take(120).ifBlank { "book" }

    /** Deletes the oldest files in [dir] until it holds at most [limit] bytes ([keep] is never deleted). */
    fun trimDir(dir: File, limit: Long, keep: File? = null) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= limit) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= limit) break
            if (keep != null && f.absolutePath == keep.absolutePath) continue
            val len = f.length()
            if (f.delete()) total -= len
        }
    }

    /** True when [location] can be opened right now (file readable / URI permission held and the document exists). */
    fun isReadable(context: Context, location: String): Boolean {
        if (!isContentUri(location)) return File(location).let { it.isFile && it.canRead() }
        return try {
            context.contentResolver.openFileDescriptor(Uri.parse(location), "r")?.use { true } ?: false
        } catch (_: Exception) { false }
    }

    /** True when the app holds a persisted read permission for [uri] (or a tree that contains it). */
    fun hasPersistedPermission(context: Context, uri: Uri): Boolean {
        val s = uri.toString()
        return runCatching {
            context.contentResolver.persistedUriPermissions.any { p ->
                p.isReadPermission && (p.uri == uri || s.startsWith(p.uri.toString() + "/document/") ||
                    (DocumentsContract.isTreeUri(p.uri) && sameTreeDocument(p.uri, uri)))
            }
        }.getOrDefault(false)
    }

    private fun sameTreeDocument(tree: Uri, doc: Uri): Boolean = runCatching {
        val treeId = DocumentsContract.getTreeDocumentId(tree)
        val docId = DocumentsContract.getDocumentId(doc)
        tree.authority == doc.authority && (docId == treeId || docId.startsWith(if (treeId.endsWith(':')) treeId else treeId.trimEnd('/') + "/"))
    }.getOrDefault(false)

    /**
     * The real file behind a content URI, when one can be found: MediaStore's DATA column, or the
     * document id of Android's own storage provider ("primary:Books/a.epub", "1234-ABCD:a.epub") and
     * the Downloads provider ("raw:/storage/…"). Returns null when the path is unknown or unreadable.
     */
    fun resolveFilePath(context: Context, uri: Uri): String? {
        val candidates = ArrayList<String>()
        try {
            @Suppress("DEPRECATION")
            context.contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { candidates += it }
            }
        } catch (_: Exception) {}
        val docId = runCatching { if (DocumentsContract.isDocumentUri(context, uri)) DocumentsContract.getDocumentId(uri) else null }.getOrNull()
        if (docId != null) pathFromDocumentId(uri.authority, docId)?.let { candidates += it }
        return candidates.firstOrNull { p -> p.startsWith("/") && File(p).let { it.isFile && it.canRead() } }
    }

    /** Maps a storage-provider document id to a file path (pure; exposed for tests). */
    fun pathFromDocumentId(authority: String?, docId: String): String? = when {
        docId.startsWith("raw:") -> docId.removePrefix("raw:")
        authority == "com.android.externalstorage.documents" && ':' in docId -> {
            val volume = docId.substringBefore(':')
            val rel = docId.substringAfter(':')
            if (volume.equals("primary", true)) "/storage/emulated/0/$rel" else if (volume.isNotBlank()) "/storage/$volume/$rel" else null
        }
        else -> null
    }

    fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) { null }

    /**
     * Content fingerprint: size + SHA-1 of the first and last 64 KiB. Stable across moves/renames.
     * Files reporting no length (pipes) are rejected: they would all share one fingerprint.
     */
    fun fingerprint(file: File): String {
        RandomAccessFile(file, "r").use { r ->
            val len = r.length()
            if (len <= 0) throw IOException("Cannot fingerprint an empty or unseekable file: ${file.path}")
            val md = MessageDigest.getInstance("SHA-1")
            val buf = ByteArray(65536)
            val n1 = r.read(buf, 0, minOf(buf.size.toLong(), len).toInt())
            if (n1 > 0) md.update(buf, 0, n1)
            if (len > buf.size * 2) {
                r.seek(len - buf.size)
                r.readFully(buf)
                md.update(buf)
            }
            md.update(len.toString().toByteArray())
            return len.toString(16) + "-" + md.digest().joinToString("") { "%02x".format(it) }.take(24)
        }
    }
}
