package app.quinten.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.quinten.ui.theme.LocalThemeStyle

/*
 * Buttons that take their shape (and a decorative border) from the styled app theme: gothic cut
 * corners for Dracula, art-deco corners for Doc Savage, and so on. Plain themes look standard.
 */

@Composable
fun QButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    val style = LocalThemeStyle.current
    Button(
        onClick, modifier, enabled,
        shape = style?.buttonShape ?: ButtonDefaults.shape,
        border = style?.let { BorderStroke(1.5.dp, MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.35f)) },
        content = content,
    )
}

@Composable
fun QOutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    val style = LocalThemeStyle.current
    OutlinedButton(
        onClick, modifier, enabled,
        shape = style?.buttonShape ?: ButtonDefaults.outlinedShape,
        colors = if (style != null) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface) else ButtonDefaults.outlinedButtonColors(),
        border = if (style != null) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else ButtonDefaults.outlinedButtonBorder(enabled),
        content = content,
    )
}

@Composable
fun QTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding, content: @Composable RowScope.() -> Unit) {
    val style = LocalThemeStyle.current
    TextButton(onClick, modifier, enabled, shape = style?.buttonShape ?: ButtonDefaults.textShape, contentPadding = contentPadding, content = content)
}
