package com.xander.files.data.server

import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FsException
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * FTP server (RFC 959 + RFC 2389 FEAT, 3659 MLSD/MLST/SIZE/MDTM/REST, 2428 EPSV/EPRT) over any storage backend.
 * Passive and active data connections; anonymous or user/password login; read-only option.
 */
class FtpServer(
    private val root: ServerRoot,
    private val port: Int,
    private val user: String?,
    private val password: String?,
    private val charset: Charset = Charsets.UTF_8,
    private val onClientsChanged: (Int) -> Unit = {},
) {
    private var socket: ServerSocket? = null
    private var pool: ExecutorService? = null
    @Volatile var running = false
        private set
    val localPort get() = socket?.localPort ?: port
    private val clients = AtomicInteger()

    fun start() {
        val s = ServerSocket(port).apply { reuseAddress = true }
        socket = s
        val p = Executors.newCachedThreadPool { r -> Thread(r, "xander-ftp").apply { isDaemon = true } }
        pool = p; running = true
        p.execute {
            while (running) {
                val c = try { s.accept() } catch (e: IOException) { break }
                p.execute {
                    onClientsChanged(clients.incrementAndGet())
                    try { Session(c).run() } finally { onClientsChanged(clients.decrementAndGet()) }
                }
            }
        }
    }

    fun stop() { running = false; runCatching { socket?.close() }; pool?.shutdownNow() }

    private inner class Session(private val control: Socket) {
        private val reader = BufferedReader(InputStreamReader(control.getInputStream(), charset))
        private val writer: Writer = OutputStreamWriter(control.getOutputStream(), charset)
        private var cwd = "/"
        private var pendingUser: String? = null
        private var loggedIn = false
        private var passive: ServerSocket? = null
        private var active: InetSocketAddress? = null
        private var rest = 0L
        private var renameFrom: String? = null
        private val fs get() = root.provider

        private fun reply(code: Int, text: String) {
            val lines = text.split('\n')
            if (lines.size == 1) writer.write("$code $text\r\n")
            else { lines.dropLast(1).forEachIndexed { i, l -> writer.write(if (i == 0) "$code-$l\r\n" else " $l\r\n") }; writer.write("$code ${lines.last()}\r\n") }
            writer.flush()
        }

        fun run() = control.use {
            control.soTimeout = 600_000
            reply(220, "Xander Files FTP server ready")
            while (true) {
                val line = try { reader.readLine() } catch (e: IOException) { null } ?: break
                val cmd = line.substringBefore(' ').uppercase()
                val arg = line.substringAfter(' ', "").trimEnd('\r')
                try {
                    if (!handle(cmd, arg)) break
                } catch (e: FsException.NotFound) { reply(550, "No such file or directory")
                } catch (e: FsException.PermissionDenied) { reply(550, "Permission denied")
                } catch (e: FsException) { reply(550, e.message ?: "Failed")
                } catch (e: IOException) { reply(426, "Connection closed; transfer aborted")
                } catch (e: Exception) { reply(550, FsException.wrap(e).message ?: "Failed") }
            }
            runCatching { passive?.close() }
        }

        private fun needWrite(): Boolean { if (root.readOnly) { reply(550, "Server is read-only"); return false }; return true }

        private fun handle(cmd: String, arg: String): Boolean {
            val open = cmd in setOf("USER", "PASS", "QUIT", "FEAT", "SYST", "NOOP", "AUTH", "OPTS", "HELP")
            if (!loggedIn && !open) { reply(530, "Please log in with USER and PASS"); return true }
            when (cmd) {
                "USER" -> {
                    pendingUser = arg
                    if (user.isNullOrEmpty()) { loggedIn = true; reply(230, "Logged in") }
                    else reply(331, "Password required")
                }
                "PASS" -> {
                    if (user.isNullOrEmpty() || (pendingUser == user && arg == password.orEmpty())) { loggedIn = true; reply(230, "Logged in") }
                    else { Thread.sleep(1000); reply(530, "Login incorrect") }
                }
                "AUTH" -> reply(502, "TLS not supported")
                "SYST" -> reply(215, "UNIX Type: L8")
                "FEAT" -> reply(211, "Features:\nUTF8\nSIZE\nMDTM\nREST STREAM\nMLST type*;size*;modify*;\nEPSV\nEPRT\nPASV\nEnd")
                "OPTS" -> reply(200, "OK")
                "HELP" -> reply(214, "See RFC 959")
                "NOOP" -> reply(200, "OK")
                "QUIT" -> { reply(221, "Goodbye"); return false }
                "PWD", "XPWD" -> reply(257, "\"${cwd.replace("\"", "\"\"")}\" is the current directory")
                "CWD", "XCWD" -> {
                    val v = root.virtual(cwd, arg.ifEmpty { "/" })
                    if (stat(v).isDirectory) { cwd = v; reply(250, "Directory changed to $v") } else reply(550, "Not a directory")
                }
                "CDUP", "XCUP" -> { cwd = root.virtual(cwd, ".."); reply(250, "Directory changed to $cwd") }
                "TYPE" -> reply(200, "Type set to ${arg.uppercase()}")
                "MODE" -> if (arg.equals("S", true)) reply(200, "OK") else reply(504, "Only stream mode")
                "STRU" -> if (arg.equals("F", true)) reply(200, "OK") else reply(504, "Only file structure")
                "ALLO" -> reply(202, "No storage allocation necessary")
                "PASV" -> {
                    val s = openPassive()
                    val ip = (control.localAddress as? Inet4Address ?: firstIpv4())?.hostAddress?.replace('.', ',') ?: "127,0,0,1"
                    reply(227, "Entering Passive Mode ($ip,${s.localPort shr 8},${s.localPort and 0xff})")
                }
                "EPSV" -> { val s = openPassive(); reply(229, "Entering Extended Passive Mode (|||${s.localPort}|)") }
                "PORT" -> {
                    val n = arg.split(',').map { it.trim().toInt() }
                    active = InetSocketAddress(InetAddress.getByName("${n[0]}.${n[1]}.${n[2]}.${n[3]}"), n[4] * 256 + n[5])
                    runCatching { passive?.close() }; passive = null
                    reply(200, "PORT command successful")
                }
                "EPRT" -> {
                    val d = arg[0]; val p = arg.trim(d).split(d)
                    active = InetSocketAddress(InetAddress.getByName(p[1]), p[2].toInt())
                    runCatching { passive?.close() }; passive = null
                    reply(200, "EPRT command successful")
                }
                "REST" -> { rest = arg.toLongOrNull() ?: 0; reply(350, "Restarting at $rest") }
                "LIST", "NLST", "MLSD" -> {
                    val target = arg.split(' ').filterNot { it.startsWith("-") }.joinToString(" ")
                    val v = root.virtual(cwd, target.ifEmpty { "." })
                    val e = stat(v)
                    val entries = if (e.isDirectory) runBlocking { fs.list(root.toFs(v)) } else listOf(e)
                    val text = buildString {
                        for (c in entries) when (cmd) {
                            "NLST" -> append(c.name).append("\r\n")
                            "MLSD" -> append(facts(c)).append(' ').append(c.name).append("\r\n")
                            else -> append(unixLine(c)).append("\r\n")
                        }
                    }
                    data(150, "Opening data connection for listing") { it.write(text.toByteArray(charset)) }
                }
                "MLST" -> { val v = root.virtual(cwd, arg.ifEmpty { "." }); reply(250, "Listing $v\n${facts(stat(v))} $v\nEnd") }
                "SIZE" -> { val e = stat(root.virtual(cwd, arg)); if (e.isDirectory) reply(550, "Not a plain file") else reply(213, e.size.toString()) }
                "MDTM" -> reply(213, mdtm(stat(root.virtual(cwd, arg)).lastModified))
                "RETR" -> {
                    val v = root.virtual(cwd, arg); val e = stat(v)
                    if (e.isDirectory) { reply(550, "Is a directory"); return true }
                    val offset = rest; rest = 0
                    val input = runBlocking { fs.openInput(root.toFs(v), offset) }
                    data(150, "Opening BINARY data connection for ${e.name} (${e.size} bytes)") { out -> input.use { it.copyTo(out, 64 * 1024) } }
                }
                "STOR", "APPE" -> {
                    if (!needWrite()) return true
                    val v = root.virtual(cwd, arg)
                    val parent = root.toFs(v.substringBeforeLast('/').ifEmpty { "/" })
                    val name = v.substringAfterLast('/')
                    val offset = rest; rest = 0
                    val target = runBlocking { fs.createFileOrReplace(parent, name) }
                    if (cmd == "APPE" || offset > 0) {
                        // Backends cannot append in place: rebuild the file from the kept prefix plus the upload.
                        val keep = if (cmd == "APPE") Long.MAX_VALUE else offset
                        val tmp = java.io.File.createTempFile("ftp", ".part")
                        try {
                            runCatching { runBlocking { fs.openInput(target) }.use { i -> tmp.outputStream().use { o -> copyLimited(i, o, keep) } } }
                            dataIn(java.io.FileOutputStream(tmp, true))
                            runBlocking { fs.openOutput(target, tmp.length()) }.use { o -> tmp.inputStream().use { it.copyTo(o, 64 * 1024) } }
                        } finally { tmp.delete() }
                    } else {
                        dataIn(runBlocking { fs.openOutput(target) })
                    }
                }
                "DELE" -> { if (!needWrite()) return true; val v = root.virtual(cwd, arg); if (stat(v).isDirectory) reply(550, "Is a directory") else { runBlocking { fs.delete(root.toFs(v)) }; reply(250, "Deleted") } }
                "RMD", "XRMD" -> { if (!needWrite()) return true; val v = root.virtual(cwd, arg); if (v == "/") reply(550, "Cannot remove root") else { runBlocking { fs.delete(root.toFs(v)) }; reply(250, "Removed") } }
                "MKD", "XMKD" -> {
                    if (!needWrite()) return true
                    val v = root.virtual(cwd, arg)
                    runBlocking { fs.mkdir(root.toFs(v.substringBeforeLast('/').ifEmpty { "/" }), v.substringAfterLast('/')) }
                    reply(257, "\"$v\" created")
                }
                "RNFR" -> { if (!needWrite()) return true; val v = root.virtual(cwd, arg); stat(v); renameFrom = v; reply(350, "Ready for RNTO") }
                "RNTO" -> {
                    val from = renameFrom ?: run { reply(503, "RNFR first"); return true }
                    renameFrom = null
                    val to = root.virtual(cwd, arg)
                    val fromParent = from.substringBeforeLast('/').ifEmpty { "/" }; val toParent = to.substringBeforeLast('/').ifEmpty { "/" }
                    runBlocking {
                        if (fromParent == toParent) fs.rename(root.toFs(from), to.substringAfterLast('/'))
                        else fs.moveInternal(root.toFs(from), root.toFs(toParent), to.substringAfterLast('/')) ?: throw FsException.Generic("Move across folders is not supported here")
                    }
                    reply(250, "Renamed")
                }
                "SITE" -> reply(202, "SITE not implemented")
                "ABOR" -> reply(226, "No transfer to abort")
                "STAT" -> reply(211, "Xander FTP: logged in, cwd $cwd")
                else -> reply(502, "Command not implemented")
            }
            return true
        }

        private fun stat(v: String): FileEntry = runBlocking { fs.stat(root.toFs(v)) }

        private fun openPassive(): ServerSocket {
            runCatching { passive?.close() }
            active = null
            return ServerSocket(0, 1, control.localAddress).apply { soTimeout = 30_000 }.also { passive = it }
        }

        private fun connectData(): Socket? {
            passive?.let { s -> return try { s.accept() } finally { runCatching { s.close() }; passive = null } }
            active?.let { a -> return Socket().apply { connect(a, 15_000) } }
            return null
        }

        private fun data(code: Int, msg: String, write: (OutputStream) -> Unit) {
            reply(code, msg)
            val s = connectData() ?: run { reply(425, "Use PASV or PORT first"); return }
            try { s.use { sock -> sock.getOutputStream().buffered(64 * 1024).let { write(it); it.flush() } }; reply(226, "Transfer complete") }
            catch (e: IOException) { reply(426, "Transfer aborted") }
        }

        private fun dataIn(target: OutputStream) {
            reply(150, "Ready to receive")
            val s = connectData() ?: run { target.close(); reply(425, "Use PASV or PORT first"); return }
            try { s.use { sock -> target.use { o -> sock.getInputStream().copyTo(o, 64 * 1024) } }; reply(226, "Transfer complete") }
            catch (e: IOException) { reply(426, "Transfer aborted") }
        }
    }

    private fun copyLimited(i: InputStream, o: OutputStream, limit: Long) {
        val buf = ByteArray(64 * 1024); var left = limit
        while (left > 0) { val n = i.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) break; o.write(buf, 0, n); left -= n }
    }

    private fun firstIpv4(): InetAddress? = LocalAddresses.ipv4().firstOrNull()

    companion object {
        private val utc = TimeZone.getTimeZone("UTC")
        fun mdtm(t: Long): String = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = utc }.format(Date(t))
        fun facts(e: FileEntry) = "type=${if (e.isDirectory) "dir" else "file"};size=${e.size};modify=${mdtm(e.lastModified)};"
        fun unixLine(e: FileEntry): String {
            val sixMonths = System.currentTimeMillis() - 182L * 86_400_000
            val fmt = if (e.lastModified > sixMonths) "MMM dd HH:mm" else "MMM dd  yyyy"
            val date = SimpleDateFormat(fmt, Locale.US).format(Date(e.lastModified))
            val perms = if (e.isDirectory) "drwxr-xr-x" else "-rw-r--r--"
            return "$perms 1 owner group ${e.size.toString().padStart(12)} $date ${e.name}"
        }
    }
}

object LocalAddresses {
    /** Site-local IPv4 addresses of up interfaces (Wi-Fi, Ethernet, hotspot), Wi-Fi first. */
    fun ipv4(): List<InetAddress> = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else if (it.name.startsWith("eth")) 1 else if (it.name.startsWith("ap") || it.name.startsWith("swlan")) 2 else 3 }
            .flatMap { it.inetAddresses.toList() }
            .filter { it is Inet4Address && !it.isLoopbackAddress }
    }.getOrDefault(emptyList())
}
