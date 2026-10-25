package com.xander.files.data.archive

import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FileSystemProvider
import com.xander.files.domain.fs.FileSystemRegistry
import com.xander.files.domain.fs.FsPath
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipParameters
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Date

enum class OutFormat(val ext: String) { ZIP("zip"), SEVEN_Z("7z"), TAR("tar"), TAR_GZ("tar.gz"), TAR_BZ2("tar.bz2") }

/** A file to add plus its path inside the archive. */
data class PackItem(val entry: FileEntry, val provider: FileSystemProvider, val relativePath: String)

/** Creates archives from files on any backend. */
class ArchiveWriter(private val registry: FileSystemRegistry) {

    /** Expands [sources] into items with archive-relative paths (folders keep their name as top level). */
    suspend fun collect(sources: List<FsPath>): List<PackItem> {
        val out = ArrayList<PackItem>()
        for (s in sources) {
            val p = registry.provider(s)
            val e = p.stat(s)
            out += PackItem(e, p, e.name)
            if (e.isDirectory) {
                val stack = ArrayDeque<Pair<FsPath, String>>().apply { add(s to e.name) }
                while (stack.isNotEmpty()) {
                    val (dir, rel) = stack.removeLast()
                    for (c in p.list(dir)) {
                        val r = "$rel/${c.name}"
                        out += PackItem(c, p, r)
                        if (c.isDirectory && !c.isSymlink) stack.add(c.path to r)
                    }
                }
            }
        }
        return out
    }

    /**
     * Writes [items] as [format] to [out]. 7z needs a seekable file, so [scratch] is used and copied after.
     * [onBytes] reports uncompressed bytes consumed; [checkpoint] lets the caller pause/cancel.
     */
    suspend fun write(
        items: List<PackItem>, format: OutFormat, out: OutputStream, password: String?, level: Int, scratch: File,
        onBytes: (Long) -> Unit, checkpoint: suspend () -> Unit,
    ) {
        when (format) {
            OutFormat.ZIP -> writeZip(items, out, password, level, onBytes, checkpoint)
            OutFormat.SEVEN_Z -> {
                SevenZOutputFile(scratch).use { sz ->
                    sz.setContentCompression(if (level == 0) SevenZMethod.COPY else SevenZMethod.LZMA2)
                    for (it in items) {
                        checkpoint()
                        val entry = sz.createArchiveEntry(File(it.relativePath), it.relativePath).apply {
                            setDirectory(it.entry.isDirectory)
                            if (it.entry.lastModified > 0) lastModifiedDate = Date(it.entry.lastModified)
                        }
                        sz.putArchiveEntry(entry)
                        if (!it.entry.isDirectory) it.provider.openInput(it.entry.path).use { i -> pump(i, { b, o, l -> sz.write(b, o, l) }, onBytes, checkpoint) }
                        sz.closeArchiveEntry()
                    }
                }
                scratch.inputStream().use { it.copyTo(out, 1 shl 18) }
            }
            OutFormat.TAR, OutFormat.TAR_GZ, OutFormat.TAR_BZ2 -> {
                val wrapped: OutputStream = when (format) {
                    OutFormat.TAR_GZ -> GzipCompressorOutputStream(out, GzipParameters().apply { compressionLevel = level.coerceIn(1, 9) })
                    OutFormat.TAR_BZ2 -> BZip2CompressorOutputStream(out, level.coerceIn(1, 9))
                    else -> out
                }
                TarArchiveOutputStream(wrapped).use { tar ->
                    tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                    tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                    for (it in items) {
                        checkpoint()
                        val e = TarArchiveEntry(it.relativePath + if (it.entry.isDirectory) "/" else "")
                        if (!it.entry.isDirectory) e.size = it.entry.size
                        if (it.entry.lastModified > 0) e.setModTime(it.entry.lastModified)
                        tar.putArchiveEntry(e)
                        if (!it.entry.isDirectory) it.provider.openInput(it.entry.path).use { i -> pump(i, { b, o, l -> tar.write(b, o, l) }, onBytes, checkpoint) }
                        tar.closeArchiveEntry()
                    }
                    tar.finish()
                }
            }
        }
    }

    private suspend fun writeZip(items: List<PackItem>, out: OutputStream, password: String?, level: Int, onBytes: (Long) -> Unit, checkpoint: suspend () -> Unit) {
        val zos = if (password.isNullOrEmpty()) ZipOutputStream(out) else ZipOutputStream(out, password.toCharArray())
        zos.use { z ->
            for (it in items) {
                checkpoint()
                val params = ZipParameters().apply {
                    fileNameInZip = it.relativePath + if (it.entry.isDirectory) "/" else ""
                    compressionMethod = if (level == 0) CompressionMethod.STORE else CompressionMethod.DEFLATE
                    compressionLevel = zipLevel(level)
                    if (it.entry.lastModified > 0) lastModifiedFileTime = it.entry.lastModified
                    if (!password.isNullOrEmpty() && !it.entry.isDirectory) {
                        isEncryptFiles = true
                        encryptionMethod = EncryptionMethod.AES
                        aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                    }
                    if (!it.entry.isDirectory) entrySize = it.entry.size
                }
                z.putNextEntry(params)
                if (!it.entry.isDirectory) it.provider.openInput(it.entry.path).use { i -> pump(i, { b, o, l -> z.write(b, o, l) }, onBytes, checkpoint) }
                z.closeEntry()
            }
        }
    }

    private fun zipLevel(l: Int) = when (l) {
        0, 1 -> CompressionLevel.FASTEST
        2 -> CompressionLevel.FASTER
        3, 4 -> CompressionLevel.FAST
        5, 6 -> CompressionLevel.NORMAL
        7 -> CompressionLevel.HIGHER
        8 -> CompressionLevel.MAXIMUM
        else -> CompressionLevel.ULTRA
    }

    private suspend fun pump(input: InputStream, write: (ByteArray, Int, Int) -> Unit, onBytes: (Long) -> Unit, checkpoint: suspend () -> Unit) {
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf); if (n < 0) break
            write(buf, 0, n); onBytes(n.toLong()); checkpoint()
        }
    }
}
