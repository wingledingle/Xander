package com.xander.files.modules

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SportsEsports
import com.xander.files.R

/** Oleander: Game Boy Advance emulator (third_party/oleander). */
object OleanderModule : ActivityModule(
    id = "oleander",
    title = R.string.module_oleander,
    summary = R.string.module_oleander_summary,
    icon = Icons.Filled.SportsEsports,
    activity = dev.oleander.app.MainActivity::class.java,
    opens = setOf("gba", "agb", "mb", "zip"),
    defaultFor = setOf("gba", "agb"),
)
