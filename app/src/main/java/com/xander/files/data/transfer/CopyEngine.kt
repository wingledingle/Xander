package com.xander.files.data.transfer

import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FileSystemProvider
import com.xander.files.domain.fs.FileSystemRegistry
import com.xander.files.domain.fs.FsException
import com.xander.files.domain.fs.FsPath
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Backend-neutral copy/move. Uses server-side operations when source and destination share a backend,
 * otherwise streams bytes between any two providers (local <-> SMB <-> cloud <-> archive ...).
 */
@Singleton
class CopyEngine @Inject constructor(private val registry: FileSystemRegistry) {

    data class Totals(val bytes: Long, val items: Int)

    /** Walks sources to size the job (directories count as items too). */
    suspend fun measure(sources: List<FsPath>, ctx: TransferContext?): Totals {
        var bytes = 0L; var items = 0
        for (s in sources) {
            val p = registry.provider(s)
            val e = p.stat(s)
            items++
            if (!e.isDirectory) { bytes += e.size; continue }
            val stack = ArrayDeque<FsPath>().apply { add(s) }
            while (stack.isNotEmpty()) {
                ctx?.checkpoint()
                val d = stack.removeLast()
                for (c in runCatching { p.list(d) }.getOrDefault(emptyList())) {
                    items++
                    if (c.isDirectory) { if (!c.isSymlink) stack.add(c.path) } else bytes += c.size
                }
            }
        }
        return Totals(bytes, items)
    }

    suspend fun transfer(sources: List<FsPath>, destDir: FsPath, move: Boolean, ctx: TransferContext) {
        val dstProvider = registry.provider(destDir)
        val destEntry = dstProvider.stat(destDir)
        if (!destEntry.isDirectory) throw FsException.NotADirectory(destDir)
        for (s in sources) {
            if (s.contains(destDir) && s.scheme == destDir.scheme) throw FsException.Generic("Cannot ${if (move) "move" else "copy"} a folder into itself")
        }
        val totals = measure(sources, ctx)
        ctx.setTotals(totals.bytes, totals.items)
        // Fail early if the destination reports too little space.
        dstProvider.spaceInfo(destDir)?.let { space ->
            val sameVolumeMove = move && sources.all { it.scheme == destDir.scheme && it.authority == destDir.authority && it.scheme == FsPath.SCHEME_FILE }
            if (!sameVolumeMove && space.free in 1 until totals.bytes) throw FsException.OutOfSpace()
        }
        for (s in sources) {
            ctx.checkpoint()
            val srcProvider = registry.provider(s)
            val entry = srcProvider.stat(s)
            copyEntry(entry, srcProvider, destDir, dstProvider, entry.name, move, ctx)
        }
        ctx.flush()
    }

    private suspend fun copyEntry(
        src: FileEntry, srcP: FileSystemProvider, destDir: FsPath, dstP: FileSystemProvider, name: String, move: Boolean, ctx: TransferContext,
    ) {
        ctx.checkpoint()
        ctx.setCurrent(src.name)
        var targetName = name
        var target = destDir.child(targetName)
        val existing = runCatching { dstP.stat(target) }.getOrNull()
        var merge = false
        if (existing != null) {
            if (src.path == target) {
                // Copy onto itself => create "name (1)".
                targetName = uniqueName(dstP, destDir, name); target = destDir.child(targetName)
            } else if (ctx.options.resumed && !src.isDirectory && existing.size == src.size) {
                ctx.bytes(src.size); ctx.itemDone()
                if (move) srcP.delete(src.path)
                return
            } else {
                val choice = ctx.resolveConflict(
                    ConflictRequest(ctx.id, src.name, src.size, src.lastModified, target.toString(), existing.size, existing.lastModified, src.isDirectory && existing.isDirectory)
                )
                when (choice) {
                    ConflictChoice.SKIP -> { skipTree(src, srcP, ctx); return }
                    ConflictChoice.RENAME -> { targetName = uniqueName(dstP, destDir, name); target = destDir.child(targetName) }
                    ConflictChoice.MERGE -> if (src.isDirectory && existing.isDirectory) merge = true else { dstP.delete(target) }
                    ConflictChoice.OVERWRITE -> if (src.isDirectory && existing.isDirectory) merge = true else dstP.delete(target)
                }
            }
        }

        val sameBackend = srcP === dstP && src.path.authority == destDir.authority
        if (sameBackend && !merge) {
            // Size the subtree first so progress stays accurate when the backend does the work in one call.
            val subtree = if (src.isDirectory) measure(listOf(src.path), null) else Totals(src.size, 1)
            var reported = 0L
            val done = if (move) srcP.moveInternal(src.path, destDir, targetName)
                else srcP.copyInternal(src.path, destDir, targetName) { reported += it; ctx.bytes(it) }
            if (done != null) {
                ctx.bytes((subtree.bytes - reported).coerceAtLeast(0))
                repeat(subtree.items) { ctx.itemDone() }
                return
            }
        }

        if (src.isDirectory) {
            if (!merge) dstP.mkdir(destDir, targetName)
            ctx.itemDone()
            val children = srcP.list(src.path)
            for (c in children) copyEntry(c, srcP, target, dstP, c.name, move, ctx)
            if (move) runCatching { srcP.delete(src.path) }
        } else {
            streamWithRetry(src, srcP, target, dstP, ctx)
            if (ctx.options.preserveTimestamps && src.lastModified > 0) runCatching { dstP.setLastModified(target, src.lastModified) }
            if (move) srcP.delete(src.path)
            ctx.itemDone()
        }
    }

    private suspend fun skipTree(src: FileEntry, p: FileSystemProvider, ctx: TransferContext) {
        if (!src.isDirectory) { ctx.bytes(src.size); ctx.itemDone(); return }
        val t = measure(listOf(src.path), null)
        ctx.bytes(t.bytes); repeat(t.items) { ctx.itemDone() }
    }

    private suspend fun streamWithRetry(src: FileEntry, srcP: FileSystemProvider, target: FsPath, dstP: FileSystemProvider, ctx: TransferContext) {
        var attempt = 0
        while (true) {
            var copied = 0L
            try {
                srcP.openInput(src.path).use { input ->
                    dstP.openOutput(target, src.size).use { output -> copied = pump(input, output, ctx) }
                }
                return
            } catch (e: Exception) {
                val fe = FsException.wrap(e, src.path)
                ctx.bytes(-copied)
                if (fe is FsException.Cancelled) { runCatching { dstP.delete(target) }; throw fe }
                if (!fe.retryable || attempt >= MAX_RETRIES) { runCatching { dstP.delete(target) }; throw fe }
                attempt++
                delay(1000L shl attempt)
            }
        }
    }

    private suspend fun pump(input: InputStream, output: OutputStream, ctx: TransferContext): Long {
        val buf = ByteArray(BUFFER)
        var total = 0L
        while (true) {
            coroutineContext.ensureActive()
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            total += n
            ctx.bytes(n.toLong())
            ctx.checkpoint()
        }
        output.flush()
        return total
    }

    companion object {
        const val BUFFER = 256 * 1024
        const val MAX_RETRIES = 3

        suspend fun uniqueName(p: FileSystemProvider, dir: FsPath, name: String): String {
            val base = if (name.contains('.') && !name.startsWith('.')) name.substringBeforeLast('.') else name
            val ext = if (name.contains('.') && !name.startsWith('.')) "." + name.substringAfterLast('.') else ""
            val existing = runCatching { p.list(dir).map { it.name }.toHashSet() }.getOrDefault(hashSetOf())
            var i = 1
            while (true) {
                val candidate = "$base ($i)$ext"
                if (candidate !in existing) return candidate
                i++
            }
        }
    }
}
