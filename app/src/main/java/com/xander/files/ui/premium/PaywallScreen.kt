package com.xander.files.ui.premium

import android.app.Activity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xander.files.R
import com.xander.files.data.billing.BillingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PaywallViewModel @Inject constructor(val billing: BillingRepository) : ViewModel()

@Composable
fun PaywallScreen(onBack: () -> Unit, vm: PaywallViewModel = hiltViewModel()) {
    val plans by vm.billing.plans.collectAsStateWithLifecycle()
    val premium by vm.billing.premium.collectAsStateWithLifecycle()
    val error by vm.billing.lastError.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { vm.billing.refresh() }
    Scaffold(topBar = { TopAppBar(navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } }, title = { Text("Premium") }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.WorkspacePremium, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(if (premium) R.string.premium_active else R.string.go_premium), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp))
            listOf(R.string.premium_benefit_ads, R.string.premium_benefit_skins, R.string.premium_benefit_support).forEach { b ->
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary); Text(stringResource(b), Modifier.padding(start = 12.dp))
                }
            }
            if (!premium) {
                if (plans.isEmpty()) CircularProgressIndicator(Modifier.padding(24.dp))
                plans.forEach { p ->
                    Card(onClick = { (ctx as? Activity)?.let { vm.billing.purchase(it, p) } }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                        Column(Modifier.padding(20.dp)) {
                            Text(if (p.period == "P1Y") stringResource(R.string.yearly) else stringResource(R.string.monthly), style = MaterialTheme.typography.titleMedium)
                            Text(p.price + " / " + if (p.period == "P1Y") stringResource(R.string.year) else stringResource(R.string.month), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
                TextButton(onClick = { scope.launch { vm.billing.restore() } }, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.restore_purchases)) }
                Text(stringResource(R.string.subscription_terms), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Ad placeholder: shows the provider's banner only when ads are enabled and the user is not premium. */
@Composable
fun AdSlot(placement: com.xander.files.data.ads.AdPlacement, controller: com.xander.files.data.ads.AdsController) {
    val visible by controller.adsVisible.collectAsStateWithLifecycle(false)
    if (!visible) return
    androidx.compose.ui.viewinterop.AndroidView(factory = { c -> controller.provider.bannerView(c, placement) ?: android.view.View(c) }, modifier = Modifier.fillMaxWidth())
}
