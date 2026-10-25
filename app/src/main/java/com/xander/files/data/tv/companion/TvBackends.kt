package com.xander.files.data.tv.companion

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.graphics.drawable.toBitmap
import com.xander.files.data.apps.ApkInstaller
import com.xander.files.data.tv.TvApp
import com.xander.files.data.tv.TvAppsBackend
import com.xander.files.data.tv.TvPairing
import com.xander.files.domain.fs.FsPath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

class AndroidTvApps(private val context: Context, private val installer: ApkInstaller) : TvAppsBackend {
    private val pm = context.packageManager

    /** Activities must be started from a context the system lets start them: the bound accessibility service if possible. */
    private fun starter(): Context = TvRemoteService.instance ?: context

    override fun list(): List<TvApp> = pm.getInstalledApplications(0).map { a ->
        val pi = runCatching { pm.getPackageInfo(a.packageName, 0) }.getOrNull()
        @Suppress("DEPRECATION") val code = if (Build.VERSION.SDK_INT >= 28) pi?.longVersionCode ?: 0 else pi?.versionCode?.toLong() ?: 0
        val size = (listOf(a.sourceDir) + (a.splitSourceDirs?.toList() ?: emptyList())).sumOf { runCatching { File(it).length() }.getOrDefault(0) }
        TvApp(a.packageName, a.loadLabel(pm).toString(), pi?.versionName ?: "", code, a.flags and ApplicationInfo.FLAG_SYSTEM != 0, size, launchIntent(a.packageName) != null)
    }.sortedBy { it.label.lowercase() }

    private fun launchIntent(pkg: String): Intent? = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)

    override fun icon(pkg: String): ByteArray? = runCatching {
        val a = pm.getApplicationInfo(pkg, 0)
        val d = a.loadBanner(pm) ?: a.loadIcon(pm)
        val w = d.intrinsicWidth.coerceIn(48, 320); val h = d.intrinsicHeight.coerceIn(48, 180)
        ByteArrayOutputStream().also { d.toBitmap(w, h).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }.getOrNull()

    override fun launch(pkg: String): Boolean = runCatching {
        starter().startActivity((launchIntent(pkg) ?: return false).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    }.getOrDefault(false)

    override fun uninstall(pkg: String): Boolean = runCatching {
        @Suppress("DEPRECATION")
        starter().startActivity(Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    }.getOrDefault(false)

    override fun install(apk: File): Boolean = runCatching { runBlocking { installer.install(FsPath.local(apk.path)) }; true }.getOrDefault(false)

    override fun apkFile(pkg: String): File? = runCatching { File(pm.getApplicationInfo(pkg, 0).sourceDir) }.getOrNull()?.takeIf { it.canRead() }
}

/** Six-digit pairing code shown on the TV; tokens are stored hashed. */
class PrefsTvPairing(private val context: Context) : TvPairing {
    private val prefs: SharedPreferences = context.getSharedPreferences("tv_pairing", Context.MODE_PRIVATE)
    private val random = SecureRandom()
    private var failures = 0
    private val _code = MutableStateFlow(prefs.getString("code", null) ?: newCode())
    val codeFlow: StateFlow<String> = _code

    override val deviceName: String get() =
        runCatching { Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "Android TV"

    private fun newCode(): String = "%06d".format(random.nextInt(1_000_000)).also { prefs.edit().putString("code", it).apply() }

    fun rotate() { _code.value = newCode(); failures = 0 }

    override fun code() = _code.value
    @Synchronized override fun failedAttempt() { if (++failures >= 5) rotate() }

    override fun issueToken(clientName: String): String {
        val t = ByteArray(24).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val set = prefs.getStringSet("tokens", emptySet())!!.toMutableSet().apply { add(hash(t)) }
        prefs.edit().putStringSet("tokens", set).putString("client_" + hash(t).take(12), clientName).apply()
        rotate()
        return t
    }

    override fun valid(token: String?): Boolean = token != null && prefs.getStringSet("tokens", emptySet())!!.contains(hash(token))

    fun pairedCount() = prefs.getStringSet("tokens", emptySet())!!.size
    fun revokeAll() { prefs.edit().remove("tokens").apply() }

    private fun hash(t: String) = MessageDigest.getInstance("SHA-256").digest(t.toByteArray()).joinToString("") { "%02x".format(it) }
}
