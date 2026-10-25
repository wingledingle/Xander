package com.xander.files.data.transfer

import com.xander.files.domain.fs.FsException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlin.coroutines.coroutineContext

/** Live control + progress handle for one running transfer. */
class TransferContext(
    val id: String,
    val options: TransferOptions,
    private val conflictBroker: ConflictBroker,
    private val onProgress: (TransferProgress) -> Unit,
) {
    val paused = MutableStateFlow(false)
    @Volatile var cancelled = false

    private var totalBytes = 0L
    private var doneBytes = 0L
    private var totalItems = 0
    private var doneItems = 0
    private var current: String? = null
    private var stickyChoice: ConflictChoice? = options.defaultConflict

    // speed over a sliding window
    private var windowStart = System.nanoTime()
    private var windowBytes = 0L
    private var speed = 0L
    private var lastEmit = 0L

    fun setTotals(bytes: Long, items: Int) { totalBytes = bytes; totalItems = items; emit(true) }
    fun addTotals(bytes: Long, items: Int) { totalBytes += bytes; totalItems += items; emit(true) }
    fun setCurrent(name: String?) { current = name; emit(true) }
    fun itemDone() { doneItems++; emit(false) }

    fun bytes(delta: Long) {
        doneBytes += delta; windowBytes += delta
        val now = System.nanoTime()
        val dt = now - windowStart
        if (dt > 1_000_000_000L) { speed = windowBytes * 1_000_000_000L / dt; windowBytes = 0; windowStart = now }
        emit(false)
    }

    private fun emit(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastEmit < 300) return
        lastEmit = now
        onProgress(TransferProgress(totalBytes, doneBytes, totalItems, doneItems, current, speed))
    }

    fun flush() = emit(true)

    /** Suspends while paused; throws when cancelled. Call between chunks. */
    suspend fun checkpoint() {
        coroutineContext.ensureActive()
        if (cancelled) throw FsException.Cancelled()
        if (paused.value) {
            speed = 0; emit(true)
            paused.first { !it || cancelled }
            if (cancelled) throw FsException.Cancelled()
        }
    }

    /** Resolves a name collision, asking the user unless a sticky "apply to all" choice exists. */
    suspend fun resolveConflict(req: ConflictRequest): ConflictChoice {
        stickyChoice?.let { if (!(it == ConflictChoice.MERGE && !req.isDirectory)) return it }
        val deferred = CompletableDeferred<ConflictAnswer>()
        val answer = conflictBroker.ask(req, deferred)
        if (answer.applyToAll) stickyChoice = answer.choice
        return answer.choice
    }
}

data class TransferProgress(
    val totalBytes: Long, val doneBytes: Long, val totalItems: Int, val doneItems: Int, val current: String?, val speedBps: Long,
) {
    val fraction: Float get() = if (totalBytes > 0) (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
        else if (totalItems > 0) doneItems.toFloat() / totalItems else 0f
    val etaSeconds: Long get() = if (speedBps > 0) (totalBytes - doneBytes) / speedBps else -1
}
