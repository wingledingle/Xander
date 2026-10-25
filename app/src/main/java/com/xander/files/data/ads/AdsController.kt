package com.xander.files.data.ads

import com.xander.files.BuildConfig
import com.xander.files.data.billing.BillingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Where ads may appear. The UI renders [AdSlot] composables for these; the SDK is plugged in via [AdProvider]. */
enum class AdPlacement { HOME_BANNER, BROWSER_BANNER, TRANSFER_DONE_INTERSTITIAL }

/**
 * Integration point for an ad SDK. The default implementation renders nothing; a release build can bind a real
 * provider in [com.xander.files.di.AdsModule]. Ads are shown only when the build flag is on and the user is not premium.
 */
interface AdProvider {
    val name: String
    /** Returns an Android View for the placement, or null when no ad is available. */
    fun bannerView(context: android.content.Context, placement: AdPlacement): android.view.View?
    fun showInterstitial(activity: android.app.Activity, placement: AdPlacement) {}
}

class NoAdProvider @Inject constructor() : AdProvider {
    override val name = "none"
    override fun bannerView(context: android.content.Context, placement: AdPlacement): android.view.View? = null
}

@Singleton
class AdsController @Inject constructor(
    billing: BillingRepository,
    val provider: AdProvider,
) {
    /** Single feature flag (BuildConfig.ADS_ENABLED, false in debug) combined with the premium entitlement. */
    val adsVisible: Flow<Boolean> = billing.premium.map { premium -> BuildConfig.ADS_ENABLED && !premium && provider.name != "none" }
}
