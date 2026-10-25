package com.xander.files.data.billing

import android.app.Activity
import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import com.xander.files.di.AppScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class PremiumPlan(val productId: String, val title: String, val price: String, val period: String, val offerToken: String, val details: ProductDetails)

/**
 * Google Play Billing for the premium subscription (monthly / yearly). The entitlement is cached locally so the app
 * works offline and re-verified with Play on every start and purchase update.
 */
@Singleton
class BillingRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @AppScope private val scope: CoroutineScope,
) : PurchasesUpdatedListener {

    private val store = context.premiumStore
    private val premiumKey = booleanPreferencesKey("premium")
    private val verifiedKey = longPreferencesKey("verified_at")

    private val _premium = MutableStateFlow(false)
    val premium: StateFlow<Boolean> = _premium
    private val _plans = MutableStateFlow<List<PremiumPlan>>(emptyList())
    val plans: StateFlow<List<PremiumPlan>> = _plans
    val lastError = MutableStateFlow<String?>(null)

    private val client: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    init {
        scope.launch {
            _premium.value = store.data.map { it[premiumKey] ?: false }.first()
            connect()
        }
    }

    private fun connect() {
        if (client.isReady) { scope.launch { refresh() }; return }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) scope.launch { refresh() }
                else lastError.value = result.debugMessage
            }
            override fun onBillingServiceDisconnected() {}
        })
    }

    /** Re-verifies the subscription with Play and loads plan prices. */
    suspend fun refresh() {
        if (!client.isReady) { connect(); return }
        val subs = client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build())
        if (subs.billingResult.responseCode == BillingClient.BillingResponseCode.OK) handle(subs.purchasesList, fromRestore = true)
        val params = QueryProductDetailsParams.newBuilder().setProductList(PRODUCTS.map {
            QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(BillingClient.ProductType.SUBS).build()
        }).build()
        val res = client.queryProductDetails(params)
        _plans.value = res.productDetailsList.orEmpty().flatMap { d ->
            d.subscriptionOfferDetails.orEmpty().take(1).map { o ->
                val phase = o.pricingPhases.pricingPhaseList.last()
                PremiumPlan(d.productId, d.name, phase.formattedPrice, phase.billingPeriod, o.offerToken, d)
            }
        }.sortedBy { PRODUCTS.indexOf(it.productId) }
    }

    fun purchase(activity: Activity, plan: PremiumPlan) {
        val flow = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(plan.details).setOfferToken(plan.offerToken).build()
        )).build()
        val r = client.launchBillingFlow(activity, flow)
        if (r.responseCode != BillingClient.BillingResponseCode.OK) lastError.value = r.debugMessage
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        if (result.responseCode == BillingClient.BillingResponseCode.OK) scope.launch { handle(purchases.orEmpty(), fromRestore = false) }
        else if (result.responseCode != BillingClient.BillingResponseCode.USER_CANCELED) lastError.value = result.debugMessage
    }

    private suspend fun handle(purchases: List<Purchase>, fromRestore: Boolean) {
        val active = purchases.filter { p -> p.purchaseState == Purchase.PurchaseState.PURCHASED && p.products.any { it in PRODUCTS } }
        active.filter { !it.isAcknowledged }.forEach {
            client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(it.purchaseToken).build())
        }
        // A restore query that returns nothing means the subscription lapsed; an update callback only adds.
        val entitled = active.isNotEmpty() || (!fromRestore && _premium.value)
        setPremium(entitled)
    }

    private suspend fun setPremium(v: Boolean) {
        _premium.value = v
        store.edit { it[premiumKey] = v; it[verifiedKey] = System.currentTimeMillis() }
    }

    suspend fun restore() = refresh()

    companion object {
        const val MONTHLY = "xander_premium_monthly"
        const val YEARLY = "xander_premium_yearly"
        val PRODUCTS = listOf(MONTHLY, YEARLY)
    }
}

private val Context.premiumStore by preferencesDataStore("premium")
