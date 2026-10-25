package com.xander.files.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.xander.files.domain.fs.FsPath
import com.xander.files.ui.browser.BrowserScreen
import com.xander.files.ui.home.HomeScreen
import com.xander.files.ui.onboarding.OnboardingScreen
import com.xander.files.ui.search.SearchScreen

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val BROWSER = "browser?path={path}"
    const val SEARCH = "search?root={root}"
    fun browser(p: FsPath) = "browser?path=${Uri.encode(p.toString())}"
    fun search(root: FsPath?) = "search?root=${Uri.encode(root?.toString() ?: "")}"
}

fun NavHostController.openFolder(p: FsPath) = navigate(Routes.browser(p))

@Composable
fun XanderNavHost(hasAccess: Boolean, initialPath: FsPath?, nav: NavHostController = rememberNavController()) {
    NavHost(nav, startDestination = if (hasAccess) Routes.HOME else Routes.ONBOARDING) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(onGranted = { nav.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } } })
        }
        composable(Routes.HOME) {
            HomeScreen(onOpen = nav::openFolder, onSearch = { nav.navigate(Routes.search(null)) })
            if (initialPath != null) androidx.compose.runtime.LaunchedEffect(initialPath) { nav.openFolder(initialPath) }
        }
        composable(Routes.BROWSER, arguments = listOf(navArgument("path") { type = NavType.StringType; nullable = true })) {
            BrowserScreen(onExit = { nav.popBackStack() }, onSearch = { nav.navigate(Routes.search(it)) })
        }
        composable(Routes.SEARCH, arguments = listOf(navArgument("root") { type = NavType.StringType; defaultValue = "" })) {
            SearchScreen(onBack = { nav.popBackStack() }, onOpenFolder = nav::openFolder)
        }
    }
}
