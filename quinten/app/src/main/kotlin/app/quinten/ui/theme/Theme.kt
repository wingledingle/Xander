package app.quinten.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize

/** One app colour scheme: a light and a dark variant, optionally with a full styled look. */
class AppTheme(
    val id: String, val name: String, val light: ColorScheme, val dark: ColorScheme, val swatch: Color,
    val forceDark: Boolean = false,
    val style: ThemeStyle? = null,
    val forceLight: Boolean = false,
)

/** Wallpaper, fonts and button shapes of a styled theme ("Sherlock Holmes", "Dracula", ...). */
class ThemeStyle(
    /** Full-screen wallpaper image (res/drawable-nodpi) and a small copy for previews. */
    val wallpaper: Int,
    val thumbnail: Int,
    val headingFont: androidx.compose.ui.text.font.FontFamily,
    val labelFont: androidx.compose.ui.text.font.FontFamily,
    val bodyFont: androidx.compose.ui.text.font.FontFamily,
    val buttonShape: androidx.compose.foundation.shape.CornerBasedShape,
    val cardShape: androidx.compose.foundation.shape.CornerBasedShape,
    /** Darkening (or lightening, for light themes) laid over the wallpaper so text stays readable. */
    val scrim: Color,
)

val LocalThemeStyle = androidx.compose.runtime.staticCompositionLocalOf<ThemeStyle?> { null }

object AppThemes {
    private fun pair(
        id: String, name: String, primary: Long, primaryDark: Long, secondary: Long, secondaryDark: Long,
        bgLight: Long? = null, bgDark: Long? = null,
    ): AppTheme {
        val l = lightColorScheme(
            primary = Color(primary), secondary = Color(secondary), tertiary = Color(secondary),
            primaryContainer = Color(primary).copy(alpha = 0.18f).compositeOver(Color.White),
            secondaryContainer = Color(secondary).copy(alpha = 0.18f).compositeOver(Color.White),
        ).let { if (bgLight != null) it.copy(background = Color(bgLight), surface = Color(bgLight), surfaceContainer = Color(bgLight).darken(0.04f), surfaceContainerHigh = Color(bgLight).darken(0.07f), surfaceContainerLow = Color(bgLight).darken(0.02f)) else it }
        val d = darkColorScheme(
            primary = Color(primaryDark), secondary = Color(secondaryDark), tertiary = Color(secondaryDark),
            primaryContainer = Color(primaryDark).copy(alpha = 0.25f).compositeOver(Color(0xFF121212)),
            secondaryContainer = Color(secondaryDark).copy(alpha = 0.25f).compositeOver(Color(0xFF121212)),
        ).let { if (bgDark != null) it.copy(background = Color(bgDark), surface = Color(bgDark), surfaceContainer = Color(bgDark).lighten(0.05f), surfaceContainerHigh = Color(bgDark).lighten(0.09f), surfaceContainerLow = Color(bgDark).lighten(0.03f)) else it }
        return AppTheme(id, name, l, d, Color(primary))
    }

    val ALL: List<AppTheme> = listOf(
        pair("forest", "Forest", 0xFF2E5E4E, 0xFF8FCBB4, 0xFF6D5D3F, 0xFFD9C3A0),
        pair("ocean", "Ocean", 0xFF1F5FA8, 0xFF9CC5FF, 0xFF3D7A8A, 0xFF9FD4E0),
        pair("plum", "Plum", 0xFF6A3D8F, 0xFFD3B4F0, 0xFF8F4D6E, 0xFFF0B4CF),
        pair("crimson", "Crimson", 0xFFA1283A, 0xFFFFB2B8, 0xFF7A5A3A, 0xFFE6C29E),
        pair("amber", "Amber", 0xFF8A5A00, 0xFFFFC46B, 0xFF6B5E3A, 0xFFD8C79A),
        pair("slate", "Slate", 0xFF44546A, 0xFFB8C6DB, 0xFF5A5F6E, 0xFFC4C6D0),
        pair("sepia", "Sepia", 0xFF7A4A1E, 0xFFE3B98A, 0xFF5E4B32, 0xFFD6C2A0, bgLight = 0xFFF4ECD8, bgDark = 0xFF2B2219),
        pair("nord", "Nord", 0xFF5E81AC, 0xFF88C0D0, 0xFF4C566A, 0xFFA3BE8C, bgLight = 0xFFECEFF4, bgDark = 0xFF2E3440),
        pair("dracula", "Dracula", 0xFF7B4FC9, 0xFFBD93F9, 0xFFC0447E, 0xFFFF79C6, bgDark = 0xFF282A36),
        pair("solarized", "Solarized", 0xFF268BD2, 0xFF268BD2, 0xFF2AA198, 0xFF2AA198, bgLight = 0xFFFDF6E3, bgDark = 0xFF002B36),
        pair("terminal", "Terminal", 0xFF1B7F3A, 0xFF40FF6E, 0xFF2D6B2D, 0xFF9BE89B, bgDark = 0xFF080A08).let {
            AppTheme(it.id, it.name, it.dark.copy(onBackground = Color(0xFF7DFF9C), onSurface = Color(0xFF7DFF9C), onSurfaceVariant = Color(0xFF4FC46E)), it.dark.copy(onBackground = Color(0xFF7DFF9C), onSurface = Color(0xFF7DFF9C), onSurfaceVariant = Color(0xFF4FC46E)), Color(0xFF40FF6E), forceDark = true)
        },
        pair("contrast", "High contrast", 0xFF0033CC, 0xFFFFE600, 0xFF000000, 0xFFFFFFFF, bgLight = 0xFFFFFFFF, bgDark = 0xFF000000).let {
            AppTheme(it.id, it.name, it.light.copy(onBackground = Color.Black, onSurface = Color.Black, onSurfaceVariant = Color.Black, outline = Color.Black),
                it.dark.copy(onBackground = Color.White, onSurface = Color.White, onSurfaceVariant = Color.White, outline = Color.White), Color.Black)
        },
    )

    private fun font(res: Int) = androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Font(res))
    private val serif = androidx.compose.ui.text.font.FontFamily.Serif
    private val sans = androidx.compose.ui.text.font.FontFamily.SansSerif

    private fun styled(
        id: String, name: String, dark: Boolean, primary: Long, secondary: Long, onBg: Long, surface: Long, swatch: Long, style: ThemeStyle,
    ): AppTheme {
        val surf = Color(surface)
        val base = if (dark) darkColorScheme(primary = Color(primary), secondary = Color(secondary), tertiary = Color(secondary))
        else lightColorScheme(primary = Color(primary), secondary = Color(secondary), tertiary = Color(secondary))
        val scheme = base.copy(
            onPrimary = if (dark) Color(0xFF140C06) else Color.White,
            // Transparent background lets the wallpaper show; bars are translucent, sheets and dialogs opaque.
            background = Color.Transparent, onBackground = Color(onBg),
            surface = surf.copy(alpha = 0.82f), onSurface = Color(onBg), onSurfaceVariant = Color(onBg).copy(alpha = 0.75f),
            surfaceContainerLowest = surf, surfaceContainerLow = surf, surfaceContainer = surf.lighten(if (dark) 0.04f else 0f).darken(if (dark) 0f else 0.03f),
            surfaceContainerHigh = surf.lighten(if (dark) 0.08f else 0f).darken(if (dark) 0f else 0.06f),
            surfaceContainerHighest = surf.lighten(if (dark) 0.12f else 0f).darken(if (dark) 0f else 0.09f),
            surfaceVariant = surf.lighten(if (dark) 0.10f else 0f).darken(if (dark) 0f else 0.08f),
            primaryContainer = Color(primary).copy(alpha = 0.3f).compositeOver(surf), onPrimaryContainer = Color(onBg),
            secondaryContainer = Color(secondary).copy(alpha = 0.3f).compositeOver(surf), onSecondaryContainer = Color(onBg),
            outline = Color(primary).copy(alpha = 0.7f), outlineVariant = Color(primary).copy(alpha = 0.35f),
        )
        return AppTheme(id, name, scheme, scheme, Color(swatch), forceDark = dark, style = style, forceLight = !dark)
    }

    val STYLED: List<AppTheme> by lazy {
        val elite = font(app.quinten.R.font.special_elite)
        val rochester = font(app.quinten.R.font.rochester)
        val ultra = font(app.quinten.R.font.ultra)
        val syncopate = font(app.quinten.R.font.syncopate_bold)
        val smokum = font(app.quinten.R.font.smokum)
        val rounded = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
        listOf(
            styled("sherlock", "Sherlock Holmes", true, 0xFFE0A85C, 0xFFB8875A, 0xFFF0E2C8, 0xFF241A11, 0xFF8A5A2B,
                ThemeStyle(app.quinten.R.drawable.wp_sherlock, app.quinten.R.drawable.wp_sherlock_thumb, elite, elite, serif, rounded, rounded, Color(0x55120C07))),
            styled("dracula_gothic", "Dracula", true, 0xFFD23A4A, 0xFFB08A9A, 0xFFF2E6E6, 0xFF1A0A10, 0xFF8B0A1A,
                ThemeStyle(app.quinten.R.drawable.wp_dracula, app.quinten.R.drawable.wp_dracula_thumb, rochester, serif, serif, androidx.compose.foundation.shape.CutCornerShape(10.dp), androidx.compose.foundation.shape.CutCornerShape(14.dp), Color(0x44050205))),
            styled("doc_savage", "Doc Savage", true, 0xFFE0A050, 0xFF7FB0C8, 0xFFF5E8D2, 0xFF0D1C2C, 0xFFCD7F32,
                ThemeStyle(app.quinten.R.drawable.wp_docsavage, app.quinten.R.drawable.wp_docsavage_thumb, ultra, ultra, sans,
                    androidx.compose.foundation.shape.CutCornerShape(topStart = 0.dp, topEnd = 12.dp, bottomEnd = 0.dp, bottomStart = 12.dp),
                    androidx.compose.foundation.shape.CutCornerShape(topStart = 0.dp, topEnd = 16.dp, bottomEnd = 0.dp, bottomStart = 16.dp),
                    Color(0x4407121E))),
            styled("lovecraft", "Lovecraft", true, 0xFF6FD6A8, 0xFF8AB0A0, 0xFFD8EFE4, 0xFF06201C, 0xFF1F6B55,
                ThemeStyle(app.quinten.R.drawable.wp_lovecraft, app.quinten.R.drawable.wp_lovecraft_thumb, rochester, serif, serif, androidx.compose.foundation.shape.RoundedCornerShape(50), androidx.compose.foundation.shape.RoundedCornerShape(24.dp), Color(0x44020B0A))),
            styled("space_opera", "Space Opera", true, 0xFF7FD8FF, 0xFFC08AFF, 0xFFE6ECFF, 0xFF0A0A22, 0xFF4A3AB8,
                ThemeStyle(app.quinten.R.drawable.wp_space, app.quinten.R.drawable.wp_space_thumb, syncopate, sans, sans,
                    androidx.compose.foundation.shape.RoundedCornerShape(topStart = 18.dp, topEnd = 3.dp, bottomEnd = 18.dp, bottomStart = 3.dp),
                    androidx.compose.foundation.shape.RoundedCornerShape(topStart = 22.dp, topEnd = 4.dp, bottomEnd = 22.dp, bottomStart = 4.dp),
                    Color(0x3303030C))),
            styled("noir", "Noir", true, 0xFFE8E8E8, 0xFFC03030, 0xFFEDEDED, 0xFF141416, 0xFF2A2A2A,
                ThemeStyle(app.quinten.R.drawable.wp_noir, app.quinten.R.drawable.wp_noir_thumb, elite, elite, serif, androidx.compose.foundation.shape.RoundedCornerShape(0.dp), androidx.compose.foundation.shape.RoundedCornerShape(0.dp), Color(0x44000000))),
            styled("wild_west", "Wild West", false, 0xFF8A3B12, 0xFF5E6B34, 0xFF2E1A0C, 0xFFF4E3C3, 0xFFC2753A,
                ThemeStyle(app.quinten.R.drawable.wp_west, app.quinten.R.drawable.wp_west_thumb, smokum, serif, serif, androidx.compose.foundation.shape.CutCornerShape(6.dp), androidx.compose.foundation.shape.CutCornerShape(8.dp), Color(0x55FFF4DE))),
        )
    }

    fun byId(id: String): AppTheme? = (ALL + STYLED).firstOrNull { it.id == id }
}

private fun Color.compositeOver(bg: Color) = Color(
    red = red * alpha + bg.red * (1 - alpha), green = green * alpha + bg.green * (1 - alpha), blue = blue * alpha + bg.blue * (1 - alpha),
)
private fun Color.darken(f: Float) = Color(red * (1 - f), green * (1 - f), blue * (1 - f))
private fun Color.lighten(f: Float) = Color(red + (1 - red) * f, green + (1 - green) * f, blue + (1 - blue) * f)

private fun ColorScheme.black() = copy(
    background = Color.Black, surface = Color.Black, surfaceContainer = Color(0xFF0C0C0C), surfaceContainerLow = Color(0xFF060606),
    surfaceContainerHigh = Color(0xFF141414), surfaceContainerHighest = Color(0xFF1C1C1C), surfaceVariant = Color(0xFF161616),
)

/**
 * App theme: [themeId] "system" uses the wallpaper colours (Android 12+, else Forest).
 * [darkMode] is "system", "light", "dark" or "black" (pure black for OLED screens).
 */
@Composable
fun QuintenTheme(themeId: String = "system", darkMode: String = "system", content: @Composable () -> Unit) {
    val theme = AppThemes.byId(themeId)
    val dark = theme?.forceDark == true || (theme?.forceLight != true && when (darkMode) {
        "light" -> false
        "dark", "black" -> true
        else -> isSystemInDarkTheme()
    })
    var scheme = when {
        theme != null -> if (dark) theme.dark else theme.light
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        else -> if (dark) AppThemes.ALL[0].dark else AppThemes.ALL[0].light
    }
    val style = theme?.style
    if (dark && darkMode == "black" && style == null) scheme = scheme.black()
    val typography = style?.let { styledTypography(it) } ?: androidx.compose.material3.Typography()
    val shapes = style?.let { androidx.compose.material3.Shapes(small = it.buttonShape, medium = it.cardShape, large = it.cardShape) } ?: androidx.compose.material3.Shapes()
    androidx.compose.runtime.CompositionLocalProvider(LocalThemeStyle provides style) {
        MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes, content = content)
    }
}

private fun styledTypography(s: ThemeStyle): androidx.compose.material3.Typography {
    val t = androidx.compose.material3.Typography()
    fun h(x: androidx.compose.ui.text.TextStyle) = x.copy(fontFamily = s.headingFont)
    fun l(x: androidx.compose.ui.text.TextStyle) = x.copy(fontFamily = s.labelFont)
    fun b(x: androidx.compose.ui.text.TextStyle) = x.copy(fontFamily = s.bodyFont)
    return t.copy(
        displayLarge = h(t.displayLarge), displayMedium = h(t.displayMedium), displaySmall = h(t.displaySmall),
        headlineLarge = h(t.headlineLarge), headlineMedium = h(t.headlineMedium), headlineSmall = h(t.headlineSmall),
        titleLarge = h(t.titleLarge).let { if (s.headingFont == s.labelFont) it else it.copy(fontSize = t.titleLarge.fontSize * 1.25f) },
        titleMedium = h(t.titleMedium), titleSmall = l(t.titleSmall),
        bodyLarge = b(t.bodyLarge), bodyMedium = b(t.bodyMedium), bodySmall = b(t.bodySmall),
        labelLarge = l(t.labelLarge), labelMedium = l(t.labelMedium), labelSmall = l(t.labelSmall),
    )
}

/** Draws the styled theme's wallpaper (if any) behind [content]. */
@Composable
fun ThemedBackground(content: @Composable () -> Unit) {
    val style = LocalThemeStyle.current
    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(if (style == null) MaterialTheme.colorScheme.background else Color.Black)) {
        if (style != null) {
            androidx.compose.foundation.Image(
                androidx.compose.ui.res.painterResource(style.wallpaper), contentDescription = null,
                modifier = androidx.compose.ui.Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(style.scrim))
        }
        content()
    }
}
