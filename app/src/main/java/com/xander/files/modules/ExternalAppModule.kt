package com.xander.files.modules

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import com.xander.files.domain.fs.FsPath

/**
 * A module that is a separate app ([packageName]): listed only while it is installed; opening it starts that app,
 * with the file handed over as an ACTION_VIEW content Uri (from any backend).
 */
class ExternalAppModule(
    override val id: String,
    override val title: Int,
    override val summary: Int,
    override val icon: ImageVector,
    val packageName: String,
    override val opens: Set<String> = emptySet(),
    override val defaultFor: Set<String> = emptySet(),
    override val zipKind: SharedIndex.ZipKind? = null,
    override val minSdk: Int = 23,
) : XanderModule {
    override fun available(c: Context): Boolean =
        runCatching { c.packageManager.getPackageInfo(packageName, 0); true }.getOrDefault(false)

    suspend fun intent(context: Context, file: FsPath?): Intent? {
        if (file == null) return context.packageManager.getLaunchIntentForPackage(packageName)
        return ActivityModule.withFile(context, Intent().setPackage(packageName), file)
    }

    @Composable
    override fun Content(file: FsPath?, onBack: () -> Unit) {
        val context = LocalContext.current
        LaunchedEffect(file) {
            runCatching { intent(context, file)?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            onBack() // the other app takes over; this route only launches it
        }
    }
}
