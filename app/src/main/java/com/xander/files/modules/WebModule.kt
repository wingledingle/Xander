package com.xander.files.modules

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.xander.files.domain.fs.FsPath

/**
 * A module that is a web app bundled in the APK at `assets/modules/<id>/` (sources in `modules-web/www/modules/<id>/`).
 * Declaring one is all it takes:
 * ```
 * object EmulatorModule : WebModule("gba", R.string.gba, R.string.gba_summary, Icons.Filled.SportsEsports,
 *     opens = setOf("gba"), defaultFor = setOf("gba"))
 * ```
 */
abstract class WebModule(
    override val id: String,
    override val title: Int,
    override val summary: Int,
    override val icon: androidx.compose.ui.graphics.vector.ImageVector,
    override val opens: Set<String> = emptySet(),
    override val defaultFor: Set<String> = emptySet(),
    /** Page inside the module folder. */
    val entry: String = "index.html",
    /** Turns on the native low-latency pad engine for this module (XanderHost.pad*). */
    val lowLatencyAudio: Boolean = false,
    /** Gives the page a native video player laid over it (XanderHost.videoCmd); see [StudioVideo]. */
    val nativeVideo: Boolean = false,
) : XanderModule {
    @Composable
    override fun Content(file: FsPath?, onBack: () -> Unit) {
        val context = LocalContext.current
        val storage = remember { XanderHostStorage.from(context) }
        WebModuleScreen(WebPage(id, entry, lowLatencyAudio, nativeVideo), file?.toString(), storage, onBack)
    }
}

