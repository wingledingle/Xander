package com.xander.files

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xander.files.data.settings.AppSettings
import com.xander.files.data.settings.SettingsRepository
import com.xander.files.data.storage.StorageAccess
import com.xander.files.domain.fs.FsPath
import com.xander.files.ui.navigation.XanderNavHost
import com.xander.files.ui.theme.XanderTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var settingsRepo: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val initial = intent.getStringExtra(EXTRA_PATH)?.let(FsPath::parse)
        setContent {
            val settings by settingsRepo.settings.collectAsStateWithLifecycle(AppSettings())
            XanderTheme(settings) {
                Surface(Modifier.fillMaxSize()) {
                    XanderNavHost(hasAccess = StorageAccess.hasFullAccess(this), initialPath = initial, initialRoute = intent.getStringExtra(EXTRA_ROUTE))
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasExtra(EXTRA_PATH) || intent.hasExtra(EXTRA_ROUTE)) recreate()
    }

    companion object {
        const val EXTRA_PATH = "com.xander.files.PATH"
        const val EXTRA_OPEN_FILE = "com.xander.files.OPEN_FILE"
        const val EXTRA_ROUTE = "com.xander.files.ROUTE"
    }
}
