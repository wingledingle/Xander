package com.xander.files.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xander.files.R

private val libraries = listOf(
    "AndroidX, Jetpack Compose, Room, WorkManager, DataStore, Media3" to "Apache License 2.0",
    "Dagger / Hilt" to "Apache License 2.0", "Kotlin, kotlinx.coroutines" to "Apache License 2.0",
    "OkHttp, Okio" to "Apache License 2.0", "Coil" to "Apache License 2.0", "smbj" to "Apache License 2.0",
    "Apache Commons Net" to "Apache License 2.0", "Apache Commons Compress" to "Apache License 2.0", "sshj" to "Apache License 2.0",
    "Bouncy Castle" to "MIT (Bouncy Castle licence)", "EMC nfs-client" to "Apache License 2.0", "Zip4j" to "Apache License 2.0",
    "XZ for Java 1.9" to "Public domain", "libsu" to "Apache License 2.0", "Shizuku API" to "MIT License", "AppAuth" to "Apache License 2.0",
    "Guava, Netty, Accompanist, asn-one" to "Apache License 2.0", "MBassador, SLF4J" to "MIT License", "EdDSA-Java" to "CC0 1.0 (public domain)",
    "Google Play Billing, Google Cast, Google Play services Auth" to "Google APIs Terms of Service",
)

@Composable
fun LicensesScreen(onBack: () -> Unit) {
    Scaffold(topBar = { TopAppBar(navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } }, title = { Text(stringResource(R.string.open_source_licenses)) }) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            items(libraries) { (name, lic) ->
                Column(Modifier.padding(16.dp)) {
                    Text(name, style = MaterialTheme.typography.bodyLarge)
                    Text(lic, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
        }
    }
}
