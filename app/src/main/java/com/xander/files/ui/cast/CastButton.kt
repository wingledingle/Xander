package com.xander.files.ui.cast

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import com.xander.files.data.cast.CastManager

/** Standard Cast icon; hidden automatically when Google Play services (and so casting) is unavailable. */
@Composable
fun CastButton(cast: CastManager, modifier: Modifier = Modifier) {
    if (!cast.available) return
    AndroidView(factory = { c ->
        MediaRouteButton(c).also { b -> runCatching { CastButtonFactory.setUpMediaRouteButton(c.applicationContext, b) } }
    }, modifier = modifier)
}
