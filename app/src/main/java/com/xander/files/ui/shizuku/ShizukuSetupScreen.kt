package com.xander.files.ui.shizuku

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.xander.files.R
import com.xander.files.data.apps.ApkInstaller
import com.xander.files.data.fs.shizuku.ShizukuFileSystemProvider
import com.xander.files.data.fs.shizuku.ShizukuState
import com.xander.files.domain.fs.FsPath
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

/** Download state of the Shizuku APK: null idle, 0..100 progress, -1 failed. */
@HiltViewModel
class ShizukuSetupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val shizuku: ShizukuFileSystemProvider,
    private val installer: ApkInstaller,
) : ViewModel() {
    val state = shizuku.state
    val download = MutableStateFlow<Int?>(null)
    val error = MutableStateFlow<String?>(null)

    fun refresh() = shizuku.refresh()
    fun canInstall() = installer.canInstall()
    fun installPermissionIntent() = installer.permissionIntent()
    fun allow() = viewModelScope.launch { shizuku.requestPermission() }

    /** Downloads the latest Shizuku release APK from its GitHub page and opens the system installer. */
    fun downloadAndInstall() = viewModelScope.launch {
        error.value = null
        download.value = 0
        runCatching {
            val apk = withContext(Dispatchers.IO) {
                val release = JSONObject(get("https://api.github.com/repos/RikkaApps/Shizuku/releases/latest").readText())
                val assets = release.getJSONArray("assets")
                val asset = (0 until assets.length()).map { assets.getJSONObject(it) }.first { it.getString("name").endsWith(".apk") }
                val out = File(context.cacheDir, "shizuku.apk")
                val c = get(asset.getString("browser_download_url"))
                val total = c.getHeaderField("Content-Length")?.toLongOrNull()?.takeIf { it > 0 } ?: asset.optLong("size", -1)
                c.inputStream.use { inp -> out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024); var done = 0L
                    while (true) { val n = inp.read(buf); if (n < 0) break; o.write(buf, 0, n); done += n; if (total > 0) download.value = (done * 100 / total).toInt() }
                } }
                out
            }
            installer.install(FsPath.local(apk.absolutePath))
        }.onFailure { error.value = it.message ?: it.javaClass.simpleName }
        download.value = null
    }

    private fun get(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = true
        setRequestProperty("Accept", "application/vnd.github+json, */*")
        check(responseCode in 200..299) { "Download failed (HTTP $responseCode)" }
    }

    private fun HttpURLConnection.readText() = inputStream.bufferedReader().use { it.readText() }
}

private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"

/**
 * One screen that walks through Shizuku: install it (downloaded and installed from here), start it (with buttons
 * straight to the settings it needs), and allow Xander. Each step ticks itself off as it happens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShizukuSetupScreen(onBack: () -> Unit, onOpenData: () -> Unit, vm: ShizukuSetupViewModel = hiltViewModel()) {
    val ctx = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val download by vm.download.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    // Re-check when coming back from Shizuku or Settings, and every 2 s while the screen is open.
    LifecycleResumeEffect(Unit) { vm.refresh(); onPauseOrDispose { } }
    LaunchedEffect(Unit) { while (true) { delay(2000); vm.refresh() } }
    val installed = state != ShizukuState.NOT_INSTALLED
    val running = state == ShizukuState.PERMISSION_NEEDED || state == ShizukuState.READY
    val ready = state == ShizukuState.READY
    val devOn = Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.shizuku_setup)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.shizuku_setup_intro), style = MaterialTheme.typography.bodyLarge)
            if (state == ShizukuState.UNSUPPORTED) Text(stringResource(R.string.shizuku_unsupported), color = MaterialTheme.colorScheme.error)

            Step(1, stringResource(R.string.shizuku_step_install), done = installed, active = !installed) {
                if (download != null) {
                    Text(stringResource(R.string.shizuku_downloading, download ?: 0))
                    LinearProgressIndicator(progress = { (download ?: 0) / 100f }, modifier = Modifier.fillMaxWidth())
                } else if (!vm.canInstall()) {
                    Text(stringResource(R.string.shizuku_allow_installs_body))
                    BigButton(stringResource(R.string.shizuku_allow_installs)) { ctx.startActivity(vm.installPermissionIntent()) }
                } else {
                    BigButton(stringResource(R.string.shizuku_install_download)) { vm.downloadAndInstall() }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    BigButton(stringResource(R.string.shizuku_open_download_page), outlined = true) {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/RikkaApps/Shizuku/releases/latest")))
                    }
                }
                BigButton(stringResource(R.string.shizuku_install_play), outlined = true) {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SHIZUKU_PKG"))) }
                        .onFailure { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$SHIZUKU_PKG"))) }
                }
            }

            Step(2, stringResource(R.string.shizuku_step_start), done = running, active = installed && !running) {
                if (Build.VERSION.SDK_INT >= 30) {
                    if (!devOn) {
                        Text(stringResource(R.string.shizuku_dev_options))
                        BigButton(stringResource(R.string.open_about_phone), outlined = true) { ctx.startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) }
                    }
                    Text(stringResource(R.string.shizuku_start_body))
                    BigButton(stringResource(R.string.open_shizuku)) { ctx.packageManager.getLaunchIntentForPackage(SHIZUKU_PKG)?.let(ctx::startActivity) }
                    BigButton(stringResource(R.string.open_developer_options), outlined = true) { runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) } }
                } else {
                    Text(stringResource(R.string.shizuku_start_adb))
                    BigButton(stringResource(R.string.open_shizuku)) { ctx.packageManager.getLaunchIntentForPackage(SHIZUKU_PKG)?.let(ctx::startActivity) }
                }
                Text(stringResource(R.string.shizuku_after_reboot), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Step(3, stringResource(R.string.shizuku_step_allow), done = ready, active = running && !ready) {
                BigButton(stringResource(R.string.shizuku_allow)) { vm.allow() }
            }

            if (ready) {
                Text(stringResource(R.string.shizuku_done), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                BigButton(stringResource(R.string.open_android_data)) { onOpenData() }
            }
        }
    }
}

@Composable
private fun Step(n: Int, title: String, done: Boolean, active: Boolean, content: @Composable () -> Unit) {
    Card(colors = if (active) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else CardDefaults.cardColors()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (done) Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                else Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp)) {
                    Text("$n", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp))
            }
            if (active) content()
        }
    }
}

@Composable
private fun BigButton(text: String, outlined: Boolean = false, onClick: () -> Unit) {
    val m = Modifier.fillMaxWidth().heightIn(min = 52.dp)
    if (outlined) OutlinedButton(onClick = onClick, modifier = m) { Text(text) } else Button(onClick = onClick, modifier = m) { Text(text) }
}
