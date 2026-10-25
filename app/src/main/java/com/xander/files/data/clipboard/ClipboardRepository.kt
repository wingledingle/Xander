package com.xander.files.data.clipboard

import com.xander.files.data.db.ClipboardDao
import com.xander.files.data.db.ClipboardEntity
import com.xander.files.data.transfer.TransferManager
import com.xander.files.data.transfer.TransferRequest
import com.xander.files.data.transfer.TransferType
import com.xander.files.domain.fs.FsPath
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

enum class ClipOp { COPY, CUT }

data class ClipItem(val id: Long, val op: ClipOp, val paths: List<FsPath>, val label: String, val createdAt: Long)

/** Persistent multi-entry clipboard spanning all backends (local, network, cloud, archives). */
@Singleton
class ClipboardRepository @Inject constructor(
    private val dao: ClipboardDao,
    private val transfers: TransferManager,
) {
    private val ser = ListSerializer(String.serializer())

    val items: Flow<List<ClipItem>> = dao.observeAll().map { list -> list.map { it.toItem() } }

    private fun ClipboardEntity.toItem() = ClipItem(
        id, ClipOp.valueOf(operation), Json.decodeFromString(ser, pathsJson).map(FsPath::parse), label, createdAt
    )

    suspend fun add(op: ClipOp, paths: List<FsPath>): Long {
        val label = if (paths.size == 1) paths.first().name else "${paths.size} items · ${paths.first().parent?.name ?: ""}"
        return dao.insert(ClipboardEntity(operation = op.name, pathsJson = Json.encodeToString(ser, paths.map { it.toString() }), label = label))
    }

    /** Pastes one clipboard entry into [destination] via the transfer engine. Cut entries are consumed. */
    suspend fun paste(id: Long, destination: FsPath): String? {
        val e = dao.get(id)?.toItem() ?: return null
        val type = if (e.op == ClipOp.CUT) TransferType.MOVE else TransferType.COPY
        val title = (if (e.op == ClipOp.CUT) "Moving " else "Copying ") + e.label
        val tid = transfers.enqueue(TransferRequest(type, e.paths.map { it.toString() }, destination.toString(), title))
        if (e.op == ClipOp.CUT) dao.delete(id)
        return tid
    }

    suspend fun remove(id: Long) = dao.delete(id)
    suspend fun clear() = dao.clear()
}
