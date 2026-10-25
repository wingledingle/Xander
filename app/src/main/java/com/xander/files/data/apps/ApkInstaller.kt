package com.xander.files.data.apps

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import com.xander.files.domain.fs.FileSystemRegistry
import com.xander.files.domain.fs.FsException
import com.xander.files.domain.fs.FsPath
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/** Installs .apk and split bundles (.apks / .xapk / .apkm) through PackageInstaller sessions. */
@Singleton
class ApkInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: FileSystemRegistry,
) {
    fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    suspend fun install(path: FsPath) = withContext(Dispatchers.IO) {
        if (!canInstall()) throw FsException.PermissionDenied(path, SecurityException("Allow installing unknown apps for Xander Files first"))
        val local = materialize(path)
        val ext = path.extension
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            try {
                if (ext == "apk") {
                    session.openWrite("base.apk", 0, local.length()).use { out -> local.inputStream().use { it.copyTo(out) }; session.fsync(out) }
                } else {
                    ZipFile(local).use { zip ->
                        val apks = zip.entries().toList().filter { !it.isDirectory && it.name.endsWith(".apk", true) }
                        if (apks.isEmpty()) throw FsException.Generic(
                            if (ext == "apkm") "This APKM bundle is encrypted and cannot be installed" else "No APK files found in this bundle"
                        )
                        for (e in apks) {
                            session.openWrite(e.name.substringAfterLast('/'), 0, e.size).use { out ->
                                zip.getInputStream(e).use { it.copyTo(out) }; session.fsync(out)
                            }
                        }
                        if (ext == "xapk") extractObb(zip)
                    }
                }
                val intent = Intent(context, InstallResultReceiver::class.java).setAction(ACTION_RESULT)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender)
            } catch (e: Exception) {
                session.abandon(); throw FsException.wrap(e, path)
            }
        }
    }

    /** XAPK files may carry OBB data under Android/obb/<pkg>/... */
    private suspend fun extractObb(zip: ZipFile) {
        val obbs = zip.entries().toList().filter { !it.isDirectory && it.name.startsWith("Android/obb/") }
        if (obbs.isEmpty()) return
        val root = FsPath.local(android.os.Environment.getExternalStorageDirectory().absolutePath)
        val p = registry.provider(root)
        for (e in obbs) {
            val target = root.resolve(e.name)
            var cur = root
            for (seg in e.name.substringBeforeLast('/').split('/')) {
                val next = cur.child(seg)
                if (!p.exists(next)) p.mkdir(cur, seg)
                cur = next
            }
            p.openOutput(target, e.size).use { out -> zip.getInputStream(e).use { it.copyTo(out) } }
        }
    }

    private suspend fun materialize(path: FsPath): File {
        if (path.isLocal && File(path.path).canRead()) return File(path.path)
        val out = File(context.cacheDir, "install/${path.name}").apply { parentFile?.mkdirs() }
        registry.provider(path).openInput(path).use { i -> out.outputStream().use { i.copyTo(it) } }
        return out
    }

    companion object { const val ACTION_RESULT = "com.xander.files.INSTALL_RESULT" }
}

/** Receives PackageInstaller callbacks; launches the system confirmation when user action is needed. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { context.startActivity(it) } }
            }
            PackageInstaller.STATUS_SUCCESS -> Toast.makeText(context, "App installed", Toast.LENGTH_SHORT).show()
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Install failed"
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }
    }
}
