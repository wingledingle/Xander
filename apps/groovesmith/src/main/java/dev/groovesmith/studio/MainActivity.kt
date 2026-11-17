package dev.groovesmith.studio

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.xander.files.modules.WebModuleScreen
import com.xander.files.modules.WebPage

/** Groovesmith Studio on its own: the Studio page full screen, with files from "Open with" or the picker. */
class MainActivity : ComponentActivity() {
    private var opened by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        opened = intent.openedFile()
        askForMediaAccess()
        setContent {
            val storage = remember { LocalHostStorage(applicationContext) }
            // A new "Open with" restarts the page with that file.
            key(opened) { WebModuleScreen(STUDIO, opened, storage, onBack = { finish() }) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.openedFile()?.let { opened = it }
    }

    private fun Intent.openedFile(): String? = data?.takeIf { action == Intent.ACTION_VIEW }?.toString()

    // The "On phone" tab lists the phone's videos and audio; without access it is simply empty.
    private fun askForMediaAccess() {
        val perms = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
            else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        if (perms.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) requestPermissions(perms, 1)
    }

    companion object {
        val STUDIO = WebPage("studio", lowLatencyAudio = true, nativeVideo = true)
    }
}
