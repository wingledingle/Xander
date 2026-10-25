package com.xander.files.bluetooth

import com.xander.files.data.bluetooth.BtProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.concurrent.thread

class BtProtocolTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun sendsFilesAndAvoidsOverwrites() {
        val dir = tmp.newFolder()
        dir.resolve("a.txt").writeText("existing")
        val toReceiver = PipedOutputStream(); val receiverIn = PipedInputStream(toReceiver, 1 shl 16)
        val toSender = PipedOutputStream(); val senderIn = PipedInputStream(toSender, 16)
        val big = ByteArray(300_000) { (it * 7).toByte() }
        var saved: List<java.io.File> = emptyList()
        val r = thread { saved = BtProtocol.receive(receiverIn, toSender, dir) { _, _, _ -> } }
        var last = 0L
        BtProtocol.send(toReceiver, senderIn, listOf(
            BtProtocol.Outgoing("a.txt", 5) { "hello".byteInputStream() },
            BtProtocol.Outgoing("../evil/b.bin", big.size.toLong()) { big.inputStream() },
        )) { _, done, _ -> last = done }
        r.join(5000)
        assertEquals(listOf("a (1).txt", ".._evil_b.bin"), saved.map { it.name })
        assertEquals("hello", saved[0].readText()); assertArrayEquals(big, saved[1].readBytes())
        assertEquals("existing", dir.resolve("a.txt").readText())
        assertEquals(300_005L, last)
    }
}
