package com.xander.files.ui.navigation

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xander.files.R
import com.xander.files.data.db.BookmarkEntity
import com.xander.files.data.storage.VolumeInfo
import com.xander.files.data.storage.VolumeKind
import com.xander.files.domain.fs.FsPath

/** Static destinations listed in the drawer. Feature screens register here as they ship. */
data class DrawerDestination(val route: String, val icon: ImageVector, val label: Int)

val drawerNetwork = listOf(
    DrawerDestination(Routes.NETWORK, Icons.Filled.Lan, R.string.network),
    DrawerDestination(Routes.CLOUD, Icons.Filled.Cloud, R.string.cloud),
)

val drawerTools = listOf(
    DrawerDestination(Routes.TRANSFERS, Icons.Filled.SwapVert, R.string.transfers),
    DrawerDestination(Routes.CLIPBOARD_ROUTE, Icons.Filled.ContentPaste, R.string.clipboard),
    DrawerDestination(Routes.RECYCLE, Icons.Filled.Delete, R.string.recycle_bin),
    DrawerDestination(Routes.HISTORY, Icons.Filled.History, R.string.history),
    DrawerDestination(Routes.APPS, Icons.Filled.Apps, R.string.app_manager),
)

@Composable
fun AppDrawer(
    volumes: List<VolumeInfo>,
    bookmarks: List<BookmarkEntity>,
    rootMode: Boolean,
    extraSections: List<Pair<Int, List<DrawerDestination>>>,
    onHome: () -> Unit,
    onOpenPath: (FsPath) -> Unit,
    onRoute: (String) -> Unit,
) {
    ModalDrawerSheet {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
            Item(Icons.Filled.Home, stringResource(R.string.home)) { onHome() }
            Section(R.string.storage)
            volumes.forEach { v ->
                Item(when (v.kind) { VolumeKind.INTERNAL -> Icons.Filled.PhoneAndroid; VolumeKind.SD_CARD -> Icons.Filled.SdCard; VolumeKind.USB -> Icons.Filled.Usb }, v.label) { onOpenPath(v.root) }
            }
            Item(Icons.Filled.AdminPanelSettings, stringResource(R.string.root_folder)) {
                onOpenPath(if (rootMode) FsPath(FsPath.SCHEME_ROOT, "", "/") else FsPath.local("/"))
            }
            if (bookmarks.isNotEmpty()) {
                Section(R.string.favorites)
                bookmarks.forEach { b -> Item(if (b.isFolder) Icons.Filled.Folder else Icons.Filled.Star, b.title) { onOpenPath(FsPath.parse(b.path)) } }
                Item(Icons.Filled.Star, stringResource(R.string.manage_favorites)) { onRoute(Routes.BOOKMARKS) }
            }
            Section(R.string.network)
            drawerNetwork.forEach { d -> Item(d.icon, stringResource(d.label)) { onRoute(d.route) } }
            extraSections.forEach { (title, items) ->
                Section(title)
                items.forEach { d -> Item(d.icon, stringResource(d.label)) { onRoute(d.route) } }
            }
            Section(R.string.tools)
            drawerTools.forEach { d -> Item(d.icon, stringResource(d.label)) { onRoute(d.route) } }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Item(Icons.Filled.Settings, stringResource(R.string.settings)) { onRoute(Routes.SETTINGS) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(title: Int) {
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text(stringResource(title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
private fun Item(icon: ImageVector, label: String, onClick: () -> Unit) {
    NavigationDrawerItem(icon = { Icon(icon, null) }, label = { Text(label, maxLines = 1) }, selected = false, onClick = onClick)
}
