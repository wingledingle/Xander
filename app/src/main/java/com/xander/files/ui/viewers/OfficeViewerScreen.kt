package com.xander.files.ui.viewers

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.xander.files.R
import com.xander.files.data.office.OfficeConverter
import com.xander.files.data.open.FileOpener
import com.xander.files.domain.fs.FsPath
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class OfficeViewModel @Inject constructor(saved: SavedStateHandle, private val opener: FileOpener) : ViewModel() {
    val path = FsPath.parse(saved.get<String>("path")!!)
    val html = MutableStateFlow<String?>(null)

    fun load(dark: Boolean) = viewModelScope.launch {
        html.value = runCatching {
            val f = opener.materialize(path)
            withContext(Dispatchers.IO) { OfficeConverter.toHtml(f, path.extension, dark) }
        }.getOrElse { "<html><body><p>${OfficeConverter.esc(it.message ?: "Cannot open this document")}</p></body></html>" }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OfficeViewerScreen(onBack: () -> Unit, vm: OfficeViewModel = hiltViewModel()) {
    val html by vm.html.collectAsStateWithLifecycle()
    val dark = isSystemInDarkTheme()
    androidx.compose.runtime.LaunchedEffect(dark) { vm.load(dark) }
    Scaffold(topBar = {
        TopAppBar(navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } }, title = { Text(vm.path.name, maxLines = 1) })
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            val h = html
            if (h == null) CircularProgressIndicator(Modifier.align(Alignment.Center).padding(24.dp))
            else AndroidView(factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = false
                    settings.builtInZoomControls = true; settings.displayZoomControls = false
                    settings.allowFileAccess = false
                    webViewClient = WebViewClient()
                }
            }, update = { it.loadDataWithBaseURL(null, h, "text/html", "UTF-8", null) }, modifier = Modifier.fillMaxSize())
        }
    }
}
