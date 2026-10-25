package com.xander.files.ui.cloud

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xander.files.R
import com.xander.files.data.cloud.CloudAccount
import com.xander.files.data.cloud.CloudService
import com.xander.files.domain.fs.FsPath
import com.xander.files.ui.common.ConfirmDialog
import com.xander.files.ui.common.EmptyState
import com.xander.files.ui.common.Format
import com.xander.files.ui.ops.ActionSheet
import com.xander.files.ui.ops.SheetAction

@Composable
fun CloudScreen(onBack: () -> Unit, onBrowse: (FsPath) -> Unit, vm: CloudViewModel = hiltViewModel()) {
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val quotas by vm.quotas.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var picker by remember { mutableStateOf(false) }
    var megaDialog by remember { mutableStateOf(false) }
    var ncDialog by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<CloudAccount?>(null) }
    var pendingService by remember { mutableStateOf<CloudService?>(null) }

    val oauthLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        pendingService?.let { vm.onOAuthResult(it, r.data) }
    }
    val googleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        vm.onGoogleResult(ctx as Activity, r.data)
    }
    LaunchedEffect(accounts.size) { vm.loadQuotas() }
    LaunchedEffect(Unit) {
        vm.eventFlow.collect { e ->
            when (e) {
                is CloudEvent.LaunchIntent -> { pendingService = e.service; oauthLauncher.launch(e.intent) }
                is CloudEvent.LaunchSender -> googleLauncher.launch(IntentSenderRequest.Builder(e.sender).build())
                is CloudEvent.OpenUrl -> runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(e.url))) }
                is CloudEvent.Message -> Toast.makeText(ctx, e.text, Toast.LENGTH_LONG).show()
                is CloudEvent.Browse -> onBrowse(e.root)
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } }, title = { Text(stringResource(R.string.cloud)) }) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { picker = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text(stringResource(R.string.add_account)) }) }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (accounts.isEmpty()) EmptyState(Icons.Filled.Cloud, stringResource(R.string.no_cloud_accounts), stringResource(R.string.no_cloud_hint))
            else LazyColumn {
                items(accounts, key = { it.id }) { a ->
                    Row(Modifier.fillMaxWidth().clickable { onBrowse(FsPath(FsPath.SCHEME_CLOUD, a.id, "/")) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Cloud, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(start = 16.dp)) {
                            Text(a.service.label, style = MaterialTheme.typography.bodyLarge)
                            Text(a.email.ifEmpty { a.displayName }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            quotas[a.id]?.let { q ->
                                LinearProgressIndicator(progress = { q.fraction }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                                Text("${Format.size(q.used)} / ${Format.size(q.total)}", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        IconButton(onClick = { removing = a }) { Icon(Icons.Filled.Delete, stringResource(R.string.remove)) }
                    }
                }
            }
        }
    }

    if (picker) ActionSheet(stringResource(R.string.add_account), CloudService.entries.map { s ->
        SheetAction(Icons.Filled.Cloud, s.label) {
            when (s) {
                CloudService.MEGA -> megaDialog = true
                CloudService.NEXTCLOUD -> ncDialog = true
                else -> vm.start(s)
            }
        }
    }, onDismiss = { picker = false })

    if (megaDialog) CredentialsDialog("MEGA", showServer = false, onDismiss = { megaDialog = false }) { _, user, pw, _ -> megaDialog = false; vm.signInMega(user, pw) }
    if (ncDialog) CredentialsDialog("Nextcloud", showServer = true, onDismiss = { ncDialog = false }) { server, user, pw, browser ->
        ncDialog = false
        if (browser) vm.signInNextcloudBrowser(server) else vm.signInNextcloudManual(server, user, pw)
    }
    removing?.let { a -> ConfirmDialog(stringResource(R.string.remove_account), a.email.ifEmpty { a.displayName }, stringResource(R.string.remove),
        onDismiss = { removing = null }, onConfirm = { vm.remove(a); removing = null }) }
}

@Composable
private fun CredentialsDialog(title: String, showServer: Boolean, onDismiss: () -> Unit, onConfirm: (server: String, user: String, password: String, browser: Boolean) -> Unit) {
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (showServer) {
                    OutlinedTextField(server, { server = it }, label = { Text(stringResource(R.string.server_url)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.nextcloud_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
                }
                OutlinedTextField(user, { user = it }, label = { Text(stringResource(if (showServer) R.string.conn_user else R.string.email)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pw, { pw = it }, label = { Text(stringResource(if (showServer) R.string.app_password else R.string.password)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Row {
                if (showServer) TextButton(enabled = server.isNotBlank(), onClick = { onConfirm(server, "", "", true) }) { Text(stringResource(R.string.sign_in_browser)) }
                TextButton(enabled = user.isNotBlank() && pw.isNotBlank() && (!showServer || server.isNotBlank()), onClick = { onConfirm(server, user, pw, false) }) { Text(stringResource(R.string.sign_in)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
