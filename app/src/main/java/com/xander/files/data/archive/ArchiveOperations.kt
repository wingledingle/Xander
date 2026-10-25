package com.xander.files.data.archive

import android.content.Context
import com.xander.files.data.crypto.FileCrypto
import com.xander.files.data.crypto.NotEncryptedException
import com.xander.files.data.crypto.WrongPasswordException
import com.xander.files.data.transfer.ConflictChoice
import com.xander.files.data.transfer.ConflictRequest
import com.xander.files.data.transfer.CopyEngine
import com.xander.files.data.transfer.TransferContext
import com.xander.files.data.transfer.TransferOperation
import com.xander.files.domain.fs.FileSystemProvider
import com.xander.files.domain.fs.FileSystemRegistry
import com.xander.files.domain.fs.FsException
import com.xander.files.domain.fs.FsPath
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * EXTRACT: sources are archive files ("extract to <dest>/<archive name>") or entries inside an archive
 * ("extract selected"). Options.archiveName == "." extracts directly into the destination ("extract here").
 */
@Singleton
class ExtractOperation @Inject constructor(
    private val registry: FileSystemRegistry,
    private val archives: ArchiveFileSystemProvider,
    private val engine: CopyEngine,
) : TransferOperation {
    override suspend fun run(sources: List<FsPath>, destination: FsPath?, ctx: TransferContext) {
        val dest = destination ?: throw FsException.Generic("No destination")
        val dstP = registry.provider(dest)
        for (src in sources) {
            ctx.checkpoint()
            if (src.scheme == FsPath.SCHEME_ARCHIVE) {
                engine.transfer(listOf(src), dest, move = false, ctx = ctx)
                continue
            }
            val root = FsPath.archive(src)
            val into = if (ctx.options.archiveName == ".") dest else {
                val base = baseName(src.name)
                val name = if (dstP.exists(dest.child(base))) CopyEngine.uniqueName(dstP, dest, base) else base
                dstP.mkdir(dest, name)
            }
            if (archives.isRandomAccess(root)) {
                engine.transfer(archives.list(root).map { it.path }, into, move = false, ctx = ctx)
            } else streamExtract(root, into, dstP, ctx)
        }
    }

    /** Single pass for TAR/GZ/BZ2/XZ so large stream archives are not re-read per entry. */
    private suspend fun streamExtract(root: FsPath, into: FsPath, dstP: FileSystemProvider, ctx: TransferContext) {
        val items = archives.allItems(root)
        ctx.setTotals(items.sumOf { it.size.coerceAtLeast(0) }, items.size)
        val made = HashSet<String>()
        suspend fun ensureDir(rel: String): FsPath {
            var cur = into
            if (rel.isEmpty() || rel == "/") return cur
            for (seg in rel.removePrefix("/").split('/')) {
                val next = cur.child(seg)
                if (made.add(next.path) && !dstP.exists(next)) dstP.mkdir(cur, seg)
                cur = next
            }
            return cur
        }
        items.filter { it.isDirectory }.forEach { ensureDir(it.path); ctx.itemDone() }
        archives.forEachEntry(root) { item, input ->
            ctx.checkpoint(); ctx.setCurrent(item.path.substringAfterLast('/'))
            val parent = ensureDir(item.path.substringBeforeLast('/', ""))
            var name = item.path.substringAfterLast('/')
            val existing = runCatching { dstP.stat(parent.child(name)) }.getOrNull()
            if (existing != null) {
                when (ctx.resolveConflict(ConflictRequest(ctx.id, name, item.size, item.modified, parent.child(name).toString(), existing.size, existing.lastModified, false))) {
                    ConflictChoice.SKIP -> { ctx.bytes(item.size.coerceAtLeast(0)); ctx.itemDone(); return@forEachEntry }
                    ConflictChoice.RENAME -> name = CopyEngine.uniqueName(dstP, parent, name)
                    else -> dstP.delete(parent.child(name))
                }
            }
            val target = parent.child(name)
            dstP.openOutput(target, item.size).use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); ctx.bytes(n.toLong()); ctx.checkpoint() }
            }
            if (item.modified > 0) runCatching { dstP.setLastModified(target, item.modified) }
            ctx.itemDone()
        }
    }

    companion object {
        fun baseName(n: String): String {
            val l = n.lowercase()
            for (ext in listOf(".tar.gz", ".tar.bz2", ".tar.xz")) if (l.endsWith(ext)) return n.dropLast(ext.length)
            return n.substringBeforeLast('.').ifEmpty { n }
        }
    }
}

/** COMPRESS: sources -> destination folder / options.archiveName (+ format extension). */
@Singleton
class CompressOperation @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: FileSystemRegistry,
) : TransferOperation {
    override suspend fun run(sources: List<FsPath>, destination: FsPath?, ctx: TransferContext) {
        val dest = destination ?: throw FsException.Generic("No destination")
        val dstP = registry.provider(dest)
        val format = runCatching { OutFormat.valueOf(ctx.options.archiveFormat.uppercase()) }.getOrDefault(OutFormat.ZIP)
        val writer = ArchiveWriter(registry)
        val items = writer.collect(sources)
        ctx.setTotals(items.sumOf { if (it.entry.isDirectory) 0 else it.entry.size }, items.size)
        val base = ctx.options.archiveName.ifBlank { if (sources.size == 1) sources.first().name else "Archive" }
        var name = if (base.endsWith(".${format.ext}", true)) base else "$base.${format.ext}"
        if (dstP.exists(dest.child(name))) {
            when (ctx.resolveConflict(ConflictRequest(ctx.id, name, 0, System.currentTimeMillis(), dest.child(name).toString(), 0, 0, false))) {
                ConflictChoice.SKIP -> return
                ConflictChoice.RENAME -> name = CopyEngine.uniqueName(dstP, dest, name)
                else -> dstP.delete(dest.child(name))
            }
        }
        val target = dest.child(name)
        val scratch = File(context.cacheDir, "pack_${System.nanoTime()}.tmp")
        try {
            dstP.openOutput(target).use { out ->
                writer.write(items, format, out, ctx.options.password.ifEmpty { null }, ctx.options.compressionLevel, scratch,
                    onBytes = { ctx.bytes(it) }, checkpoint = { ctx.checkpoint() })
            }
            repeat(items.size) { ctx.itemDone() }
        } catch (e: Throwable) {
            runCatching { dstP.delete(target) }; throw e
        } finally { scratch.delete() }
    }
}

/** ENCRYPT: every file (folders recursively) -> "<name>.<ext>" next to it. */
@Singleton
class EncryptOperation @Inject constructor(private val registry: FileSystemRegistry, private val engine: CopyEngine) : TransferOperation {
    override suspend fun run(sources: List<FsPath>, destination: FsPath?, ctx: TransferContext) {
        val pwd = ctx.options.password.ifEmpty { throw FsException.PasswordRequired(sources.first()) }.toCharArray()
        val t = engine.measure(sources, ctx); ctx.setTotals(t.bytes, t.items)
        val ext = ctx.options.encryptedExtension.ifBlank { "xenc" }
        for (s in sources) walkFiles(registry.provider(s), s, ctx) { p, f ->
            if (f.name.endsWith(".$ext")) return@walkFiles
            val parent = f.parent!!
            var outName = "${f.name}.$ext"
            if (p.exists(parent.child(outName))) outName = CopyEngine.uniqueName(p, parent, outName)
            val out = parent.child(outName)
            try {
                p.openInput(f).use { i -> p.openOutput(out).use { o ->
                    FileCrypto.encrypt(i, o, pwd, onBytes = { ctx.bytes(it) }, checkpoint = { runBlocking { ctx.checkpoint() } })
                } }
            } catch (e: Throwable) { runCatching { p.delete(out) }; throw e }
            if (ctx.options.deleteOriginal) p.delete(f)
        }
    }
}

/** DECRYPT: "<name>.<ext>" -> "<name>" (unique if it exists). */
@Singleton
class DecryptOperation @Inject constructor(private val registry: FileSystemRegistry, private val engine: CopyEngine) : TransferOperation {
    override suspend fun run(sources: List<FsPath>, destination: FsPath?, ctx: TransferContext) {
        val pwd = ctx.options.password.ifEmpty { throw FsException.PasswordRequired(sources.first()) }.toCharArray()
        val t = engine.measure(sources, ctx); ctx.setTotals(t.bytes, t.items)
        val ext = ctx.options.encryptedExtension.ifBlank { "xenc" }
        for (s in sources) walkFiles(registry.provider(s), s, ctx) { p, f ->
            if (!f.name.endsWith(".$ext")) return@walkFiles
            val parent = destination ?: f.parent!!
            var outName = f.name.removeSuffix(".$ext").ifEmpty { "decrypted" }
            if (p.exists(parent.child(outName))) outName = CopyEngine.uniqueName(p, parent, outName)
            val out = parent.child(outName)
            try {
                p.openInput(f).use { i -> p.openOutput(out).use { o ->
                    FileCrypto.decrypt(i, o, pwd, onBytes = { ctx.bytes(it) }, checkpoint = { runBlocking { ctx.checkpoint() } })
                } }
            } catch (e: WrongPasswordException) { runCatching { p.delete(out) }; throw FsException.WrongPassword(e)
            } catch (e: NotEncryptedException) { runCatching { p.delete(out) }; throw FsException.Generic("${f.name} is not an encrypted file")
            } catch (e: Throwable) { runCatching { p.delete(out) }; throw e }
            if (ctx.options.deleteOriginal) p.delete(f)
        }
    }
}

private suspend fun walkFiles(p: FileSystemProvider, start: FsPath, ctx: TransferContext, block: suspend (FileSystemProvider, FsPath) -> Unit) {
    val e = p.stat(start)
    if (!e.isDirectory) { ctx.setCurrent(e.name); block(p, start); ctx.itemDone(); return }
    ctx.itemDone()
    for (c in p.list(start)) walkFiles(p, c.path, ctx, block)
}
