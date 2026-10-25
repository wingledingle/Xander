package com.xander.files.ui.onboarding

import android.content.ActivityNotFoundException
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.xander.files.R
import com.xander.files.data.storage.StorageAccess

/** Explains and requests storage access: legacy permissions on API 23-29, "All files access" on 30+. */
@Composable
fun OnboardingScreen(onGranted: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME && StorageAccess.hasFullAccess(context)) onGranted() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (StorageAccess.hasFullAccess(context)) onGranted()
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Filled.FolderOpen, null, Modifier.size(96.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(if (Build.VERSION.SDK_INT >= 30) R.string.onboarding_body_all_files else R.string.onboarding_body_legacy),
            style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(32.dp))
        Button(modifier = Modifier.fillMaxWidth(), onClick = {
            if (Build.VERSION.SDK_INT >= 30) {
                try { context.startActivity(StorageAccess.allFilesAccessIntent(context)) }
                catch (e: ActivityNotFoundException) { context.startActivity(StorageAccess.allFilesAccessFallbackIntent()) }
            } else legacy.launch(StorageAccess.legacyPermissions)
        }) { Text(stringResource(R.string.grant_access)) }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { context.startActivity(StorageAccess.appDetailsIntent(context)) }) {
            Text(stringResource(R.string.open_app_settings))
        }
    }
}
