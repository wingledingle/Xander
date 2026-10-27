package com.xander.files.modules

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import com.xander.files.data.open.FileOpener
import com.xander.files.domain.fs.FsPath
import com.xander.files.domain.fs.MimeTypes
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * A module that is a whole Activity of its own (an app brought in as source, like Oleander or Quinten).
 * Opening it starts [activity]; a file is handed over as an ACTION_VIEW content Uri, from any backend.
 */
abstract class ActivityModule(
    override val id: String,
    override val title: Int,
    override val summary: Int,
    override val icon: ImageVector,
    private val activity: Class<out Activity>,
    override val opens: Set<String> = emptySet(),
    override val defaultFor: Set<String> = emptySet(),
) : XanderModule {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps { fun opener(): FileOpener }

    suspend fun intent(context: Context, file: FsPath?): Intent {
        val i = Intent(context, activity)
        if (file != null) {
            val uri = EntryPointAccessors.fromApplication(context, Deps::class.java).opener().shareableUri(file)
            i.setAction(Intent.ACTION_VIEW).setDataAndType(uri, MimeTypes.fromName(file.name) ?: "application/octet-stream")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return i
    }

    @Composable
    override fun Content(file: FsPath?, onBack: () -> Unit) {
        val context = LocalContext.current
        LaunchedEffect(file) {
            runCatching { context.startActivity(intent(context, file)) }
            onBack() // the module runs in its own Activity; this route only launches it
        }
    }
}
