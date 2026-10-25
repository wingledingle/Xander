package com.xander.files.data.net.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ImpersonationLevel
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.PipeShare
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.EnumSet
import java.util.UUID

/**
 * Lists the disk shares of an SMB server with DCE/RPC SRVSVC NetrShareEnum (opnum 15, level 1)
 * over the \PIPE\srvsvc named pipe. Implemented here because smbj has no share enumeration.
 */
object ShareEnumerator {
    data class Share(val name: String, val remark: String, val type: Int)

    private val SRVSVC = UUID.fromString("4b324fc8-1670-01d3-1278-5a47bf6ee188")
    private val NDR = UUID.fromString("8a885d04-1ceb-11c9-9fe8-08002b104860")
    private const val STYPE_SPECIAL = 0x80000000.toInt()

    fun list(session: Session, server: String): List<Share> {
        val share = session.connectShare("IPC$") as PipeShare
        share.use { ps ->
            val pipe = ps.open("srvsvc", SMB2ImpersonationLevel.Impersonation, EnumSet.of(AccessMask.MAXIMUM_ALLOWED), null,
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE), SMB2CreateDisposition.FILE_OPEN, null)
            pipe.use { p ->
                val bindAck = readPdu { p.transact(bind()) }
                if (bindAck.getOrNull(2)?.toInt() != 12) throw IOException("SRVSVC bind rejected")
                val resp = collect(p.transact(request(server))) {
                    val buf = ByteArray(65536)
                    val n = p.read(buf)
                    if (n <= 0) throw IOException("SRVSVC truncated response")
                    buf.copyOf(n)
                }
                return parse(resp).filter { it.type and STYPE_SPECIAL == 0 && (it.type and 0xFF) == 0 }
            }
        }
    }

    private fun readPdu(f: () -> ByteArray) = f()

    /** Concatenates fragment stubs until PFC_LAST_FRAG. */
    private fun collect(first: ByteArray, more: () -> ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var pdu = first
        while (true) {
            val b = ByteBuffer.wrap(pdu).order(ByteOrder.LITTLE_ENDIAN)
            if (pdu[2].toInt() == 3) throw IOException("SRVSVC fault 0x" + Integer.toHexString(b.getInt(24)))
            val flags = pdu[3].toInt()
            val fragLen = b.getShort(8).toInt() and 0xFFFF
            out.write(pdu, 24, fragLen - 24)
            if (flags and 0x02 != 0) break
            pdu = more()
        }
        return out.toByteArray()
    }

    private fun uuid(b: ByteBuffer, u: UUID) {
        val msb = u.mostSignificantBits; val lsb = u.leastSignificantBits
        b.putInt((msb ushr 32).toInt()); b.putShort((msb ushr 16).toShort()); b.putShort(msb.toShort())
        val be = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(lsb).array()
        b.put(be)
    }

    private fun header(b: ByteBuffer, type: Int, len: Int, callId: Int) {
        b.put(5).put(0).put(type.toByte()).put(0x03).putInt(0x10).putShort(len.toShort()).putShort(0).putInt(callId)
    }

    private fun bind(): ByteArray {
        val len = 72
        val b = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN)
        header(b, 11, len, 1)
        b.putShort(4280).putShort(4280).putInt(0)
        b.put(1).put(0).put(0).put(0) // one context item + padding
        b.putShort(0).put(1).put(0)    // context id 0, one transfer syntax
        uuid(b, SRVSVC); b.putShort(3).putShort(0)
        uuid(b, NDR); b.putInt(2)
        return b.array()
    }

    private fun ndrString(b: ByteBuffer, s: String) {
        val chars = s + "\u0000"
        b.putInt(chars.length).putInt(0).putInt(chars.length)
        chars.forEach { b.putChar(it) }
        while (b.position() % 4 != 0) b.put(0)
    }

    private fun request(server: String): ByteArray {
        val stub = ByteBuffer.allocate(256 + server.length * 2).order(ByteOrder.LITTLE_ENDIAN)
        stub.putInt(0x00020000)               // ServerName referent
        ndrString(stub, "\\\\$server")
        stub.putInt(1)                        // level
        stub.putInt(1)                        // union switch
        stub.putInt(0x00020004)               // container referent
        stub.putInt(0)                        // EntriesRead
        stub.putInt(0)                        // Buffer = NULL
        stub.putInt(-1)                       // PreferedMaximumLength
        stub.putInt(0x00020008)               // ResumeHandle referent
        stub.putInt(0)
        val stubLen = stub.position()
        val len = 24 + stubLen
        val b = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN)
        header(b, 0, len, 2)
        b.putInt(stubLen).putShort(0).putShort(15)
        b.put(stub.array(), 0, stubLen)
        return b.array()
    }

    private fun readString(b: ByteBuffer): String {
        b.int // max count
        b.int // offset
        val actual = b.int
        val sb = StringBuilder()
        repeat(actual) { sb.append(b.char) }
        while (b.position() % 4 != 0) b.get()
        return sb.toString().trimEnd('\u0000')
    }

    private fun parse(stub: ByteArray): List<Share> {
        val b = ByteBuffer.wrap(stub).order(ByteOrder.LITTLE_ENDIAN)
        b.int; b.int          // level, switch
        b.int                 // container ref
        val count = b.int
        val bufRef = b.int
        if (bufRef == 0 || count == 0) return emptyList()
        b.int                 // array max count
        val raw = (0 until count).map { Triple(b.int, b.int, b.int) } // netname ref, type, remark ref
        return raw.map { (nameRef, type, remarkRef) ->
            val name = if (nameRef != 0) readString(b) else ""
            val remark = if (remarkRef != 0) readString(b) else ""
            Share(name, remark, type)
        }
    }
}
