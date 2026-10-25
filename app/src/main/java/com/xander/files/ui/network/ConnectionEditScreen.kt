package com.xander.files.ui.network

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.xander.files.R
import com.xander.files.data.net.ConnOptions
import com.xander.files.data.net.ConnType
import com.xander.files.data.net.Connection
import com.xander.files.data.net.ConnectionRepository
import com.xander.files.data.net.sftp.HostKeyUnknownException
import com.xander.files.data.net.sftp.SftpFileSystemProvider
import com.xander.files.domain.fs.FileSystemRegistry
import com.xander.files.domain.fs.FsPath
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConnectionEditViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val repo: ConnectionRepository,
    private val registry: FileSystemRegistry,
    private val sftp: SftpFileSystemProvider,
) : ViewModel() {
    val conn = MutableStateFlow<Connection?>(null)
    val testing = MutableStateFlow(false)
    val result = MutableStateFlow<String?>(null)
    val hostKey = MutableStateFlow<HostKeyUnknownException?>(null)

    init {
        viewModelScope.launch {
            val id = saved.get<String>("id")?.takeIf { it.isNotEmpty() }
            conn.value = if (id != null) repo.get(id) else {
                val type = saved.get<String>("type")?.takeIf { it.isNotEmpty() }?.let { ConnType.valueOf(it) } ?: ConnType.SMB
                val port = saved.get<String>("port")?.toIntOrNull()?.takeIf { it > 0 } ?: type.defaultPort
                Connection("", type, "", saved.get<String>("host").orEmpty(), port, "", "", "", if (type == ConnType.SFTP) "/" else "",
                    ConnOptions(https = port == 443 || port == 5006))
            }
        }
    }

    fun update(f: (Connection) -> Connection) { conn.value = conn.value?.let(f); result.value = null }

    /** Saves and lists the root to verify host, credentials and path. */
    fun saveAndTest(onDone: (FsPath) -> Unit) = viewModelScope.launch {
        val c = conn.value ?: return@launch
        testing.value = true; result.value = null
        try {
            val saved = repo.save(c)
            conn.value = saved
            registry.provider(saved.root).list(saved.root)
            onDone(saved.root)
        } catch (e: HostKeyUnknownException) {
            hostKey.value = e
        } catch (e: Exception) {
            result.value = e.message ?: e.javaClass.simpleName
        } finally { testing.value = false }
    }

    fun trustHostKey(onDone: (FsPath) -> Unit) = viewModelScope.launch {
        val e = hostKey.value ?: return@launch
        sftp.trustHostKey(e.connectionId, e.fingerprint)
        hostKey.value = null
        conn.value = repo.get(e.connectionId)
        saveAndTest(onDone)
    }

    fun dismissHostKey() { hostKey.value = null }
}

@Composable
fun ConnectionEditScreen(onBack: () -> Unit, onConnected: (FsPath) -> Unit, vm: ConnectionEditViewModel = hiltViewModel()) {
    val c by vm.conn.collectAsStateWithLifecycle()
    val testing by vm.testing.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()
    val hostKey by vm.hostKey.collectAsStateWithLifecycle()
    val conn = c ?: return
    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            title = { Text(if (conn.id.isEmpty()) stringResource(R.string.add_connection) else conn.name) }
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(Modifier.fillMaxWidth()) {
                ConnType.entries.forEach { t ->
                    FilterChip(conn.type == t, { vm.update { it.copy(type = t, port = if (it.port == it.type.defaultPort) t.defaultPort else it.port) } },
                        label = { Text(typeLabel(t)) }, modifier = Modifier.padding(end = 4.dp))
                }
            }
            Field(stringResource(R.string.conn_name), conn.name) { v -> vm.update { it.copy(name = v) } }
            Field(stringResource(R.string.conn_host), conn.host, KeyboardType.Uri) { v -> vm.update { it.copy(host = v.trim()) } }
            Field(stringResource(R.string.conn_port), conn.port.toString(), KeyboardType.Number) { v -> vm.update { it.copy(port = v.toIntOrNull() ?: 0) } }
            if (conn.type != ConnType.NFS) {
                Toggle(stringResource(R.string.conn_anonymous), conn.options.anonymous) { v -> vm.update { it.copy(options = it.options.copy(anonymous = v)) } }
                if (!conn.options.anonymous) {
                    Field(stringResource(R.string.conn_user), conn.username) { v -> vm.update { it.copy(username = v) } }
                    if (!(conn.type == ConnType.SFTP && conn.options.usePrivateKey))
                        Field(stringResource(R.string.password), conn.password, KeyboardType.Password, secret = true) { v -> vm.update { it.copy(password = v) } }
                }
            }
            if (conn.type == ConnType.SMB) Field(stringResource(R.string.conn_domain), conn.domain) { v -> vm.update { it.copy(domain = v) } }
            Field(stringResource(when (conn.type) { ConnType.SMB -> R.string.conn_share; ConnType.NFS -> R.string.conn_export; else -> R.string.conn_path }), conn.remotePath) { v -> vm.update { it.copy(remotePath = v) } }
            when (conn.type) {
                ConnType.FTP, ConnType.FTPS -> {
                    Toggle(stringResource(R.string.conn_passive), conn.options.passive) { v -> vm.update { it.copy(options = it.options.copy(passive = v)) } }
                    Field(stringResource(R.string.conn_encoding), conn.options.encoding) { v -> vm.update { it.copy(options = it.options.copy(encoding = v)) } }
                    if (conn.type == ConnType.FTPS) {
                        Toggle(stringResource(R.string.conn_implicit), conn.options.implicitTls) { v -> vm.update { it.copy(port = if (v && it.port == 21) 990 else if (!v && it.port == 990) 21 else it.port, options = it.options.copy(implicitTls = v)) } }
                        Toggle(stringResource(R.string.conn_trust_self_signed), conn.options.trustSelfSigned) { v -> vm.update { it.copy(options = it.options.copy(trustSelfSigned = v)) } }
                    }
                }
                ConnType.SFTP -> {
                    Toggle(stringResource(R.string.conn_private_key), conn.options.usePrivateKey) { v -> vm.update { it.copy(options = it.options.copy(usePrivateKey = v)) } }
                    if (conn.options.usePrivateKey) {
                        OutlinedTextField(conn.privateKey, { v -> vm.update { it.copy(privateKey = v) } }, label = { Text(stringResource(R.string.conn_key_text)) },
                            minLines = 4, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                        Field(stringResource(R.string.conn_key_passphrase), conn.keyPassphrase, KeyboardType.Password, secret = true) { v -> vm.update { it.copy(keyPassphrase = v) } }
                    }
                    if (conn.options.hostKeyFingerprint.isNotEmpty()) Text(stringResource(R.string.conn_host_key, conn.options.hostKeyFingerprint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
                ConnType.WEBDAV, ConnType.HTTP -> {
                    Toggle("HTTPS", conn.options.https) { v -> vm.update { it.copy(port = if (v && it.port == 80) 443 else if (!v && it.port == 443) 80 else it.port, options = it.options.copy(https = v)) } }
                    Toggle(stringResource(R.string.conn_trust_self_signed), conn.options.trustSelfSigned) { v -> vm.update { it.copy(options = it.options.copy(trustSelfSigned = v)) } }
                }
                ConnType.NFS -> {
                    Field("UID", conn.options.nfsUid.toString(), KeyboardType.Number) { v -> vm.update { it.copy(options = it.options.copy(nfsUid = v.toIntOrNull() ?: 0)) } }
                    Field("GID", conn.options.nfsGid.toString(), KeyboardType.Number) { v -> vm.update { it.copy(options = it.options.copy(nfsGid = v.toIntOrNull() ?: 0)) } }
                }
                ConnType.SMB -> Text(stringResource(R.string.smb_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
            Field(stringResource(R.string.conn_timeout), conn.options.timeoutSec.toString(), KeyboardType.Number) { v -> vm.update { it.copy(options = it.options.copy(timeoutSec = v.toIntOrNull()?.coerceIn(3, 120) ?: 20)) } }
            result?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
            Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = conn.host.isNotBlank() && conn.port > 0 && !testing, onClick = { vm.saveAndTest(onConnected) }) { Text(stringResource(R.string.save_and_connect)) }
                if (testing) CircularProgressIndicator(Modifier.padding(start = 16.dp))
            }
        }
    }
    hostKey?.let { e ->
        com.xander.files.ui.common.ConfirmDialog(
            title = stringResource(if (e.changed) R.string.host_key_changed else R.string.host_key_unknown),
            message = e.fingerprint, confirm = stringResource(R.string.trust),
            onDismiss = vm::dismissHostKey, onConfirm = { vm.trustHostKey(onConnected) })
    }
}

@Composable
private fun Field(label: String, value: String, type: KeyboardType = KeyboardType.Text, secret: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        keyboardOptions = KeyboardOptions(keyboardType = type), visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(value, onChange)
    }
}
