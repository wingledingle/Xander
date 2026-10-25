package com.xander.files.ui.server

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.xander.files.R
import com.xander.files.data.server.ServerController
import com.xander.files.data.server.ServerKind
import com.xander.files.data.settings.AppSettings
import com.xander.files.data.settings.SettingsRepository
import com.xander.files.domain.fs.FsPath
import com.xander.files.ui.ops.FolderPickerDialog
import com.xander.files.ui.settings.ChoiceDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ServerViewModel @Inject constructor(
    val controller: ServerController,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {
    val state = controller.state
    val settings = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    var defaultRoot by mutableStateOf<FsPath?>(null)
        private set

    init { controller.refreshAddresses(); viewModelScope.launch { defaultRoot = controller.defaultRoot() } }

    fun toggle(kind: ServerKind, on: Boolean) = viewModelScope.launch { if (on) controller.start(kind) else controller.stop(kind) }
    fun update(t: (AppSettings) -> AppSettings) = viewModelScope.launch { settingsRepo.update(t) }
}

@Composable
fun ServerScreen(onBack: () -> Unit, vm: ServerViewModel = hiltViewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var pickRoot by remember { mutableStateOf(false) }
    var charsetDialog by remember { mutableStateOf(false) }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) }
    val ip = st.addresses.firstOrNull()
    val credentialsOk = s.serverAnonymous || s.serverPassword.isNotEmpty()
    val locked = st.anyRunning

    Scaffold(topBar = {
        TopAppBar(navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } }, title = { Text(stringResource(R.string.access_from_pc)) })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.server_hint), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            if (ip == null) Text(stringResource(R.string.server_no_network), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
            st.error?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
            if (!credentialsOk) Text(stringResource(R.string.server_password_needed), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)

            ServerCard(stringResource(R.string.ftp_server), st.ftpRunning, ip?.let { "ftp://$it:${st.ftpPort.takeIf { p -> p > 0 } ?: s.serverPort}" },
                if (st.ftpClients > 0) stringResource(R.string.server_clients, st.ftpClients) else null, enabled = credentialsOk) { vm.toggle(ServerKind.FTP, it) }
            ServerCard(stringResource(R.string.web_server), st.httpRunning, ip?.let { "http://$it:${st.httpPort.takeIf { p -> p > 0 } ?: s.httpServerPort}" }, null, enabled = credentialsOk) { vm.toggle(ServerKind.HTTP, it) }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PortField(stringResource(R.string.ftp_server) + " " + stringResource(R.string.server_port), s.serverPort, !locked, Modifier.weight(1f)) { p -> vm.update { it.copy(serverPort = p) } }
                    PortField(stringResource(R.string.web_server) + " " + stringResource(R.string.server_port), s.httpServerPort, !locked, Modifier.weight(1f)) { p -> vm.update { it.copy(httpServerPort = p) } }
                }
            }
            ListItem(headlineContent = { Text(stringResource(R.string.server_anonymous)) },
                trailingContent = { Switch(s.serverAnonymous, { v -> vm.update { it.copy(serverAnonymous = v) } }, enabled = !locked) })
            if (!s.serverAnonymous) Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(s.serverUser, { v -> vm.update { it.copy(serverUser = v.trim()) } }, label = { Text(stringResource(R.string.server_user)) }, singleLine = true, enabled = !locked, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(s.serverPassword, { v -> vm.update { it.copy(serverPassword = v) } }, label = { Text(stringResource(R.string.server_password)) }, singleLine = true, enabled = !locked,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
            }
            ListItem(headlineContent = { Text(stringResource(R.string.server_root)) },
                supportingContent = { Text(s.serverRoot.ifEmpty { vm.defaultRoot?.toString() ?: "" }.let { FsPath.parse(it).path }) },
                trailingContent = { TextButton(onClick = { pickRoot = true }, enabled = !locked) { Text(stringResource(R.string.change)) } })
            ListItem(headlineContent = { Text(stringResource(R.string.server_charset)) }, supportingContent = { Text(s.serverCharset) },
                trailingContent = { TextButton(onClick = { charsetDialog = true }, enabled = !locked) { Text(stringResource(R.string.change)) } })
            ListItem(headlineContent = { Text(stringResource(R.string.server_read_only)) },
                trailingContent = { Switch(s.serverReadOnly, { v -> vm.update { it.copy(serverReadOnly = v) } }, enabled = !locked) })
            Spacer(Modifier.height(24.dp))
        }
    }
    if (pickRoot) FolderPickerDialog(stringResource(R.string.server_root), s.serverRoot.takeIf { it.isNotEmpty() }?.let(FsPath::parse) ?: vm.defaultRoot, { pickRoot = false }, onPick = { p ->
        pickRoot = false; vm.update { it.copy(serverRoot = p.toString()) }
    })
    if (charsetDialog) {
        val sets = listOf("UTF-8", "GBK", "Big5", "Shift_JIS", "EUC-KR", "windows-1251", "windows-1252", "ISO-8859-1")
        ChoiceDialog(stringResource(R.string.server_charset), sets, sets.indexOf(s.serverCharset), { charsetDialog = false }) { i -> vm.update { it.copy(serverCharset = sets[i]) } }
    }
}

@Composable
private fun ServerCard(title: String, running: Boolean, address: String?, sub: String?, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (running && address != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(address, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                        IconButton(onClick = {
                            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("address", address))
                            Toast.makeText(ctx, R.string.address_copied, Toast.LENGTH_SHORT).show()
                        }) { Icon(Icons.Filled.ContentCopy, stringResource(R.string.copy_address)) }
                    }
                }
                sub?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            Switch(running, onToggle, enabled = enabled || running)
        }
    }
}

@Composable
private fun PortField(label: String, value: Int, enabled: Boolean, modifier: Modifier, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(text, { t ->
        text = t.filter(Char::isDigit).take(5)
        text.toIntOrNull()?.takeIf { it in 1024..65535 }?.let(onChange)
    }, label = { Text(label, maxLines = 1) }, singleLine = true, enabled = enabled, isError = text.toIntOrNull()?.let { it !in 1024..65535 } ?: true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = modifier)
}
