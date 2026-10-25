package com.xander.files.domain.fs

import kotlinx.serialization.Serializable

/**
 * Backend-neutral location. Every storage backend is addressed as `scheme://authority/path`:
 *  - file:///storage/emulated/0/DCIM         local file system
 *  - root:///data/local                      root shell (libsu)
 *  - shizuku:///storage/emulated/0/Android/data
 *  - saf://<rootId>/sub/dir                  Storage Access Framework tree (SD card, OTG, Android/data grants)
 *  - archive://<urlencoded archive FsPath>/inner   browsing inside an archive
 *  - smb|ftp|sftp|webdav|nfs|http://<connectionId>/path
 *  - cloud://<accountId>/path
 *  - tv://<deviceId>/path
 */
@Serializable
data class FsPath(val scheme: String, val authority: String = "", val path: String = "/") {

    init {
        require(path.startsWith("/")) { "path must be absolute: $path" }
    }

    val isRoot: Boolean get() = path == "/"

    val name: String
        get() = if (isRoot) "" else path.substringAfterLast('/')

    val extension: String
        get() = name.substringAfterLast('.', "").lowercase()

    val parent: FsPath?
        get() = if (isRoot) null else copy(path = path.substringBeforeLast('/').ifEmpty { "/" })

    fun child(childName: String): FsPath {
        require(childName.isNotEmpty() && !childName.contains('/')) { "invalid name: $childName" }
        return copy(path = if (isRoot) "/$childName" else "$path/$childName")
    }

    fun resolve(relative: String): FsPath {
        var p = this
        relative.split('/').filter { it.isNotEmpty() && it != "." }.forEach {
            p = if (it == "..") p.parent ?: p else p.child(it)
        }
        return p
    }

    /** True if [other] is this path or located below it on the same backend. */
    fun contains(other: FsPath): Boolean =
        scheme == other.scheme && authority == other.authority &&
            (isRoot || other.path == path || other.path.startsWith("$path/"))

    fun relativeTo(base: FsPath): String =
        if (base.isRoot) path.removePrefix("/") else path.removePrefix(base.path).removePrefix("/")

    /** All ancestors from root to this path (inclusive). */
    fun segments(): List<FsPath> {
        val out = ArrayList<FsPath>()
        var p: FsPath? = this
        while (p != null) { out.add(p); p = p.parent }
        return out.asReversed()
    }

    val isLocal: Boolean get() = scheme == SCHEME_FILE

    override fun toString(): String = "$scheme://$authority$path"

    companion object {
        const val SCHEME_FILE = "file"
        const val SCHEME_ROOT = "root"
        const val SCHEME_SHIZUKU = "shizuku"
        const val SCHEME_SAF = "saf"
        const val SCHEME_ARCHIVE = "archive"
        const val SCHEME_SMB = "smb"
        const val SCHEME_NFS = "nfs"
        const val SCHEME_HTTP = "http"
        const val SCHEME_FTP = "ftp"
        const val SCHEME_SFTP = "sftp"
        const val SCHEME_WEBDAV = "webdav"
        const val SCHEME_CLOUD = "cloud"
        const val SCHEME_TV = "tv"

        fun local(absolutePath: String): FsPath = FsPath(SCHEME_FILE, "", normalize(absolutePath))

        fun parse(value: String): FsPath {
            val idx = value.indexOf("://")
            if (idx <= 0) return local(value)
            val scheme = value.substring(0, idx)
            val rest = value.substring(idx + 3)
            val slash = rest.indexOf('/')
            return if (slash < 0) FsPath(scheme, rest, "/")
            else FsPath(scheme, rest.substring(0, slash), normalize(rest.substring(slash)))
        }

        fun normalize(p: String): String {
            val parts = ArrayList<String>()
            p.split('/').forEach {
                when (it) {
                    "", "." -> Unit
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                    else -> parts.add(it)
                }
            }
            return "/" + parts.joinToString("/")
        }

        fun archive(archiveFile: FsPath, inner: String = "/"): FsPath =
            FsPath(SCHEME_ARCHIVE, encodeAuthority(archiveFile.toString()), normalize(inner))

        /** Percent-encodes an arbitrary string so it can be embedded as an authority (no '/' survives). */
        fun encodeAuthority(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

        fun decodeAuthority(s: String): String = java.net.URLDecoder.decode(s, "UTF-8")
    }
}
