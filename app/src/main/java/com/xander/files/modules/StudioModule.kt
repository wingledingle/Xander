package com.xander.files.modules

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import com.xander.files.R

/** Groovesmith Studio: beat sequencer, synth, sampler, video chopper and mixer (modules-web/www/modules/studio). */
object StudioModule : WebModule(
    id = "studio",
    title = R.string.module_studio,
    summary = R.string.module_studio_summary,
    icon = Icons.Filled.GraphicEq,
    opens = setOf("gsproj", "mp4", "mkv", "webm", "mov", "3gp", "avi", "m4v", "ts", "wav", "mp3", "flac", "ogg", "opus", "m4a", "aac", "aif", "aiff"),
    defaultFor = setOf("gsproj"),
    lowLatencyAudio = true,
)
