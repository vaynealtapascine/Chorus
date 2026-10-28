package garden.vayne.chorus.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.unit.dp
import android.app.Activity
import androidx.core.view.WindowCompat

val LocalChorusPalette = staticCompositionLocalOf { Tokens.Dark }
val LocalChorusDark = staticCompositionLocalOf { true }
enum class ThemeMode(val label: String) { Dark("Dark"), Light("Light"), System("Follow device") }
data class Appearance(val mode: ThemeMode, val change: (ThemeMode) -> Unit)
val LocalChorusAppearance = staticCompositionLocalOf { Appearance(ThemeMode.Dark) {} }
@OptIn(ExperimentalTextApi::class)
val HankenGrotesk = FontFamily(
    Font(R.font.hanken_grotesk, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.hanken_grotesk, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.hanken_grotesk, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.hanken_grotesk, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

/** D-079: dark by default; an explicit device preference may follow the OS or select light. */
@Composable
fun ChorusTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("chorus.appearance", 0) }
    var mode by remember { mutableStateOf(runCatching { ThemeMode.valueOf(prefs.getString("theme", "Dark")!!) }.getOrDefault(ThemeMode.Dark)) }
    val dark = mode == ThemeMode.Dark || (mode == ThemeMode.System && isSystemInDarkTheme())
    SideEffect {
        (context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val p = if (dark) Tokens.Dark else Tokens.Light
    val scheme = if (dark) darkColorScheme(
        primary = p.accent, onPrimary = p.onAccent, primaryContainer = p.accentSoft, onPrimaryContainer = p.ink,
        secondary = p.accent, secondaryContainer = p.accentSoft, onSecondaryContainer = p.ink,
        background = p.bg, onBackground = p.ink, surface = p.surface, onSurface = p.ink,
        surfaceVariant = p.surface2, onSurfaceVariant = p.ink2, outline = p.line, error = p.danger,
        surfaceContainer = p.surface, surfaceContainerHigh = p.surface2, surfaceContainerHighest = p.surface3,
    ) else lightColorScheme(
        primary = p.accent, onPrimary = p.onAccent, primaryContainer = p.accentSoft, onPrimaryContainer = p.ink,
        secondary = p.accent, secondaryContainer = p.accentSoft, onSecondaryContainer = p.ink,
        background = p.bg, onBackground = p.ink, surface = p.surface, onSurface = p.ink,
        surfaceVariant = p.surface2, onSurfaceVariant = p.ink2, outline = p.line, error = p.danger,
        surfaceContainer = p.surface, surfaceContainerHigh = p.surface2, surfaceContainerHighest = p.surface3,
    )
    val base = Typography()
    val typography = Typography(
        displayLarge = base.displayLarge.copy(fontFamily = HankenGrotesk), displayMedium = base.displayMedium.copy(fontFamily = HankenGrotesk), displaySmall = base.displaySmall.copy(fontFamily = HankenGrotesk),
        headlineLarge = base.headlineLarge.copy(fontFamily = HankenGrotesk), headlineMedium = base.headlineMedium.copy(fontFamily = HankenGrotesk), headlineSmall = base.headlineSmall.copy(fontFamily = HankenGrotesk),
        titleLarge = base.titleLarge.copy(fontFamily = HankenGrotesk), titleMedium = base.titleMedium.copy(fontFamily = HankenGrotesk), titleSmall = base.titleSmall.copy(fontFamily = HankenGrotesk),
        bodyLarge = base.bodyLarge.copy(fontFamily = HankenGrotesk), bodyMedium = base.bodyMedium.copy(fontFamily = HankenGrotesk), bodySmall = base.bodySmall.copy(fontFamily = HankenGrotesk),
        labelLarge = base.labelLarge.copy(fontFamily = HankenGrotesk), labelMedium = base.labelMedium.copy(fontFamily = HankenGrotesk), labelSmall = base.labelSmall.copy(fontFamily = HankenGrotesk),
    )
    CompositionLocalProvider(LocalChorusPalette provides p, LocalChorusDark provides dark,
        LocalChorusAppearance provides Appearance(mode) { next -> mode = next; prefs.edit().putString("theme", next.name).apply() }) {
        MaterialTheme(colorScheme = scheme, typography = typography,
            shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(22.dp)), content = content)
    }
}
