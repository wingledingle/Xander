package com.xander.files.ui.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import coil3.compose.SubcomposeAsyncImage
import com.xander.files.R
import com.xander.files.data.db.PlaylistEntity
import com.xander.files.data.music.Album
import com.xander.files.data.music.Artist
import com.xander.files.data.music.MusicController
import com.xander.files.data.music.MusicFolder
import com.xander.files.data.music.MusicLibrary
import com.xander.files.data.music.Song
import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FsPath
import com.xander.files.ui.common.EmptyState
import com.xander.files.ui.common.Format
import com.xander.files.ui.common.TextInputDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MusicViewModel @Inject constructor(private val lib: MusicLibrary, val player: MusicController) : ViewModel() {
    val songs = MutableStateFlow<List<Song>>(emptyList())
    val albums = MutableStateFlow<List<Album>>(emptyList())
    val artists = MutableStateFlow<List<Artist>>(emptyList())
    val folders = MutableStateFlow<List<MusicFolder>>(emptyList())
    val playlists = lib.playlistFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val state = player.state

    init { player.connect(); load(false) }

    fun load(refresh: Boolean) = viewModelScope.launch {
        songs.value = lib.songs(refresh); albums.value = lib.albums(); artists.value = lib.artists(); folders.value = lib.folders()
    }

    fun play(list: List<Song>, index: Int, shuffle: Boolean = false) = player.play(list.map(player::songItem), index, shuffle)
    fun playNext(s: Song) = player.enqueue(listOf(player.songItem(s)), next = true)
    fun enqueue(s: List<Song>) = player.enqueue(s.map(player::songItem))
    fun newPlaylist(name: String, songs: List<Song>) = viewModelScope.launch { val id = lib.createPlaylist(name); if (songs.isNotEmpty()) lib.addToPlaylist(id, songs) }
    fun addTo(p: PlaylistEntity, s: List<Song>) = viewModelScope.launch { lib.addToPlaylist(p.id, s) }
    fun deletePlaylist(p: PlaylistEntity) = viewModelScope.launch { lib.deletePlaylist(p.id) }
    fun playPlaylist(p: PlaylistEntity, shuffle: Boolean = false) = viewModelScope.launch {
        val items = lib.itemsOf(p.id).map { player.item(FsPath.parse(it.uri), it.title, it.artist, it.album) }
        if (items.isNotEmpty()) player.play(items, 0, shuffle)
    }
    fun playlistItems(id: Long) = lib.items(id)
    fun removeFromPlaylist(id: Long, pos: Int) = viewModelScope.launch { lib.removeFromPlaylist(id, pos) }
}

@Composable
fun AlbumArt(path: FsPath?, size: Int, modifier: Modifier = Modifier) {
    val m = modifier.size(size.dp).clip(RoundedCornerShape((size / 8).dp))
    if (path == null) { Box(m.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Icon(Icons.Filled.MusicNote, null) }; return }
    SubcomposeAsyncImage(FileEntry(path, false), null, contentScale = ContentScale.Crop, modifier = m,
        loading = { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Icon(Icons.Filled.MusicNote, null) } },
        error = { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Icon(Icons.Filled.MusicNote, null) } })
}

private enum class MusicTab(val label: Int) { SONGS(R.string.songs), ALBUMS(R.string.albums), ARTISTS(R.string.artists), FOLDERS(R.string.folders), PLAYLISTS(R.string.playlists) }

@Composable
fun MusicScreen(onBack: () -> Unit, onNowPlaying: () -> Unit, vm: MusicViewModel = hiltViewModel()) {
    val songs by vm.songs.collectAsStateWithLifecycle()
    val albums by vm.albums.collectAsStateWithLifecycle()
    val artists by vm.artists.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(MusicTab.SONGS) }
    var drill by remember { mutableStateOf<Pair<String, List<Song>>?>(null) }
    var openPlaylist by remember { mutableStateOf<PlaylistEntity?>(null) }
    var addTarget by remember { mutableStateOf<List<Song>?>(null) }
    var newPlaylist by remember { mutableStateOf<List<Song>?>(null) }
    androidx.activity.compose.BackHandler(enabled = drill != null || openPlaylist != null) { drill = null; openPlaylist = null }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = { if (drill != null || openPlaylist != null) { drill = null; openPlaylist = null } else onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                    title = { Text(drill?.first ?: openPlaylist?.name ?: stringResource(R.string.music)) },
                    actions = { IconButton(onClick = { val l = drill?.second ?: songs; vm.play(l, 0, shuffle = true) }) { Icon(Icons.Filled.Shuffle, stringResource(R.string.shuffle)) } }
                )
                if (drill == null && openPlaylist == null) PrimaryScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 8.dp) {
                    MusicTab.entries.forEach { t -> Tab(tab == t, { tab = t }, text = { Text(stringResource(t.label)) }) }
                }
            }
        },
        bottomBar = { if (state.active) MiniPlayer(vm, onNowPlaying) }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            val pl = openPlaylist
            when {
                drill != null -> SongList(drill!!.second, vm, onAddToPlaylist = { addTarget = it })
                pl != null -> PlaylistDetail(pl, vm)
                tab == MusicTab.SONGS -> if (songs.isEmpty()) EmptyState(Icons.Filled.MusicNote, stringResource(R.string.no_music)) else SongList(songs, vm, onAddToPlaylist = { addTarget = it })
                tab == MusicTab.ALBUMS -> LazyColumn { items(albums, key = { it.id }) { a -> GroupRow(a.songs.first().fsPath, a.title.ifEmpty { "—" }, "${a.artist} · ${a.songs.size}") { drill = a.title to a.songs } } }
                tab == MusicTab.ARTISTS -> LazyColumn { items(artists, key = { it.name }) { a -> GroupRow(a.songs.first().fsPath, a.name, stringResource(R.string.albums_songs, a.albums, a.songs.size)) { drill = a.name to a.songs.sortedWith(compareBy({ it.album }, { it.track })) } } }
                tab == MusicTab.FOLDERS -> LazyColumn { items(folders, key = { it.path }) { f -> GroupRow(null, f.name, "${f.songs.size} · ${f.path}") { drill = f.name to f.songs } } }
                else -> LazyColumn {
                    item { TextButton(onClick = { newPlaylist = emptyList() }, modifier = Modifier.padding(8.dp)) { Icon(Icons.Filled.Add, null); Text(" " + stringResource(R.string.new_playlist)) } }
                    items(playlists, key = { it.id }) { p ->
                        Row(Modifier.fillMaxWidth().clickable { openPlaylist = p }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Filled.QueueMusic, null); Text(p.name, Modifier.weight(1f).padding(start = 16.dp))
                            IconButton(onClick = { vm.playPlaylist(p) }) { Icon(Icons.Filled.PlayArrow, stringResource(R.string.play)) }
                            IconButton(onClick = { vm.deletePlaylist(p) }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
                        }
                    }
                }
            }
        }
    }
    addTarget?.let { target ->
        ModalBottomSheet(onDismissRequest = { addTarget = null }) {
            Text(stringResource(R.string.add_to_playlist), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
            TextButton(onClick = { newPlaylist = target; addTarget = null }, modifier = Modifier.padding(horizontal = 8.dp)) { Icon(Icons.Filled.Add, null); Text(" " + stringResource(R.string.new_playlist)) }
            playlists.forEach { p -> Text(p.name, Modifier.fillMaxWidth().clickable { vm.addTo(p, target); addTarget = null }.padding(16.dp)) }
            Box(Modifier.padding(24.dp))
        }
    }
    newPlaylist?.let { songsToAdd -> TextInputDialog(stringResource(R.string.new_playlist), "", selectBaseName = false, onDismiss = { newPlaylist = null }) { n -> vm.newPlaylist(n, songsToAdd); newPlaylist = null } }
}

@Composable
private fun GroupRow(art: FsPath?, title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        AlbumArt(art, 48)
        Column(Modifier.padding(start = 14.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SongList(list: List<Song>, vm: MusicViewModel, onAddToPlaylist: (List<Song>) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    LazyColumn {
        itemsIndexed(list, key = { _, s -> s.id }) { i, s ->
            val current = state.path?.path == s.path
            Row(Modifier.fillMaxWidth().combinedClickable(onClick = { vm.play(list, i) }, onLongClick = { onAddToPlaylist(listOf(s)) })
                .padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                AlbumArt(s.fsPath, 44)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(s.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    Text("${s.artist} · ${Format.duration(s.durationMs / 1000)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                IconButton(onClick = { vm.playNext(s) }) { Icon(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.play_next)) }
                IconButton(onClick = { onAddToPlaylist(listOf(s)) }) { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, stringResource(R.string.add_to_playlist)) }
            }
        }
    }
}

@Composable
private fun PlaylistDetail(p: PlaylistEntity, vm: MusicViewModel) {
    val items by vm.playlistItems(p.id).collectAsStateWithLifecycle(emptyList())
    Column {
        Row(Modifier.padding(8.dp)) {
            TextButton(onClick = { vm.playPlaylist(p) }) { Icon(Icons.Filled.PlayArrow, null); Text(" " + stringResource(R.string.play)) }
            TextButton(onClick = { vm.playPlaylist(p, shuffle = true) }) { Icon(Icons.Filled.Shuffle, null); Text(" " + stringResource(R.string.shuffle)) }
        }
        if (items.isEmpty()) EmptyState(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.playlist_empty))
        LazyColumn {
            itemsIndexed(items, key = { i, it -> "$i${it.uri}" }) { i, it ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    AlbumArt(FsPath.parse(it.uri), 40)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(it.title, maxLines = 1); Text(it.artist.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { vm.removeFromPlaylist(p.id, i) }) { Icon(Icons.Filled.Close, stringResource(R.string.remove)) }
                }
            }
        }
    }
}

@Composable
fun MiniPlayer(vm: MusicViewModel, onOpen: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    Surface(tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AlbumArt(s.path, 44)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(s.title, maxLines = 1, style = MaterialTheme.typography.bodyMedium); Text(s.artist, maxLines = 1, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = { vm.player.toggle() }) { Icon(if (s.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, stringResource(R.string.play)) }
            IconButton(onClick = { vm.player.next() }) { Icon(Icons.Filled.SkipNext, stringResource(R.string.next)) }
        }
    }
}

@Composable
fun NowPlayingScreen(onBack: () -> Unit, castButton: @Composable () -> Unit = {}, vm: MusicViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    var queue by remember { mutableStateOf(false) }
    var dragPos by remember { mutableStateOf<Float?>(null) }
    Scaffold(topBar = {
        TopAppBar(navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } }, title = { Text(stringResource(R.string.now_playing)) },
            actions = { castButton(); IconButton(onClick = { queue = true }) { Icon(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.queue)) } })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f)) { AlbumArt(s.path, 320, Modifier.align(Alignment.Center)) }
            Text(s.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, modifier = Modifier.padding(top = 24.dp))
            Text(listOf(s.artist, s.album).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(value = dragPos ?: (if (s.durationMs > 0) s.positionMs.toFloat() / s.durationMs else 0f), onValueChange = { dragPos = it },
                onValueChangeFinished = { dragPos?.let { vm.player.seek((it * s.durationMs).toLong()) }; dragPos = null }, modifier = Modifier.padding(top = 16.dp))
            Row(Modifier.fillMaxWidth()) {
                Text(Format.duration(s.positionMs / 1000), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                Text(Format.duration(s.durationMs / 1000), style = MaterialTheme.typography.labelSmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp)) {
                IconButton(onClick = { vm.player.setShuffle(!s.shuffle) }) { Icon(Icons.Filled.Shuffle, stringResource(R.string.shuffle), tint = if (s.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                IconButton(onClick = { vm.player.previous() }, modifier = Modifier.size(56.dp)) { Icon(Icons.Filled.SkipPrevious, stringResource(R.string.previous), Modifier.size(36.dp)) }
                FilledIconButton(onClick = { vm.player.toggle() }, modifier = Modifier.size(72.dp), colors = IconButtonDefaults.filledIconButtonColors()) {
                    Icon(if (s.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, stringResource(R.string.play), Modifier.size(40.dp))
                }
                IconButton(onClick = { vm.player.next() }, modifier = Modifier.size(56.dp)) { Icon(Icons.Filled.SkipNext, stringResource(R.string.next), Modifier.size(36.dp)) }
                IconButton(onClick = { vm.player.cycleRepeat() }) {
                    Icon(if (s.repeat == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat, stringResource(R.string.repeat),
                        tint = if (s.repeat != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (queue) ModalBottomSheet(onDismissRequest = { queue = false }) {
        Text(stringResource(R.string.queue), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
        LazyColumn {
            itemsIndexed(s.queue) { i, item ->
                Row(Modifier.fillMaxWidth().clickable { vm.player.skipTo(i) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(item.mediaMetadata.title?.toString().orEmpty(), Modifier.weight(1f), maxLines = 1, color = if (i == s.index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    IconButton(onClick = { vm.player.remove(i) }) { Icon(Icons.Filled.Close, stringResource(R.string.remove)) }
                }
            }
            item { Box(Modifier.padding(24.dp)) }
        }
    }
}
