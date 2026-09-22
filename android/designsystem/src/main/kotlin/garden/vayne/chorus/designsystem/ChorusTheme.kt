package garden.vayne.chorus.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

/** The Chorus palette for the current theme (DESIGN.md §1). */
val LocalChorusPalette = staticCompositionLocalOf { Tokens.Light }

/**
 * Soft & warm theme (D-020). Material 3 is only the base: surfaces, ink and accent come from
 * design/tokens.json via the generated [Tokens].
 */
@Composable
fun ChorusTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val p = if (dark) Tokens.Dark else Tokens.Light
    val scheme = if (dark) {
        darkColorScheme(
            primary = p.accent, onPrimary = p.bg, background = p.bg, onBackground = p.ink,
            surface = p.surface, onSurface = p.ink, surfaceVariant = p.surface2, onSurfaceVariant = p.ink2,
            outline = p.line, error = p.danger,
        )
    } else {
        lightColorScheme(
            primary = p.accent, onPrimary = p.surface, background = p.bg, onBackground = p.ink,
            surface = p.surface, onSurface = p.ink, surfaceVariant = p.surface2, onSurfaceVariant = p.ink2,
            outline = p.line, error = p.danger,
        )
    }
    CompositionLocalProvider(LocalChorusPalette provides p) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
