package app.quinten

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.quinten.ui.library.LibraryScreen
import app.quinten.ui.reader.ReaderKeys
import app.quinten.ui.reader.ReaderScreen
import app.quinten.ui.screens.HistoryScreen
import app.quinten.ui.screens.NotesScreen
import app.quinten.ui.screens.SettingsScreen
import app.quinten.ui.theme.QuintenTheme
import kotlinx.coroutines.launch

open class MainActivity : ComponentActivity() {
    private val openBook = mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            val ui by container.settings.app.collectAsState(initial = null)
            QuintenTheme(ui?.uiTheme ?: "system", ui?.uiDarkMode ?: "system") { app.quinten.ui.theme.ThemedBackground {
                val nav = rememberNavController()
                val pending = openBook.value
                LaunchedEffect(pending) { if (pending != null) { nav.openReader(pending); openBook.value = null } }
                NavHost(nav, startDestination = "library") {
                    composable("library") {
                        LibraryScreen(
                            onOpen = { nav.openReader(it) },
                            onSettings = { nav.navigate("settings") },
                            onNotes = { nav.navigate("notes") },
                            onHistory = { nav.navigate("history") },
                        )
                    }
                    composable(
                        "reader/{id}?loc={loc}",
                        arguments = listOf(navArgument("id") { type = NavType.LongType }, navArgument("loc") { type = NavType.StringType; nullable = true; defaultValue = null }),
                    ) { e ->
                        ReaderScreen(e.arguments!!.getLong("id"), e.arguments?.getString("loc")) { nav.popBackStack() }
                    }
                    composable("settings") { SettingsScreen { nav.popBackStack() } }
                    composable("notes") { NotesScreen(onBack = { nav.popBackStack() }, onOpen = { id, loc -> nav.openReader(id, loc) }) }
                    composable("history") { HistoryScreen(onBack = { nav.popBackStack() }, onOpen = { nav.openReader(it) }) }
                }
            } }
        }
    }

    private fun NavHostController.openReader(id: Long, loc: String? = null) {
        navigate(if (loc != null) "reader/$id?loc=$loc" else "reader/$id") { launchSingleTop = true }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** "Open with" from file managers and other apps. */
    private fun handleIntent(intent: Intent?) {
        intent?.getLongExtra(BookWindows.EXTRA_BOOK, -1L)?.takeIf { it >= 0 }?.let { openBook.value = it; return }
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        val location = if (uri.scheme == "file") uri.path ?: return else uri.toString()
        lifecycleScope.launch {
            val id = container.library.addBook(location)
            if (id != null) openBook.value = id
            else Toast.makeText(this@MainActivity, "This file could not be opened as a book.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val h = ReaderKeys.handlers[this]
        if (h != null && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            if (h(keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) return true
        }
        if (h != null && (keyCode == KeyEvent.KEYCODE_PAGE_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_SPACE)) { h(true); return true }
        if (h != null && (keyCode == KeyEvent.KEYCODE_PAGE_UP || keyCode == KeyEvent.KEYCODE_DPAD_LEFT)) { h(false); return true }
        return super.onKeyDown(keyCode, event)
    }
}

/** A separate window (own task in recents, works in split screen) for reading another book at the same time. */
class BookWindowActivity : MainActivity()

object BookWindows {
    const val EXTRA_BOOK = "app.quinten.BOOK_ID"

    /** Opens [bookId] (or the library when null) in a new window, beside the current one in split screen. */
    fun open(context: android.content.Context, bookId: Long?) {
        val i = Intent(context, BookWindowActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        if (bookId != null) i.putExtra(EXTRA_BOOK, bookId)
        val act = context as? android.app.Activity
        if (act?.isInMultiWindowMode == true) i.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        runCatching { context.startActivity(i) }
    }
}
