package com.xander.files.data.tv.companion

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.xander.files.BuildConfig
import com.xander.files.R
import com.xander.files.data.apps.ApkInstaller
import com.xander.files.data.fs.local.LocalFileSystemProvider
import com.xander.files.data.server.LocalAddresses
import com.xander.files.data.server.MiniHttpServer
import com.xander.files.data.tv.TvCompanionCore
import com.xander.files.data.tv.TvInfo
import com.xander.files.data.tv.TvProtocol
import com.xander.files.domain.fs.FsPath
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class CompanionState(val running: Boolean = false, val port: Int = 0, val addresses: List<String> = emptyList(), val remoteEnabled: Boolean = false, val error: String? = null)

/** TV build: runs the companion API that the phone app uses for TV file/app management and remote control. */
@Singleton
class TvCompanionController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val local: LocalFileSystemProvider,
    private val installer: ApkInstaller,
) {
    val pairing = PrefsTvPairing(context)
    private val _state = MutableStateFlow(CompanionState())
    val state: StateFlow<CompanionState> = _state
    private var server: MiniHttpServer? = null
    private var nsdListener: NsdManager.RegistrationListener? = null

    private fun info(): TvInfo {
        val root = Environment.getExternalStorageDirectory().absolutePath
        val st = runCatching { StatFs(root) }.getOrNull()
        return TvInfo(pairing.deviceName, "${Build.MANUFACTURER} ${Build.MODEL}", Build.VERSION.RELEASE, root,
            st?.totalBytes ?: 0, st?.availableBytes ?: 0, TvRemoteService.instance != null)
    }

    @Synchronized fun start() {
        if (server?.running == true) { refresh(); return }
        val core = TvCompanionCore(local, FsPath.SCHEME_FILE, ::info, AndroidTvApps(context, installer), TvRemoteService.backend, pairing, context.cacheDir)
        val s = try { MiniHttpServer(TvProtocol.DEFAULT_PORT, core::handle).also { it.start() } }
        catch (e: java.net.BindException) { MiniHttpServer(0, core::handle).also { it.start() } }
        catch (e: Exception) { _state.value = CompanionState(error = e.message); return }
        server = s
        register(s.localPort)
        refresh()
        ContextCompat.startForegroundService(context, Intent(context, TvCompanionService::class.java))
    }

    @Synchronized fun stop() {
        server?.stop(); server = null
        nsdListener?.let { l -> runCatching { context.getSystemService(NsdManager::class.java).unregisterService(l) } }
        nsdListener = null
        refresh()
    }

    fun refresh() {
        _state.value = CompanionState(server?.running == true, server?.localPort ?: 0, LocalAddresses.ipv4().mapNotNull { it.hostAddress }, TvRemoteService.instance != null)
    }

    private fun register(port: Int) {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return
        val info = NsdServiceInfo().apply { serviceName = pairing.deviceName; serviceType = TvProtocol.SERVICE_TYPE; setPort(port) }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) {}
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
        }
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l); nsdListener = l }
    }
}

@AndroidEntryPoint
class TvCompanionService : Service() {
    @Inject lateinit var controller: TvCompanionController
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.tv_companion), NotificationManager.IMPORTANCE_MIN))
        val n = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.tv_companion)).setContentText(getString(R.string.tv_companion_running)).setOngoing(true).build()
        ServiceCompat.startForeground(this, 4301, n, if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        controller.start()
        return START_STICKY
    }

    override fun onDestroy() { controller.stop(); super.onDestroy() }

    companion object { const val CHANNEL = "tv_companion" }
}

/** Starts the companion after the TV boots (TV build only). */
class TvBootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (!BuildConfig.IS_TV_BUILD) return
        if (i.action == Intent.ACTION_BOOT_COMPLETED || i.action == "android.intent.action.QUICKBOOT_POWERON")
            runCatching { ContextCompat.startForegroundService(c, Intent(c, TvCompanionService::class.java)) }
    }
}
