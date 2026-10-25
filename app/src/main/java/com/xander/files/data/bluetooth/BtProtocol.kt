package com.xander.files.data.bluetooth

import kotlinx.coroutines.runBlocking
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Xander direct transfer over an RFCOMM stream:
 * "XFBT" v1, count, then per file: UTF name, size, bytes; receiver answers one 0x06 byte per file.
 */
object BtProtocol {
    private const val MAGIC = 0x58464254 // "XFBT"
    class Outgoing(val name: String, val size: Long, val open: suspend () -> InputStream)

    fun send(out: OutputStream, input: InputStream, files: List<Outgoing>, progress: (String, Long, Long) -> Unit) {
        val o = DataOutputStream(out.buffered(32 * 1024)); val i = DataInputStream(input)
        val total = files.sumOf { it.size }; var done = 0L
        o.writeInt(MAGIC); o.writeInt(1); o.writeInt(files.size); o.writeLong(total)
        for (f in files) {
            o.writeUTF(f.name); o.writeLong(f.size)
            runBlocking { f.open() }.use { src ->
                val buf = ByteArray(32 * 1024); var left = f.size
                while (left > 0) {
                    val n = src.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) throw IOException("File shrank while sending")
                    o.write(buf, 0, n); left -= n; done += n; progress(f.name, done, total)
                }
            }
            o.flush()
            if (i.read() != 0x06) throw IOException("Receiver rejected ${f.name}")
        }
    }

    fun receive(input: InputStream, out: OutputStream, dir: File, progress: (String, Long, Long) -> Unit): List<File> {
        val i = DataInputStream(input.buffered(32 * 1024))
        if (i.readInt() != MAGIC) throw IOException("Not a Xander transfer")
        i.readInt()
        val count = i.readInt()
        val total = i.readLong()
        val saved = ArrayList<File>()
        var done = 0L
        repeat(count) {
            val name = i.readUTF().replace('/', '_').replace('\\', '_').ifBlank { "file" }
            val size = i.readLong()
            val target = unique(dir, name)
            target.outputStream().buffered(64 * 1024).use { o ->
                val buf = ByteArray(32 * 1024); var left = size
                while (left > 0) { val n = i.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) throw IOException("Connection lost"); o.write(buf, 0, n); left -= n; done += n; progress(name, done, total) }
            }
            saved += target
            out.write(0x06); out.flush()
        }
        return saved
    }

    fun unique(dir: File, name: String): File {
        var f = File(dir, name); var n = 1
        val base = name.substringBeforeLast('.'); val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        while (f.exists()) f = File(dir, "$base (${n++})$ext")
        return f
    }
}
