package com.xander.files.ui.tv

import android.graphics.BitmapFactory
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.xander.files.R
import com.xander.files.data.db.TvDeviceEntity
import com.xander.files.data.tv.DiscoveredTv
import com.xander.files.data.tv.TvApp
import com.xander.files.data.tv.TvInfo
import com.xander.files.data.tv.TvKey
import com.xander.files.data.tv.TvProtocol
import com.xander.files.data.tv.TvRepository
import com.xander.files.domain.fs.FsException
import com.xander.files.domain.fs.FsPath
import com.xander.files.ui.common.Format
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@HiltViewModel
class TvListViewModel @Inject constructor(private val repo: TvRepository) : ViewModel() {
    val paired = repo.devices.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val found = MutableStateFlow<List<DiscoveredTv>>(emptyList())
    val error = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)

    init { viewModelScope.launch { runCatching { repo.discover().collect { tv -> if (found.value.none { it.host == tv.host && it.port == tv.port }) found.value = found.value + tv } } } }

    fun pair(host: String, port: Int, code: String, onDone: (TvDeviceEntity) -> Unit) = viewModelScope.launch {
        busy.value = true
        try { onDone(repo.pair(host, port, code)) } catch (e: Exception) { error.value = if (e is FsException.NetworkLost) "Cannot reach $host" else e.message ?: "Pairing failed" }
        finally { busy.value = false }
    }

    fun forget(id: String) = viewModelScope.launch { repo.forget(id) }
}

@Composable
fun TvListScreen(onBack: () -> Unit, onOpen: (String) -> Unit, vm: TvListViewModel = hiltViewModel()) {
    val paired by vm.paired.collectAsStateWithLifecycle()
    val found by vm.found.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var pairing by remember { mutableStateOf<DiscoveredTv?>(null) }
    var byIp by remember { mutableStateOf(false) }

    Scaffold(topBar = {
        TopAppBar(navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            title = { Text(stringResource(R.string.tv_management)) },
            actions = { IconButton(onClick = { byIp = true }) { Icon(Icons.Filled.Add, stringResource(R.string.tv_add_by_ip)) } })
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            if (paired.isNotEmpty()) {
                item { Header(stringResource(R.string.tv_paired)) }
                items(paired, key = { it.id }) { d ->
                    var menu by remember { mutableStateOf(false) }
                    ListItem(modifier = Modifier.clickable { onOpen(d.id) }, leadingContent = { Icon(Icons.Filled.Tv, null) }, headlineContent = { Text(d.name) },
                        supportingContent = { Text("${d.host}:${d.port}") },
                        trailingContent = {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, null) }
                            DropdownMenu(menu, { menu = false }) { DropdownMenuItem(text = { Text(stringResource(R.string.tv_forget)) }, onClick = { menu = false; vm.forget(d.id) }) }
                        })
                }
            }
            item { Header(stringResource(R.string.tv_found)) }
            val unpaired = found.filter { f -> paired.none { it.host == f.host && it.port == f.port } }
            if (unpaired.isEmpty()) item { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text(stringResource(R.string.tv_none_found), Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodySmall) } }
            items(unpaired, key = { it.host + it.port }) { tv ->
                ListItem(modifier = Modifier.clickable { pairing = tv }, leadingContent = { Icon(Icons.Filled.Tv, null) }, headlineContent = { Text(tv.name) },
                    supportingContent = { Text(tv.host) }, trailingContent = { TextButton(onClick = { pairing = tv }) { Text(stringResource(R.string.tv_pair)) } })
            }
        }
    }
    if (pairing != null || byIp) {
        var code by remember { mutableStateOf("") }
        var ip by remember { mutableStateOf(pairing?.host ?: "") }
        val target = pairing
        AlertDialog(onDismissRequest = { pairing = null; byIp = false },
            title = { Text(stringResource(R.string.tv_pair_title, target?.name ?: "TV")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (target == null) OutlinedTextField(ip, { ip = it.trim() }, label = { Text(stringResource(R.string.tv_ip)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    Text(stringResource(R.string.tv_pair_code))
                    OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                    if (busy) CircularProgressIndicator()
                }
            },
            confirmButton = {
                TextButton(enabled = code.length == 6 && ip.isNotEmpty() && !busy, onClick = {
                    val host = ip.substringBefore(':'); val port = ip.substringAfter(':', "").toIntOrNull() ?: target?.port ?: TvProtocol.DEFAULT_PORT
                    vm.pair(host, port, code) { pairing = null; byIp = false; onOpen(it.id) }
                }) { Text(stringResource(R.string.tv_pair)) }
            },
            dismissButton = { TextButton(onClick = { pairing = null; byIp = false }) { Text(stringResource(R.string.cancel)) } })
    }
    error?.let { m -> AlertDialog(onDismissRequest = { vm.error.value = null }, text = { Text(m) }, confirmButton = { TextButton(onClick = { vm.error.value = null }) { Text(stringResource(android.R.string.ok)) } }) }
}

@HiltViewModel
class TvDeviceViewModel @Inject constructor(saved: SavedStateHandle, private val repo: TvRepository) : ViewModel() {
    val id: String = saved.get<String>("id")!!
    val name = MutableStateFlow("TV")
    val info = MutableStateFlow<TvInfo?>(null)
    val apps = MutableStateFlow<List<TvApp>>(emptyList())
    val loadingApps = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    private val icons = ConcurrentHashMap<String, ImageBitmap>()

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        name.value = repo.name(id)
        try { info.value = withContext(Dispatchers.IO) { repo.client(id).info() } } catch (e: Exception) { message.value = errorText(e) }
    }

    private fun errorText(e: Throwable) = when (e) {
        is FsException.NetworkLost -> "Cannot reach the TV. Check that it is on and on the same network."
        is FsException.AuthRequired -> "This TV no longer recognises this phone. Forget it and pair again."
        else -> e.message ?: "Failed"
    }

    private fun op(block: suspend () -> Unit) = viewModelScope.launch { try { withContext(Dispatchers.IO) { block() } } catch (e: Exception) { message.value = errorText(e) } }

    fun loadApps() = viewModelScope.launch {
        loadingApps.value = true
        try { apps.value = withContext(Dispatchers.IO) { repo.client(id).apps() } } catch (e: Exception) { message.value = errorText(e) }
        loadingApps.value = false
    }

    suspend fun icon(pkg: String): ImageBitmap? = icons[pkg] ?: withContext(Dispatchers.IO) {
        runCatching {
            val c = repo.client(id)
            okhttp3.OkHttpClient().newCall(c.iconRequest(pkg)).execute().use { r -> r.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
        }.getOrNull()?.also { icons[pkg] = it }
    }

    fun launch(pkg: String) = op { repo.client(id).launch(pkg) }
    fun uninstall(pkg: String) = op { repo.client(id).uninstall(pkg); message.value = "Confirm the uninstall on the TV screen" }
    fun key(k: TvKey) = op { repo.client(id).key(k) }
    fun text(t: String) = op { repo.client(id).text(t) }

    fun backup(a: TvApp) = op {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Xander TV").apply { mkdirs() }
        val f = File(dir, "${a.label.replace(Regex("[\\\\/:*?\"<>|]"), "_")}-${a.versionName}.apk")
        repo.client(id).downloadApk(a.packageName).use { i -> f.outputStream().use { i.copyTo(it) } }
        message.value = "Saved to ${f.path}"
    }

    fun install(open: () -> java.io.InputStream?, size: Long) = op {
        val input = open() ?: throw FsException.Generic("Cannot read the APK")
        repo.client(id).install(input, size)
        message.value = "Confirm the install on the TV screen"
    }
}

@Composable
fun TvDeviceScreen(onBack: () -> Unit, onBrowse: (FsPath) -> Unit, vm: TvDeviceViewModel = hiltViewModel()) {
    val name by vm.name.collectAsStateWithLifecycle()
    val info by vm.info.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(0) }
    val ctx = LocalContext.current
    val pickApk = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val size = runCatching { ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
            vm.install({ ctx.contentResolver.openInputStream(uri) }, size)
        }
    }
    Scaffold(topBar = {
        Column {
            TopAppBar(navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                title = { Column { Text(name); info?.let { Text("${it.model} · Android ${it.android}", style = MaterialTheme.typography.labelSmall) } } })
            PrimaryTabRow(selectedTabIndex = tab) {
                listOf(R.string.tv_files to Icons.Filled.Folder, R.string.tv_apps to Icons.Filled.Apps, R.string.tv_remote to Icons.Filled.ViewCarousel).forEachIndexed { i, (l, ic) ->
                    Tab(selected = tab == i, onClick = { tab = i; if (i == 1 && vm.apps.value.isEmpty()) vm.loadApps() }, text = { Text(stringResource(l)) }, icon = { Icon(ic, null) })
                }
            }
        }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    info?.let { i ->
                        Text("${Format.size(i.total - i.free)} / ${Format.size(i.total)}", style = MaterialTheme.typography.titleMedium)
                        androidx.compose.material3.LinearProgressIndicator(progress = { if (i.total > 0) (i.total - i.free).toFloat() / i.total else 0f }, Modifier.fillMaxWidth())
                    }
                    Button(onClick = { onBrowse(FsPath(FsPath.SCHEME_TV, vm.id, info?.storageRoot ?: "/storage/emulated/0")) }) {
                        Icon(Icons.Filled.Folder, null); Spacer(Modifier.size(8.dp)); Text(stringResource(R.string.tv_files))
                    }
                    Button(onClick = { pickApk.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) }) {
                        Icon(Icons.Filled.Add, null); Spacer(Modifier.size(8.dp)); Text(stringResource(R.string.tv_install_apk))
                    }
                }
                1 -> AppsTab(vm)
                else -> RemoteTab(vm, info?.remote != false)
            }
        }
    }
    message?.let { m -> AlertDialog(onDismissRequest = { vm.message.value = null }, text = { Text(m) }, confirmButton = { TextButton(onClick = { vm.message.value = null }) { Text(stringResource(android.R.string.ok)) } }) }
}

@Composable
private fun AppsTab(vm: TvDeviceViewModel) {
    val apps by vm.apps.collectAsStateWithLifecycle()
    val loading by vm.loadingApps.collectAsStateWithLifecycle()
    var system by remember { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth().clickable { system = !system }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(system, { system = it }); Text(stringResource(R.string.tv_system_apps))
        }
        if (loading) CircularProgressIndicator(Modifier.padding(24.dp))
        LazyColumn {
            items(apps.filter { system || !it.system || it.launchable && !it.system }, key = { it.packageName }) { a ->
                var menu by remember { mutableStateOf(false) }
                val icon by produceState<ImageBitmap?>(null, a.packageName) { value = vm.icon(a.packageName) }
                ListItem(modifier = Modifier.clickable(enabled = a.launchable) { vm.launch(a.packageName) },
                    leadingContent = { icon?.let { Image(it, null, Modifier.size(width = 64.dp, height = 36.dp)) } ?: Icon(Icons.Filled.Apps, null, Modifier.size(36.dp)) },
                    headlineContent = { Text(a.label) },
                    supportingContent = { Text("${a.versionName} · ${Format.size(a.sizeBytes)}") },
                    trailingContent = {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, null) }
                        DropdownMenu(menu, { menu = false }) {
                            if (a.launchable) DropdownMenuItem(text = { Text(stringResource(R.string.tv_launch)) }, onClick = { menu = false; vm.launch(a.packageName) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.tv_backup_apk)) }, onClick = { menu = false; vm.backup(a) })
                            if (!a.system) DropdownMenuItem(text = { Text(stringResource(R.string.tv_uninstall)) }, onClick = { menu = false; vm.uninstall(a.packageName) })
                        }
                    })
            }
        }
    }
}

@Composable
private fun RemoteTab(vm: TvDeviceViewModel, remoteOn: Boolean) {
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!remoteOn) Text(stringResource(R.string.tv_remote_disabled), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Key(Icons.AutoMirrored.Filled.ArrowBack, R.string.back) { vm.key(TvKey.BACK) }
            Key(Icons.Filled.Home, R.string.home) { vm.key(TvKey.HOME) }
            Key(Icons.Filled.ViewCarousel, R.string.tv_apps) { vm.key(TvKey.RECENTS) }
        }
        Key(Icons.Filled.KeyboardArrowUp, R.string.tv_remote, big = true) { vm.key(TvKey.UP) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Key(Icons.Filled.KeyboardArrowLeft, R.string.tv_remote, big = true) { vm.key(TvKey.LEFT) }
            Key(Icons.Filled.Circle, R.string.tv_remote, big = true) { vm.key(TvKey.CENTER) }
            Key(Icons.Filled.KeyboardArrowRight, R.string.tv_remote, big = true) { vm.key(TvKey.RIGHT) }
        }
        Key(Icons.Filled.KeyboardArrowDown, R.string.tv_remote, big = true) { vm.key(TvKey.DOWN) }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Key(Icons.Filled.SkipPrevious, R.string.previous) { vm.key(TvKey.PREVIOUS) }
            Key(Icons.Filled.PlayArrow, R.string.play) { vm.key(TvKey.PLAY_PAUSE) }
            Key(Icons.Filled.SkipNext, R.string.next) { vm.key(TvKey.NEXT) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Key(Icons.AutoMirrored.Filled.VolumeDown, R.string.tv_remote) { vm.key(TvKey.VOLUME_DOWN) }
            Key(Icons.AutoMirrored.Filled.VolumeOff, R.string.tv_remote) { vm.key(TvKey.MUTE) }
            Key(Icons.AutoMirrored.Filled.VolumeUp, R.string.tv_remote) { vm.key(TvKey.VOLUME_UP) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it }, Modifier.weight(1f), label = { Text(stringResource(R.string.tv_type_text)) }, singleLine = true)
            TextButton(onClick = { vm.text(text) }) { Text(stringResource(R.string.tv_send)) }
        }
    }
}

@Composable
private fun Key(icon: ImageVector, label: Int, big: Boolean = false, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(if (big) 72.dp else 52.dp), shape = CircleShape) {
        Icon(icon, stringResource(label), Modifier.size(if (big) 36.dp else 24.dp))
    }
}

@Composable
private fun Header(text: String) =
    Text(text, Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
