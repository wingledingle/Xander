package com.xander.files.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.xander.files.data.settings.AppSettings
import com.xander.files.data.settings.ThemeMode

/** Accent palette selectable in settings. */
val Accents = listOf(
    Color(0xFF1E6FD9), Color(0xFF00897B), Color(0xFF7B1FA2), Color(0xFFE65100),
    Color(0xFFC2185B), Color(0xFF2E7D32), Color(0xFF5D4037), Color(0xFF455A64),
)

data class Skin(val id: String, val premium: Boolean, val lightBg: Color, val darkBg: Color, val surfaceTint: Color?)

/** Skins; all but "default" are premium. */
val Skins = listOf(
    Skin("default", false, Color(0xFFF7F9FC), Color(0xFF121418), null),
    Skin("ocean", true, Color(0xFFEAF4FB), Color(0xFF0B1A26), Color(0xFF0277BD)),
    Skin("forest", true, Color(0xFFEFF6EE), Color(0xFF0E1A10), Color(0xFF2E7D32)),
    Skin("sunset", true, Color(0xFFFFF3EC), Color(0xFF221410), Color(0xFFE64A19)),
    Skin("graphite", true, Color(0xFFF1F1F1), Color(0xFF1A1A1A), Color(0xFF616161)),
    Skin("rose", true, Color(0xFFFFF0F4), Color(0xFF241218), Color(0xFFD81B60)),
)

data class Density(val compact: Boolean) {
    val rowHeight get() = if (compact) 52 else 64
    val iconSize get() = if (compact) 36 else 44
}

val LocalDensityPrefs = staticCompositionLocalOf { Density(false) }

@Composable
fun XanderTheme(settings: AppSettings, isPremium: Boolean = false, content: @Composable () -> Unit) {
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.BLACK -> true
    }
    val accent = Accents.getOrElse(settings.accent) { Accents[0] }
    val skin = Skins.firstOrNull { it.id == settings.skin && (!it.premium || isPremium) } ?: Skins[0]
    val ctx = LocalContext.current
    var scheme: ColorScheme = when {
        settings.dynamicColor && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(
            primary = lighten(skin.surfaceTint ?: accent), secondary = lighten(skin.surfaceTint ?: accent),
            background = skin.darkBg, surface = skin.darkBg,
            surfaceContainer = skin.darkBg.blend(Color.White, 0.06f),
            surfaceContainerHigh = skin.darkBg.blend(Color.White, 0.09f),
            surfaceContainerLow = skin.darkBg.blend(Color.White, 0.03f),
        )
        else -> lightColorScheme(
            primary = skin.surfaceTint ?: accent, secondary = skin.surfaceTint ?: accent,
            background = skin.lightBg, surface = skin.lightBg,
            surfaceContainer = skin.lightBg.blend(Color.Black, 0.04f),
            surfaceContainerHigh = skin.lightBg.blend(Color.Black, 0.07f),
            surfaceContainerLow = skin.lightBg.blend(Color.Black, 0.02f),
        )
    }
    if (settings.themeMode == ThemeMode.BLACK) {
        scheme = scheme.copy(
            background = Color.Black, surface = Color.Black, surfaceContainer = Color(0xFF0C0C0C),
            surfaceContainerLow = Color(0xFF060606), surfaceContainerHigh = Color(0xFF141414), surfaceContainerHighest = Color(0xFF1A1A1A),
        )
    }
    val base = Typography()
    val typography = if (settings.compactText) base.copy(
        bodyLarge = base.bodyLarge.copy(fontSize = 14.sp), bodyMedium = base.bodyMedium.copy(fontSize = 13.sp),
        bodySmall = base.bodySmall.copy(fontSize = 11.sp), titleMedium = base.titleMedium.copy(fontSize = 14.sp),
        labelSmall = base.labelSmall.copy(fontSize = 10.sp),
    ) else base
    androidx.compose.runtime.CompositionLocalProvider(LocalDensityPrefs provides Density(settings.compactText)) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}

private fun lighten(c: Color) = c.blend(Color.White, 0.35f)

fun Color.blend(other: Color, f: Float) = Color(
    red = red + (other.red - red) * f, green = green + (other.green - green) * f,
    blue = blue + (other.blue - blue) * f, alpha = 1f
)

object CategoryColors {
    val image = Color(0xFF8E24AA); val video = Color(0xFFE53935); val audio = Color(0xFFFB8C00)
    val document = Color(0xFF1E88E5); val apk = Color(0xFF43A047); val archive = Color(0xFF6D4C41)
    val download = Color(0xFF00ACC1); val recent = Color(0xFF3949AB); val apps = Color(0xFF7CB342)
    val large = Color(0xFFD81B60); val folder = Color(0xFFFFB300); val other = Color(0xFF78909C)
}
