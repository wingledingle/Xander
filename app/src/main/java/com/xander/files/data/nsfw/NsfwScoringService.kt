package com.xander.files.data.nsfw

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject

/**
 * Scores files in its own process (":nsfw"). Android's image and video decoders can crash natively on a damaged file;
 * in the app's process that closed whatever screen was open (Settings, when detection was switched on). Here only
 * this service dies, the scan notes the file and moves on.
 */
@AndroidEntryPoint
class NsfwScoringService : Service() {
    @Inject lateinit var scorer: NsfwScorer
    private val thread = HandlerThread("nsfw-score").apply { start() }

    private val messenger by lazy {
        Messenger(Handler(thread.looper) { msg ->
            val path = msg.data.getString("path"); val video = msg.data.getBoolean("video")
            val r = path?.let { p -> runCatching { if (video) scorer.video(File(p)) else scorer.image(File(p)) } }
            val reply = Message.obtain(null, 0).apply {
                data = Bundle().apply {
                    r?.getOrNull()?.let { putFloatArray("scores", floatArrayOf(it.drawings, it.hentai, it.neutral, it.porn, it.sexy)) }
                    r?.exceptionOrNull()?.let { putString("error", android.util.Log.getStackTraceString(it).take(1500)) }
                }
            }
            runCatching { msg.replyTo?.send(reply) }
            true
        })
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder
    override fun onDestroy() { thread.quitSafely(); super.onDestroy() }
}

/** The scan worker's side: sends one file at a time and survives the service process dying. */
class NsfwScoringClient(private val context: Context) {
    class Crashed : Exception("the decoder crashed on this file")

    private var service: Messenger? = null
    private var connected = CompletableDeferred<Messenger>()
    private var died: CompletableDeferred<Unit>? = null
    private val replies = HandlerThread("nsfw-reply").apply { start() }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
            val m = Messenger(binder); service = m
            runCatching { binder.linkToDeath({ died?.complete(Unit) }, 0) }
            connected.complete(m)
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null; connected = CompletableDeferred(); died?.complete(Unit) }
    }

    private suspend fun messenger(): Messenger? {
        service?.let { return it }
        if (!context.bindService(Intent(context, NsfwScoringService::class.java), conn, Context.BIND_AUTO_CREATE)) return null
        return withTimeoutOrNull(30_000) { connected.await() }
    }

    /** Scores for [f]; null if it isn't an image/video worth scoring. Throws [Crashed] if the decoder died on it. */
    suspend fun score(f: File, video: Boolean): NsfwScores? {
        val m = messenger() ?: throw IllegalStateException("scoring service unavailable")
        val result = CompletableDeferred<Bundle>()
        val gone = CompletableDeferred<Unit>().also { died = it }
        val msg = Message.obtain(null, 1).apply {
            data = Bundle().apply { putString("path", f.path); putBoolean("video", video) }
            replyTo = Messenger(Handler(replies.looper) { result.complete(it.data); true })
        }
        try { m.send(msg) } catch (e: android.os.RemoteException) { reset(); throw Crashed() }
        // A slow video can take a while; two minutes without an answer counts as a hang.
        val b = withTimeoutOrNull(120_000) {
            kotlinx.coroutines.selects.select { result.onAwait { it }; gone.onAwait { null } }
        }
        if (b == null) { reset(); throw Crashed() }
        b.getString("error")?.let { throw RuntimeException(it) }
        return b.getFloatArray("scores")?.let { NsfwScores(it[0], it[1], it[2], it[3], it[4]) }
    }

    /** Drops the dead or hung service; the next file binds a fresh one. */
    private fun reset() { service = null; runCatching { context.unbindService(conn) }; connected = CompletableDeferred() }

    fun close() { runCatching { context.unbindService(conn) }; replies.quitSafely() }
}
