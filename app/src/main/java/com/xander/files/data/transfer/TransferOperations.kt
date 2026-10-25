package com.xander.files.data.transfer

import com.xander.files.data.recycle.RecycleBin
import com.xander.files.domain.fs.FileSystemRegistry
import com.xander.files.domain.fs.FsPath
import javax.inject.Inject
import javax.inject.Singleton

/** One long-running operation kind handled by the transfer engine. */
interface TransferOperation {
    suspend fun run(sources: List<FsPath>, destination: FsPath?, ctx: TransferContext)
}

@Singleton
class CopyOperation @Inject constructor(private val engine: CopyEngine) : TransferOperation {
    override suspend fun run(sources: List<FsPath>, destination: FsPath?, ctx: TransferContext) =
        engine.transfer(sources, destination!!, move = false, ctx = ctx)
}

@Singleton
class MoveOperation @Inject constructor(private val engine: CopyEngine) : TransferOperation {
    override suspend fun run(sources: List<FsPath>, destination: FsPath?, ctx: TransferContext) =
        engine.transfer(sources, destination!!, move = true, ctx = ctx)
}

@Singleton
class DeleteOperation @Inject constructor(private val registry: FileSystemRegistry) : TransferOperation {
    override suspend fun run(sources: List<FsPath>, destination: FsPath?, ctx: TransferContext) {
        ctx.setTotals(0, sources.size)
        for (s in sources) {
            ctx.checkpoint(); ctx.setCurrent(s.name)
            registry.provider(s).delete(s)
            ctx.itemDone()
        }
    }
}

@Singleton
class RecycleOperation @Inject constructor(private val bin: RecycleBin) : TransferOperation {
    override suspend fun run(sources: List<FsPath>, destination: FsPath?, ctx: TransferContext) {
        ctx.setTotals(0, sources.size)
        for (s in sources) {
            ctx.checkpoint(); ctx.setCurrent(s.name)
            bin.moveToBin(s, ctx)
            ctx.itemDone()
        }
    }
}

@Singleton
class RestoreOperation @Inject constructor(private val bin: RecycleBin) : TransferOperation {
    override suspend fun run(sources: List<FsPath>, destination: FsPath?, ctx: TransferContext) {
        val ids = ctx.options.recycleIds
        ctx.setTotals(0, ids.size)
        for (id in ids) {
            ctx.checkpoint(); ctx.setCurrent(bin.get(id)?.name)
            bin.restore(id, ctx)
            ctx.itemDone()
        }
    }
}
