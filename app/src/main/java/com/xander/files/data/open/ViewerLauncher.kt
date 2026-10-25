package com.xander.files.data.open

import android.content.Intent
import android.net.Uri
import com.xander.files.data.bookmarks.BookmarkRepository
import com.xander.files.data.media.MediaLibraryHelper
import com.xander.files.data.music.MusicController
import com.xander.files.data.settings.SettingsRepository
import com.xander.files.domain.fs.FileCategory
import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FsException
import com.xander.files.domain.fs.FsPath
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject
import javax.inject.Singleton

/** What the UI shell should do to show a file. */
sealed interface OpenRequest {
    data class Route(val route: String) : OpenRequest
    data class Launch(val intent: Intent) : OpenRequest
    data class Error(val message: String) : OpenRequest
}

/**
 * Single entry point for "open this file" from the browser, search, library, recents and bookmarks.
 * Picks the built-in viewer (or the external app when built-in viewers are off / unsupported) and
 * publishes a request that the navigation host fulfils.
 */
@Singleton
class ViewerLauncher @Inject constructor(
    private val opener: FileOpener,
    private val settings: SettingsRepository,
    private val music: MusicController,
    private val media: MediaLibraryHelper,
    private val bookmarks: BookmarkRepository,
) {
    private val channel = Channel<OpenRequest>(Channel.BUFFERED)
    val requests = channel.receiveAsFlow()

    suspend fun open(entry: FileEntry, forceKind: ViewerKind? = null) {
        runCatching { bookmarks.recordOpened(entry) }
        val kind = forceKind ?: if (settings.settings.first().builtInViewers) ViewerRouter.kindOf(entry) else ViewerKind.EXTERNAL
        try {
            channel.send(
                when (kind) {
                    ViewerKind.IMAGE -> OpenRequest.Route(route("image", entry.path))
                    ViewerKind.VIDEO -> OpenRequest.Route(route("video", entry.path))
                    ViewerKind.PDF -> OpenRequest.Route(route("pdf", entry.path))
                    ViewerKind.OFFICE -> OpenRequest.Route(route("office", entry.path))
                    ViewerKind.BOOK -> OpenRequest.Route(route("book", entry.path))
                    ViewerKind.TEXT -> OpenRequest.Route(route("editor", entry.path))
                    ViewerKind.AUDIO -> {
                        // Play the whole folder starting at the tapped track, like a music player would.
                        val tracks = runCatching { media.siblings(entry.path, setOf(FileCategory.AUDIO)) }.getOrDefault(emptyList()).ifEmpty { listOf(entry) }
                        music.play(tracks.map(music::fileItem), tracks.indexOfFirst { it.path == entry.path }.coerceAtLeast(0))
                        OpenRequest.Route(NOW_PLAYING)
                    }
                    ViewerKind.EXTERNAL -> external(entry)
                }
            )
        } catch (e: Exception) {
            channel.send(OpenRequest.Error(FsException.wrap(e).message ?: "Cannot open"))
        }
    }

    suspend fun openExternal(entry: FileEntry) = try { channel.send(external(entry)) } catch (e: Exception) {
        channel.send(OpenRequest.Error(FsException.wrap(e).message ?: "Cannot open"))
    }

    private suspend fun external(entry: FileEntry): OpenRequest {
        val i = opener.openExternal(entry)
        return OpenRequest.Launch(if (i.component != null) i else Intent.createChooser(i, entry.name))
    }

    companion object {
        const val NOW_PLAYING = "nowplaying"
        fun route(kind: String, p: FsPath) = "$kind?path=${Uri.encode(p.toString())}"
    }
}
