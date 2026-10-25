package com.xander.files.data.server

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.xander.files.data.settings.SettingsRepository
import com.xander.files.data.storage.StorageVolumes
import com.xander.files.domain.fs.FileSystemRegistry
import com.xander.files.domain.fs.FsPath
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

enum class ServerKind { FTP, HTTP }

data class ServerState(
    val ftpRunning: Boolean = false,
    val httpRunning: Boolean = false,
    val ftpPort: Int = 0,
    val httpPort: Int = 0,
    val addresses: List<String> = emptyList(),
    val ftpClients: Int = 0,
    val error: String? = null,
) { val anyRunning get() = ftpRunning || httpRunning }

/** Owns the FTP and web servers; [ServerService] keeps the process alive while either runs. */
@Singleton
class ServerController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val registry: FileSystemRegistry,
    private val volumes: StorageVolumes,
) {
    private val _state = MutableStateFlow(ServerState())
    val state: StateFlow<ServerState> = _state
    private var ftp: FtpServer? = null
    private var web: WebFileServer? = null

    suspend fun defaultRoot(): FsPath = volumes.volumes().firstOrNull()?.root ?: FsPath.local("/storage/emulated/0")

    private suspend fun root(): ServerRoot {
        val s = settings.settings.first()
        val r = s.serverRoot.takeIf { it.isNotEmpty() }?.let(FsPath::parse) ?: defaultRoot()
        return ServerRoot(r, registry, s.serverReadOnly)
    }

    suspend fun start(kind: ServerKind) {
        val s = settings.settings.first()
        val user = if (s.serverAnonymous) null else s.serverUser
        val pass = if (s.serverAnonymous) null else s.serverPassword
        try {
            when (kind) {
                ServerKind.FTP -> if (ftp == null) {
                    val cs = runCatching { charset(s.serverCharset) }.getOrDefault(Charsets.UTF_8)
                    ftp = FtpServer(root(), s.serverPort, user, pass, cs) { n -> _state.update { it.copy(ftpClients = n) } }.also { it.start() }
                }
                ServerKind.HTTP -> if (web == null) web = WebFileServer(root(), user, pass, s.httpServerPort).also { it.start() }
            }
            publish(null)
            ContextCompat.startForegroundService(context, Intent(context, ServerService::class.java))
        } catch (e: Exception) {
            publish(if (e is java.net.BindException) "Port is already in use" else e.message ?: "Could not start server")
        }
    }

    fun stop(kind: ServerKind) {
        when (kind) {
            ServerKind.FTP -> { ftp?.stop(); ftp = null }
            ServerKind.HTTP -> { web?.stop(); web = null }
        }
        publish(null)
    }

    fun stopAll() { stop(ServerKind.FTP); stop(ServerKind.HTTP) }

    fun refreshAddresses() = publish(_state.value.error)

    private fun publish(error: String?) {
        _state.value = ServerState(
            ftpRunning = ftp != null, httpRunning = web != null,
            ftpPort = ftp?.localPort ?: 0, httpPort = web?.port ?: 0,
            addresses = LocalAddresses.ipv4().mapNotNull { it.hostAddress },
            ftpClients = if (ftp != null) _state.value.ftpClients else 0,
            error = error,
        )
    }
}
