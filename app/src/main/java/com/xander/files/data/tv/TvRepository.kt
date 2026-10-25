package com.xander.files.data.tv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.xander.files.data.db.TvDeviceDao
import com.xander.files.data.db.TvDeviceEntity
import com.xander.files.domain.fs.FsException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

data class DiscoveredTv(val name: String, val host: String, val port: Int)

/** Phone side of TV management: finds TVs running Xander, pairs with them and hands out clients. */
@Singleton
class TvRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: TvDeviceDao,
) {
    val devices = dao.observeAll()
    private val clients = ConcurrentHashMap<String, TvClient>()

    suspend fun client(id: String): TvClient = clients[id] ?: run {
        val d = dao.get(id) ?: throw FsException.AuthRequired(id)
        TvClient(d.host, d.port, d.token).also { clients[id] = it }
    }

    suspend fun name(id: String) = dao.get(id)?.name ?: "TV"

    /** Exchanges the code shown on the TV for an access token and remembers the TV. */
    suspend fun pair(host: String, port: Int, code: String): TvDeviceEntity = withContext(Dispatchers.IO) {
        val anon = TvClient(host, port, null)
        anon.hello()
        val (token, name) = anon.pair(code.trim(), Build.MODEL ?: "Phone")
        val id = "tv-" + host.replace('.', '-').replace(':', '-') + "-" + port
        val e = TvDeviceEntity(id, name.ifEmpty { host }, host, port, token)
        dao.upsert(e); clients.remove(id)
        e
    }

    suspend fun forget(id: String) { dao.delete(id); clients.remove(id) }

    /** Paired TVs can change address (DHCP); update when NSD sees the same name elsewhere. */
    suspend fun updateAddress(found: DiscoveredTv) {
        dao.observeAllOnce().firstOrNull { it.name == found.name && (it.host != found.host || it.port != found.port) }?.let {
            dao.upsert(it.copy(host = found.host, port = found.port, lastSeen = System.currentTimeMillis())); clients.remove(it.id)
        }
    }

    /** mDNS/NSD discovery of TVs running the Xander companion. */
    fun discover(): Flow<DiscoveredTv> = callbackFlow {
        val nsd = context.getSystemService(NsdManager::class.java)
        val resolver = Executors.newSingleThreadExecutor()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, code: Int) { close() }
            override fun onStopDiscoveryFailed(t: String, code: Int) {}
            override fun onServiceLost(s: NsdServiceInfo) {}
            override fun onServiceFound(s: NsdServiceInfo) {
                resolver.execute {
                    val latch = java.util.concurrent.CountDownLatch(1)
                    @Suppress("DEPRECATION")
                    nsd.resolveService(s, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(si: NsdServiceInfo, code: Int) { latch.countDown() }
                        override fun onServiceResolved(si: NsdServiceInfo) {
                            @Suppress("DEPRECATION") val host = si.host?.hostAddress
                            if (host != null) {
                                val tv = DiscoveredTv(si.serviceName, host, si.port)
                                trySend(tv); runBlocking { runCatching { updateAddress(tv) } }
                            }
                            latch.countDown()
                        }
                    })
                    latch.await(8, java.util.concurrent.TimeUnit.SECONDS)
                }
            }
        }
        nsd.discoverServices(TvProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose { runCatching { nsd.stopServiceDiscovery(listener) }; resolver.shutdownNow() }
    }
}
