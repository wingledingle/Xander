package com.xander.files.modules

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FsPath

/**
 * A feature that plugs into the file manager: it gets a drawer entry, an "Open in <title>" action for the file
 * types it handles, optionally becomes the default viewer for some of them, and a full-screen route.
 *
 * Kinds:
 *  - [WebModule]: a web app under `modules-web/www/modules/<id>/`, shipped in the APK and run in a WebView with
 *    the `XanderHost` bridge (file streaming, native audio decoding, saving). See modules-web/README.md.
 *  - [ActivityModule]: a whole app brought in as source, started as its own Activity (Oleander, Quinten).
 *  - Native: implement [Content] with Compose.
 *
 * To add one, create the object and list it in [Modules.all]. Nothing else needs to change.
 */
interface XanderModule {
    /** Stable id, used in the route and (for web modules) as the asset folder name. */
    val id: String
    @get:StringRes val title: Int
    @get:StringRes val summary: Int
    val icon: ImageVector
    /** Lower-case extensions offered as "Open in <title>" in the file action sheet. */
    val opens: Set<String> get() = emptySet()
    /** Extensions this module opens on a plain tap (instead of the built-in viewer). Must be a subset of [opens]. */
    val defaultFor: Set<String> get() = emptySet()
    /** Lowest Android version the module runs on; it is hidden below that. */
    val minSdk: Int get() = 23

    /** Full-screen UI. [file] is the file the module was opened with, if any. */
    @Composable
    fun Content(file: FsPath?, onBack: () -> Unit)
}

object Modules {
    /** Every module in the app, in drawer order. */
    val all: List<XanderModule> = listOf(
        StudioModule,
        OleanderModule,
        QuintenModule,
    ).filter { it.minSdk <= android.os.Build.VERSION.SDK_INT }

    /** The module that opens what a plain .zip holds (GBA game, book), or null. Blocks briefly: call off the main thread. */
    suspend fun forZip(c: android.content.Context, f: java.io.File): XanderModule? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        when (SharedIndex.zipKind(c, f)) {
            SharedIndex.ZipKind.GBA -> OleanderModule.takeIf { it in all }
            SharedIndex.ZipKind.BOOK -> QuintenModule.takeIf { it in all }
            SharedIndex.ZipKind.NONE -> null
        }
    }

    const val ROUTE = "module/{id}?path={path}"
    fun route(m: XanderModule, file: FsPath? = null) = "module/${m.id}?path=${Uri.encode(file?.toString() ?: "")}"

    fun byId(id: String?): XanderModule? = all.firstOrNull { it.id == id }
    /** Modules that can open this file (for the "Open in…" actions). */
    fun forFile(e: FileEntry): List<XanderModule> = if (e.isDirectory) emptyList() else all.filter { e.extension in it.opens }
    /** The module that opens this file on tap, if any claimed its extension. */
    fun defaultFor(e: FileEntry): XanderModule? = if (e.isDirectory) null else all.firstOrNull { e.extension in it.defaultFor }
}
