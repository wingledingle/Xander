package com.xander.files.data.net

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * Gives callers a plain OutputStream while a background thread consumes the bytes (HTTP PUT bodies,
 * FTP STOR, cloud uploads). close() waits for the consumer and rethrows its failure.
 */
class StreamingUpload(name: String, bufferSize: Int = 1 shl 20, private val consumer: (InputStream) -> Unit) : OutputStream() {
    private val input = PipedInputStream(bufferSize)
    private val output = PipedOutputStream(input)
    private val error = AtomicReference<Throwable?>(null)
    private val thread = Thread({
        try { consumer(input) } catch (t: Throwable) { error.set(t) } finally { runCatching { input.close() } }
    }, "upload-$name").apply { isDaemon = true; start() }
    private var closed = false

    private fun check() { error.get()?.let { throw if (it is IOException) it else IOException(it.message, it) } }

    override fun write(b: Int) { check(); output.write(b) }
    override fun write(b: ByteArray, off: Int, len: Int) {
        check()
        try { output.write(b, off, len) } catch (e: IOException) { check(); throw e }
    }
    override fun flush() { check(); output.flush() }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { output.close() }
        thread.join()
        check()
    }
}

/** InputStream wrapper that runs [onClose] exactly once (returning pooled clients, finishing commands). */
open class ClosingInputStream(private val delegate: InputStream, private val onClose: () -> Unit) : InputStream() {
    private var done = false
    override fun read() = delegate.read()
    override fun read(b: ByteArray, off: Int, len: Int) = delegate.read(b, off, len)
    override fun skip(n: Long) = delegate.skip(n)
    override fun available() = delegate.available()
    override fun close() {
        if (done) return
        done = true
        try { delegate.close() } finally { onClose() }
    }
}
