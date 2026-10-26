package com.xander.files

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.xander.files.data.index.FileIndexer
import com.xander.files.data.nsfw.NsfwRepository
import com.xander.files.data.settings.SettingsRepository
import com.xander.files.di.AppScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/** `adb shell am broadcast -a com.xander.files.debug.NSFW_SCAN -n com.xander.files/.DebugCommands` (debug builds only). */
@AndroidEntryPoint
class DebugCommands : BroadcastReceiver() {
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var indexer: FileIndexer
    @Inject lateinit var nsfw: NsfwRepository
    @Inject @AppScope lateinit var scope: CoroutineScope
    @Inject lateinit var analyzer: com.xander.files.data.analyzer.StorageAnalyzer
    @Inject lateinit var volumes: com.xander.files.data.storage.StorageVolumes

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.xander.files.debug.ANALYZE") {
            scope.launch {
                val root = volumes.volumes().first().root
                val r = analyzer.analyze(root)
                Log.i("XanderDebug", "analyze root=$root files=${r.files} allFiles=${r.allFiles.size} folders=${r.folders} unreadable=${r.unreadable.map { it.first.path + " (" + it.second + ")" }}")
                val disk = java.io.File(root.path).walkTopDown().onEnter { it.canRead() }.count { it.isFile }
                Log.i("XanderDebug", "java.io walk of the same root sees $disk files")
            }
            return
        }
        scope.launch {
            settings.update { it.copy(nsfwEnabled = true, nsfwVideos = true, nsfwChargingOnly = false) }
            indexer.indexAll(full = true)
            Log.i("XanderDebug", "indexed; starting scan")
            nsfw.scan(rescanAll = true)
        }
    }
}
