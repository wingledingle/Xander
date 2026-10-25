package com.xander.files.data.apps

import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.storage.StorageManager
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import com.xander.files.domain.fs.FileSystemRegistry
import com.xander.files.domain.fs.FsPath
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

data class AppInfo(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val isSystem: Boolean,
    val apkSize: Long,
    val totalSize: Long?,
    val installed: Long,
    val updated: Long,
    val sourceDir: String,
    val splitDirs: List<String>,
    val enabled: Boolean,
    val targetSdk: Int,
    val minSdk: Int,
)

@Singleton
class AppRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: FileSystemRegistry,
) {
    private val pm: PackageManager get() = context.packageManager

    suspend fun list(): List<AppInfo> = withContext(Dispatchers.IO) {
        val flags = PackageManager.GET_META_DATA
        val pkgs: List<PackageInfo> = if (Build.VERSION.SDK_INT >= 33) pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
            else @Suppress("DEPRECATION") pm.getInstalledPackages(flags)
        val stats = if (Build.VERSION.SDK_INT >= 26 && hasUsageAccess()) context.getSystemService(StorageStatsManager::class.java) else null
        pkgs.mapNotNull { p ->
            val ai = p.applicationInfo ?: return@mapNotNull null
            val splits = ai.splitSourceDirs?.toList().orEmpty()
            val apkSize = (listOf(ai.sourceDir) + splits).sumOf { File(it).length() }
            val total = stats?.let { s ->
                runCatching {
                    val st = s.queryStatsForPackage(StorageManager.UUID_DEFAULT, p.packageName, Process.myUserHandle())
                    st.appBytes + st.dataBytes + st.cacheBytes
                }.getOrNull()
            }
            AppInfo(
                packageName = p.packageName,
                label = ai.loadLabel(pm).toString(),
                versionName = p.versionName ?: "",
                versionCode = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong(),
                isSystem = ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                apkSize = apkSize, totalSize = total,
                installed = p.firstInstallTime, updated = p.lastUpdateTime,
                sourceDir = ai.sourceDir, splitDirs = splits, enabled = ai.enabled,
                targetSdk = ai.targetSdkVersion, minSdk = if (Build.VERSION.SDK_INT >= 24) ai.minSdkVersion else 0,
            )
        }
    }

    fun icon(packageName: String, size: Int = 96): Bitmap? = runCatching { pm.getApplicationIcon(packageName).toBitmap(size, size) }.getOrNull()

    fun hasUsageAccess(): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false
        val aom = context.getSystemService(android.app.AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        val mode = aom.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    fun usageAccessIntent() = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    fun launchIntent(pkg: String): Intent? = pm.getLaunchIntentForPackage(pkg) ?: pm.getLeanbackLaunchIntentForPackage(pkg)
    fun appInfoIntent(pkg: String) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
    fun uninstallIntent(pkg: String) = Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")).putExtra(Intent.EXTRA_RETURN_RESULT, true)
    fun playStoreIntent(pkg: String): Intent {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
        return if (market.resolveActivity(pm) != null) market else Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
    }

    /** Backs up an app to [destDir] as .apk (single) or .apks (base + splits). Returns the created path. */
    suspend fun backup(app: AppInfo, destDir: FsPath): FsPath = withContext(Dispatchers.IO) {
        val provider = registry.provider(destDir)
        val safeLabel = app.label.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val base = "${safeLabel}_${app.versionName.replace('/', '_')}"
        if (!provider.exists(destDir)) destDir.parent?.let { provider.mkdir(it, destDir.name) }
        if (app.splitDirs.isEmpty()) {
            val target = destDir.child("$base.apk")
            File(app.sourceDir).inputStream().use { i -> provider.openOutput(target, File(app.sourceDir).length()).use { i.copyTo(it, 1 shl 18) } }
            target
        } else {
            val target = destDir.child("$base.apks")
            provider.openOutput(target).use { out ->
                ZipOutputStream(out).use { zip ->
                    (listOf(app.sourceDir) + app.splitDirs).forEach { path ->
                        val f = File(path)
                        zip.putNextEntry(ZipEntry(if (path == app.sourceDir) "base.apk" else f.name))
                        f.inputStream().use { it.copyTo(zip, 1 shl 18) }
                        zip.closeEntry()
                    }
                }
            }
            target
        }
    }

    /** Copies the APK(s) to cache and returns a share intent. */
    suspend fun shareIntent(app: AppInfo): Intent = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "share_apk").apply { mkdirs() }
        val out = backup(app, FsPath.local(dir.absolutePath))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(out.path))
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("application/vnd.android.package-archive").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), app.label
        )
    }
}
