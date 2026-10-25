package com.xander.files.ui.tv

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xander.files.R
import com.xander.files.data.tv.companion.TvCompanionController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class TvHostViewModel @Inject constructor(val controller: TvCompanionController) : ViewModel()

/** Shown on the TV: the pairing code and address phones need, plus the remote-control service status. */
@Composable
fun TvHostScreen(onBack: () -> Unit, vm: TvHostViewModel = hiltViewModel()) {
    val st by vm.controller.state.collectAsStateWithLifecycle()
    val code by vm.controller.pairing.codeFlow.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) vm.controller.start() }
        lifecycle.addObserver(o); onDispose { lifecycle.removeObserver(o) }
    }
    Scaffold(topBar = {
        TopAppBar(navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } }, title = { Text(stringResource(R.string.tv_companion)) })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.tv_host_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.tv_host_steps), style = MaterialTheme.typography.bodyLarge)
            Text(code.chunked(3).joinToString(" "), fontSize = 64.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
            st.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(stringResource(R.string.tv_host_addr, st.addresses.firstOrNull()?.let { "$it:${st.port}" } ?: "—"))
            Text(stringResource(R.string.tv_host_paired, vm.controller.pairing.pairedCount()))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { vm.controller.pairing.rotate() }) { Text(stringResource(R.string.tv_host_new_code)) }
                OutlinedButton(onClick = { vm.controller.pairing.revokeAll(); vm.controller.refresh() }) { Text(stringResource(R.string.tv_host_revoke)) }
            }
            Text(stringResource(R.string.tv_host_remote), style = MaterialTheme.typography.titleMedium)
            Button(onClick = { runCatching { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) {
                Text(stringResource(if (st.remoteEnabled) R.string.tv_host_remote_on else R.string.tv_host_remote_off))
            }
        }
    }
}
