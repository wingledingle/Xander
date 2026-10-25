package com.xander.files.ui.navigation

import android.net.Uri
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.xander.files.domain.fs.FsPath
import com.xander.files.ui.bookmarks.BookmarksScreen
import com.xander.files.ui.browser.BrowserScreen
import com.xander.files.ui.clipboard.ClipboardScreen
import com.xander.files.ui.history.HistoryScreen
import com.xander.files.ui.home.HomeScreen
import com.xander.files.ui.onboarding.OnboardingScreen
import com.xander.files.ui.ops.ConflictDialog
import com.xander.files.ui.recycle.RecycleBinScreen
import com.xander.files.ui.search.SearchScreen
import com.xander.files.ui.transfers.TransfersScreen
import kotlinx.coroutines.launch

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val BROWSER = "browser?path={path}"
    const val SEARCH = "search?root={root}"
    const val TRANSFERS = "transfers"
    const val RECYCLE = "recycle"
    const val CLIPBOARD = "clipboard?target={target}"
    const val CLIPBOARD_ROUTE = "clipboard"
    const val BOOKMARKS = "bookmarks"
    const val HISTORY = "history"
    const val LIBRARY = "library/{kind}"
    const val APPS = "apps"
    const val NETWORK = "network"
    const val CLOUD = "cloud"
    const val SETTINGS = "settings"
    const val PREMIUM = "premium"
    const val LICENSES = "licenses"
    const val CONNECTION = "connection?id={id}&type={type}&host={host}&port={port}"
    fun connection(id: String?, type: com.xander.files.data.net.ConnType?, host: String?, port: Int?) =
        "connection?id=${id ?: ""}&type=${type?.name ?: ""}&host=${Uri.encode(host ?: "")}&port=${port ?: 0}"
    fun library(kind: com.xander.files.data.index.LibraryKind) = "library/${kind.name}"
    fun browser(p: FsPath) = "browser?path=${Uri.encode(p.toString())}"
    fun search(root: FsPath?) = "search?root=${Uri.encode(root?.toString() ?: "")}"
    fun clipboard(target: FsPath?) = "clipboard?target=${Uri.encode(target?.toString() ?: "")}"
}

fun NavHostController.openFolder(p: FsPath) = navigate(Routes.browser(p))

@Composable
fun XanderNavHost(
    hasAccess: Boolean,
    initialPath: FsPath?,
    initialRoute: String?,
    nav: NavHostController = rememberNavController(),
    shell: ShellViewModel = hiltViewModel(),
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val volumes by shell.volumes.collectAsStateWithLifecycle()
    val bookmarks by shell.bookmarks.collectAsStateWithLifecycle()
    val rootMode by shell.rootMode.collectAsStateWithLifecycle()
    val conflict by shell.conflicts.pending.collectAsStateWithLifecycle()

    fun go(action: () -> Unit) { scope.launch { drawerState.close() }; action() }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = hasAccess,
        drawerContent = {
            AppDrawer(
                volumes = volumes, bookmarks = bookmarks, rootMode = rootMode,
                extraSections = shell.extraDrawerSections,
                onHome = { go { nav.popBackStack(Routes.HOME, inclusive = false) } },
                onOpenPath = { p -> go { nav.openFolder(p) } },
                onRoute = { r -> go { nav.navigate(r) } },
            )
        }
    ) {
        NavHost(nav, startDestination = if (hasAccess) Routes.HOME else Routes.ONBOARDING) {
            composable(Routes.ONBOARDING) {
                val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as com.xander.files.XanderApp
                OnboardingScreen(onGranted = {
                    app.startIndexing()
                    nav.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                })
            }
            composable(Routes.HOME) {
                HomeScreen(
                    onOpen = nav::openFolder,
                    onSearch = { nav.navigate(Routes.search(null)) },
                    onMenu = { scope.launch { drawerState.open() } },
                    onRoute = { nav.navigate(it) },
                )
                LaunchedEffect(Unit) {
                    if (initialPath != null) nav.openFolder(initialPath)
                    else if (initialRoute != null) nav.navigate(initialRoute)
                }
            }
            composable(Routes.BROWSER, arguments = listOf(navArgument("path") { type = NavType.StringType; nullable = true })) {
                BrowserScreen(
                    onExit = { nav.popBackStack() },
                    onSearch = { nav.navigate(Routes.search(it)) },
                    onOpenClipboard = { nav.navigate(Routes.clipboard(it)) },
                    onReauth = { p ->
                        if (p.scheme == FsPath.SCHEME_CLOUD) nav.navigate(Routes.CLOUD)
                        else nav.navigate(Routes.connection(p.authority, null, null, null))
                    },
                )
            }
            composable(Routes.SEARCH, arguments = listOf(navArgument("root") { type = NavType.StringType; defaultValue = "" })) {
                SearchScreen(onBack = { nav.popBackStack() }, onOpenFolder = nav::openFolder)
            }
            composable(Routes.TRANSFERS) { TransfersScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.RECYCLE) { RecycleBinScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.CLIPBOARD, arguments = listOf(navArgument("target") { type = NavType.StringType; defaultValue = "" })) { e ->
                val target = e.arguments?.getString("target")?.takeIf { it.isNotEmpty() }?.let(FsPath::parse)
                ClipboardScreen(onBack = { nav.popBackStack() }, pasteTarget = target)
            }
            composable(Routes.BOOKMARKS) {
                BookmarksScreen(onBack = { nav.popBackStack() }, onOpen = { p, folder -> if (folder) nav.openFolder(p) else p.parent?.let(nav::openFolder) })
            }
            composable(Routes.LIBRARY) {
                com.xander.files.ui.library.LibraryScreen(onBack = { nav.popBackStack() }, onOpenFolder = nav::openFolder)
            }
            composable(Routes.APPS) { com.xander.files.ui.apps.AppsScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.NETWORK) {
                com.xander.files.ui.network.NetworkScreen(onBack = { nav.popBackStack() }, onBrowse = nav::openFolder,
                    onEdit = { id, t, h, p -> nav.navigate(Routes.connection(id, t, h, p)) }, onCloud = { nav.navigate(Routes.CLOUD) })
            }
            composable(Routes.CONNECTION, arguments = listOf("id", "type", "host", "port").map { n -> navArgument(n) { type = NavType.StringType; defaultValue = "" } }) {
                com.xander.files.ui.network.ConnectionEditScreen(onBack = { nav.popBackStack() }, onConnected = { root -> nav.popBackStack(); nav.openFolder(root) })
            }
            composable(Routes.CLOUD) { com.xander.files.ui.cloud.CloudScreen(onBack = { nav.popBackStack() }, onBrowse = nav::openFolder) }
            composable(Routes.SETTINGS) {
                com.xander.files.ui.settings.SettingsScreen(onBack = { nav.popBackStack() }, onPremium = { nav.navigate(Routes.PREMIUM) }, onLicenses = { nav.navigate(Routes.LICENSES) })
            }
            composable(Routes.PREMIUM) { com.xander.files.ui.premium.PaywallScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.LICENSES) { com.xander.files.ui.settings.LicensesScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.HISTORY) { HistoryScreen(onBack = { nav.popBackStack() }, onOpen = nav::openFolder) }
        }
    }

    conflict?.let { p -> ConflictDialog(p) { choice, all -> shell.conflicts.answer(choice, all) } }
}
