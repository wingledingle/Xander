package com.xander.files.data.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

data class LanHost(val ip: String, val name: String?, val services: Set<ConnType>, val ports: Map<ConnType, Int>)

/** Finds SMB / FTP / SFTP / WebDAV / NFS / HTTP servers on the current Wi-Fi or Ethernet network. */
@Singleton
class LanScanner @Inject constructor(@ApplicationContext private val context: Context) {

    data class Subnet(val address: Inet4Address, val prefix: Int)

    fun currentSubnet(): Subnet? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val nets = cm.allNetworks.filter {
            val caps = cm.getNetworkCapabilities(it) ?: return@filter false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }
        for (n in nets) {
            val lp: LinkProperties = cm.getLinkProperties(n) ?: continue
            lp.linkAddresses.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }?.let {
                return Subnet(it.address as Inet4Address, it.prefixLength)
            }
        }
        return null
    }

    fun localIp(): String? = currentSubnet()?.address?.hostAddress

    private val probes = listOf(
        ConnType.SMB to 445, ConnType.FTP to 21, ConnType.SFTP to 22, ConnType.NFS to 2049,
        ConnType.WEBDAV to 5005, ConnType.HTTP to 80, ConnType.HTTP to 8080,
    )

    fun scan(): Flow<LanHost> = channelFlow {
        val subnet = currentSubnet() ?: return@channelFlow
        launch { discoverMdns(this@channelFlow) }
        val base = subnet.address.address.let { (it[0].toInt() and 0xFF shl 24) or (it[1].toInt() and 0xFF shl 16) or (it[2].toInt() and 0xFF shl 8) or (it[3].toInt() and 0xFF) }
        // Cap very large subnets to the local /22 so a scan finishes in seconds.
        val prefix = subnet.prefix.coerceIn(22, 30)
        val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
        val network = base and mask
        val count = (1 shl (32 - prefix)) - 2
        val sem = Semaphore(96)
        for (i in 1..count) {
            val ip = network + i
            if (ip == base) continue
            launch {
                sem.withPermit {
                    val addr = "${ip ushr 24 and 0xFF}.${ip ushr 16 and 0xFF}.${ip ushr 8 and 0xFF}.${ip and 0xFF}"
                    val open = probes.filter { (_, port) -> isOpen(addr, port) }
                    if (open.isNotEmpty()) {
                        val services = open.map { it.first }.toSet()
                        val name = (if (ConnType.SMB in services) netbiosName(addr) else null) ?: reverseName(addr)
                        send(LanHost(addr, name, services, open.associate { it.first to it.second }))
                    }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun isOpen(ip: String, port: Int, timeout: Int = 350): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), timeout); true }
    } catch (e: Exception) { false }

    private fun reverseName(ip: String): String? = runCatching {
        InetAddress.getByName(ip).canonicalHostName.takeIf { it != ip }
    }.getOrNull()

    /** NetBIOS node status (NBSTAT) query on UDP 137 to get a Windows/Samba machine name. */
    fun netbiosName(ip: String): String? = runCatching {
        DatagramSocket().use { s ->
            s.soTimeout = 600
            val q = ByteArray(50)
            q[0] = 0x13; q[1] = 0x37          // transaction id
            q[5] = 1                          // one question
            q[12] = 0x20                      // encoded name length 32
            q[13] = 'C'.code.toByte(); q[14] = 'K'.code.toByte() // '*' encoded
            for (i in 15 until 45) q[i] = 'A'.code.toByte()
            q[45] = 0; q[46] = 0; q[47] = 0x21; q[48] = 0; q[49] = 1 // type NBSTAT, class IN
            s.send(DatagramPacket(q, q.size, InetAddress.getByName(ip), 137))
            val buf = ByteArray(1024)
            val p = DatagramPacket(buf, buf.size)
            s.receive(p)
            val names = buf[56].toInt() and 0xFF
            (0 until names).map { i -> String(buf, 57 + i * 18, 15).trim() to (buf[57 + i * 18 + 15].toInt() and 0xFF) }
                .firstOrNull { it.second == 0x20 || it.second == 0x00 }?.first
        }
    }.getOrNull()

    private suspend fun discoverMdns(out: ProducerScope<LanHost>) {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return
        val types = mapOf("_smb._tcp." to ConnType.SMB, "_ftp._tcp." to ConnType.FTP, "_sftp-ssh._tcp." to ConnType.SFTP,
            "_webdav._tcp." to ConnType.WEBDAV, "_webdavs._tcp." to ConnType.WEBDAV, "_nfs._tcp." to ConnType.NFS)
        val listeners = ArrayList<NsdManager.DiscoveryListener>()
        for ((type, conn) in types) {
            val l = object : NsdManager.DiscoveryListener {
                override fun onServiceFound(info: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(i: NsdServiceInfo?, code: Int) {}
                        override fun onServiceResolved(i: NsdServiceInfo) {
                            @Suppress("DEPRECATION") val host = (if (Build.VERSION.SDK_INT >= 34) i.hostAddresses.firstOrNull() else i.host) as? Inet4Address ?: return
                            out.trySend(LanHost(host.hostAddress!!, i.serviceName, setOf(conn), mapOf(conn to i.port)))
                        }
                    })
                }
                override fun onDiscoveryStarted(t: String?) {}
                override fun onDiscoveryStopped(t: String?) {}
                override fun onServiceLost(i: NsdServiceInfo?) {}
                override fun onStartDiscoveryFailed(t: String?, c: Int) {}
                override fun onStopDiscoveryFailed(t: String?, c: Int) {}
            }
            runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l); listeners += l }
        }
        try { kotlinx.coroutines.delay(8000) } finally { listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } } }
    }
}
