package com.xander.files.data.archive

import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.FileHeader
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.lzma.LZMACompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset

enum class ArchiveFormat { ZIP, SEVEN_Z, TAR, TAR_GZ, TAR_BZ2, TAR_XZ, GZ, BZ2, XZ, LZMA, RAR, UNKNOWN }

data class ArchiveItem(
    /** Normalized path inside the archive, always starting with '/'. */
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val compressedSize: Long,
    val modified: Long,
    val encrypted: Boolean,
)

class ArchivePasswordException(val wrong: Boolean) : IOException(if (wrong) "Wrong password" else "Password required")
class UnsupportedArchiveException(msg: String) : IOException(msg)

object ArchiveFormats {
    fun detect(name: String, header: ByteArray?): ArchiveFormat {
        val n = name.lowercase()
        val byExt = when {
            n.endsWith(".tar.gz") || n.endsWith(".tgz") -> ArchiveFormat.TAR_GZ
            n.endsWith(".tar.bz2") || n.endsWith(".tbz2") || n.endsWith(".tbz") -> ArchiveFormat.TAR_BZ2
            n.endsWith(".tar.xz") || n.endsWith(".txz") -> ArchiveFormat.TAR_XZ
            n.endsWith(".tar") -> ArchiveFormat.TAR
            n.endsWith(".7z") -> ArchiveFormat.SEVEN_Z
            n.endsWith(".rar") -> ArchiveFormat.RAR
            n.endsWith(".gz") -> ArchiveFormat.GZ
            n.endsWith(".bz2") -> ArchiveFormat.BZ2
            n.endsWith(".xz") -> ArchiveFormat.XZ
            n.endsWith(".lzma") -> ArchiveFormat.LZMA
            n.endsWith(".zip") || n.endsWith(".jar") || n.endsWith(".apk") || n.endsWith(".apks") || n.endsWith(".xapk") ||
                n.endsWith(".apkm") || n.endsWith(".obb") || n.endsWith(".epub") || n.endsWith(".docx") || n.endsWith(".xlsx") ||
                n.endsWith(".pptx") || n.endsWith(".cbz") || n.endsWith(".aar") -> ArchiveFormat.ZIP
            else -> ArchiveFormat.UNKNOWN
        }
        val h = header ?: return byExt
        val byMagic = when {
            h.size >= 4 && h[0] == 'P'.code.toByte() && h[1] == 'K'.code.toByte() && (h[2].toInt() == 3 || h[2].toInt() == 5) -> ArchiveFormat.ZIP
            h.size >= 6 && h[0] == '7'.code.toByte() && h[1] == 'z'.code.toByte() && h[2] == 0xBC.toByte() && h[3] == 0xAF.toByte() -> ArchiveFormat.SEVEN_Z
            h.size >= 4 && h[0] == 'R'.code.toByte() && h[1] == 'a'.code.toByte() && h[2] == 'r'.code.toByte() && h[3] == '!'.code.toByte() -> ArchiveFormat.RAR
            else -> null
        }
        return when {
            byMagic == null -> byExt
            byMagic == ArchiveFormat.ZIP -> ArchiveFormat.ZIP
            else -> byMagic
        }
    }

    fun isArchiveName(name: String) = detect(name, null) != ArchiveFormat.UNKNOWN && !name.lowercase().let {
        // Documents and books that happen to be ZIP containers open in their viewers, not as folders.
        it.endsWith(".epub") || it.endsWith(".docx") || it.endsWith(".xlsx") || it.endsWith(".pptx")
    } && !name.lowercase().endsWith(".apk")

    fun norm(p: String): String {
        val parts = p.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." && it != ".." }
        return "/" + parts.joinToString("/")
    }
}

/** Read access to one archive file (always a local file; remote archives are cached first). */
interface ArchiveReader : AutoCloseable {
    val randomAccess: Boolean
    fun items(): List<ArchiveItem>
    fun open(path: String): InputStream
    /** Single pass over all entries (fast for stream formats). */
    fun forEach(block: (ArchiveItem, InputStream) -> Unit)
}

class ZipReader(private val file: File, password: CharArray?, charset: Charset?) : ArchiveReader {
    private val zip = ZipFile(file, password).apply { if (charset != null) setCharset(charset) }
    override val randomAccess = true
    private val headers: List<FileHeader> by lazy {
        try { zip.fileHeaders } catch (e: ZipException) { throw IOException("Not a valid ZIP archive: ${e.message}", e) }
    }

    override fun items(): List<ArchiveItem> = headers.map {
        ArchiveItem(ArchiveFormats.norm(it.fileName), it.isDirectory, it.uncompressedSize, it.compressedSize,
            it.lastModifiedTimeEpoch, it.isEncrypted)
    }

    override fun open(path: String): InputStream {
        val h = headers.firstOrNull { ArchiveFormats.norm(it.fileName) == path } ?: throw java.io.FileNotFoundException(path)
        if (h.isEncrypted && !zip.isEncryptedWithPassword()) throw ArchivePasswordException(false)
        return try { zip.getInputStream(h) } catch (e: ZipException) {
            if (e.type == ZipException.Type.WRONG_PASSWORD || e.message?.contains("password", true) == true) throw ArchivePasswordException(true)
            throw IOException(e.message, e)
        }
    }

    override fun forEach(block: (ArchiveItem, InputStream) -> Unit) {
        for (it in items()) if (!it.isDirectory) open(it.path).use { s -> block(it, s) }
    }

    private fun ZipFile.isEncryptedWithPassword(): Boolean = runCatching {
        javaClass.getDeclaredField("password").apply { isAccessible = true }.get(this) != null
    }.getOrDefault(true)

    override fun close() = zip.close()
}

class SevenZReader(file: File, private val password: CharArray?) : ArchiveReader {
    private val sz: SevenZFile = wrapPassword {
        SevenZFile.builder().setFile(file).apply { if (password != null) setPassword(password) }.get()
    }
    override val randomAccess = true
    private val all by lazy { sz.entries.toList() }

    override fun items() = all.map {
        ArchiveItem(ArchiveFormats.norm(it.name), it.isDirectory, it.size, it.size, if (it.hasLastModifiedDate) it.lastModifiedDate.time else 0,
            it.contentMethods?.any { m -> m.method.name.contains("AES") } == true)
    }

    override fun open(path: String): InputStream {
        val e = all.firstOrNull { ArchiveFormats.norm(it.name) == path } ?: throw java.io.FileNotFoundException(path)
        val s = wrapPassword { sz.getInputStream(e) }
        return object : FilterInputStream(s) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = wrapPassword { super.read(b, off, len) }
        }
    }

    override fun forEach(block: (ArchiveItem, InputStream) -> Unit) {
        val its = items().associateBy { it.path }
        var e = wrapPassword { sz.nextEntry }
        while (e != null) {
            if (!e.isDirectory) {
                val item = its[ArchiveFormats.norm(e.name)]!!
                block(item, object : InputStream() {
                    override fun read() = wrapPassword { sz.read() }
                    override fun read(b: ByteArray, off: Int, len: Int) = wrapPassword { sz.read(b, off, len) }
                })
            }
            e = wrapPassword { sz.nextEntry }
        }
    }

    /** Maps commons-compress password failures (missing, or wrong = checksum/data errors) to typed exceptions. */
    private inline fun <T> wrapPassword(f: () -> T): T = try { f() } catch (e: org.apache.commons.compress.PasswordRequiredException) {
        throw ArchivePasswordException(false)
    } catch (e: IOException) {
        val m = e.message.orEmpty()
        if (password != null && (m.contains("checksum", true) || m.contains("crc", true) || m.contains("corrupt", true) ||
                m.contains("password", true) || m.contains("Bad 7z", true) || m.contains("Unsupported", true) && m.contains("header", true)))
            throw ArchivePasswordException(true)
        throw e
    }

    override fun close() = sz.close()
}

/** TAR family and single-file compressors: streaming only, so random access re-scans. */
class StreamArchiveReader(private val file: File, private val format: ArchiveFormat) : ArchiveReader {
    override val randomAccess = false

    private fun decompressed(): InputStream {
        val raw = BufferedInputStream(file.inputStream(), 1 shl 16)
        return when (format) {
            ArchiveFormat.TAR -> raw
            ArchiveFormat.TAR_GZ, ArchiveFormat.GZ -> GzipCompressorInputStream(raw, true)
            ArchiveFormat.TAR_BZ2, ArchiveFormat.BZ2 -> BZip2CompressorInputStream(raw, true)
            ArchiveFormat.TAR_XZ, ArchiveFormat.XZ -> XZCompressorInputStream(raw, true)
            ArchiveFormat.LZMA -> LZMACompressorInputStream(raw)
            else -> throw UnsupportedArchiveException("Unsupported format")
        }
    }

    private val isSingle get() = format in setOf(ArchiveFormat.GZ, ArchiveFormat.BZ2, ArchiveFormat.XZ, ArchiveFormat.LZMA)

    private val singleName: String
        get() {
            val n = file.name
            val ext = n.substringAfterLast('.', "")
            return if (ext.isEmpty()) "$n.out" else n.dropLast(ext.length + 1)
        }

    private val cached: List<ArchiveItem> by lazy {
        if (isSingle) listOf(ArchiveItem("/$singleName", false, -1, file.length(), file.lastModified(), false))
        else {
            val out = ArrayList<ArchiveItem>()
            TarArchiveInputStream(decompressed()).use { tar ->
                var e = tar.nextEntry
                while (e != null) { out += e.toItem(); e = tar.nextEntry }
            }
            out
        }
    }

    private fun ArchiveEntry.toItem() = ArchiveItem(ArchiveFormats.norm(name), isDirectory, size, size, lastModifiedDate?.time ?: 0, false)

    override fun items() = cached

    override fun open(path: String): InputStream {
        if (isSingle) return decompressed()
        val tar = TarArchiveInputStream(decompressed())
        var e = tar.nextEntry
        while (e != null) {
            if (ArchiveFormats.norm(e.name) == path) return object : FilterInputStream(tar) {}
            e = tar.nextEntry
        }
        tar.close()
        throw java.io.FileNotFoundException(path)
    }

    override fun forEach(block: (ArchiveItem, InputStream) -> Unit) {
        if (isSingle) { decompressed().use { block(cached.first(), it) }; return }
        TarArchiveInputStream(decompressed()).use { tar ->
            var e = tar.nextEntry
            while (e != null) {
                if (!e.isDirectory) block(e.toItem(), object : FilterInputStream(tar) { override fun close() {} })
                e = tar.nextEntry
            }
        }
    }

    override fun close() {}
}

object ArchiveReaders {
    fun open(file: File, displayName: String, password: CharArray?, charset: Charset? = null): ArchiveReader {
        val header = ByteArray(8).also { b -> file.inputStream().use { it.read(b) } }
        return when (val f = ArchiveFormats.detect(displayName, header)) {
            ArchiveFormat.ZIP -> ZipReader(file, password, charset)
            ArchiveFormat.SEVEN_Z -> SevenZReader(file, password)
            ArchiveFormat.TAR, ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_BZ2, ArchiveFormat.TAR_XZ,
            ArchiveFormat.GZ, ArchiveFormat.BZ2, ArchiveFormat.XZ, ArchiveFormat.LZMA -> StreamArchiveReader(file, f)
            ArchiveFormat.RAR -> throw UnsupportedArchiveException("RAR archives are not supported")
            ArchiveFormat.UNKNOWN -> throw UnsupportedArchiveException("Unknown archive format")
        }
    }
}
