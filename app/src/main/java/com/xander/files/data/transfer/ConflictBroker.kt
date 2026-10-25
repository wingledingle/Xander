package com.xander.files.data.transfer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Hands conflict questions from background transfers to whatever UI is showing; one at a time. */
@Singleton
class ConflictBroker @Inject constructor() {
    data class Pending(val request: ConflictRequest, val answer: CompletableDeferred<ConflictAnswer>)

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending
    private val mutex = Mutex()
    var onAsk: ((ConflictRequest) -> Unit)? = null

    suspend fun ask(req: ConflictRequest, deferred: CompletableDeferred<ConflictAnswer>): ConflictAnswer = mutex.withLock {
        _pending.value = Pending(req, deferred)
        onAsk?.invoke(req)
        try { deferred.await() } finally { _pending.value = null }
    }

    fun answer(choice: ConflictChoice, applyToAll: Boolean) {
        _pending.value?.answer?.complete(ConflictAnswer(choice, applyToAll))
    }

    fun cancelPending(transferId: String) {
        val p = _pending.value ?: return
        if (p.request.transferId == transferId) p.answer.complete(ConflictAnswer(ConflictChoice.SKIP, true))
    }
}
