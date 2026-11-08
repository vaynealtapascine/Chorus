package garden.vayne.chorus.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
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
// F10.2's order: Light, Dark, Follow device (stored by name, so the order is free)
enum class ThemeMode(val label: String) { Light("Light"), Dark("Dark"), System("Follow device") }
/** Text size on top of the device's own (F10.2): Default follows it. */
enum class TextSize(val label: String, val scale: Float) { Default("Default", 1f), Large("Large", 1.15f), Larger("Larger", 1.3f) }
enum class Spacing(val label: String) { Comfortable("Comfortable"), Compact("Compact") }

/**
 * How this device draws Chorus (F10.2, `chorus.appearance` prefs, never synced): theme, accent,
 * member colours on names in chat, text size and spacing. [update] saves a changed copy.
 */
data class Appearance(val mode: ThemeMode, val accent: ChorusAccent = ChorusAccent.Teal, val memberColours: Boolean = true,
    val textSize: TextSize = TextSize.Default, val spacing: Spacing = Spacing.Comfortable, val update: (Appearance) -> Unit = {}) {
    fun change(next: ThemeMode) = update(copy(mode = next))
    val compact: Boolean get() = spacing == Spacing.Compact
    /** The values as stored, by key. */
    fun stored(): Map<String, String> = mapOf("theme" to mode.name, "accent" to accent.name,
        "member_colours" to memberColours.toString(), "text_size" to textSize.name, "density" to spacing.name)

    companion object {
        /** Reads stored values; anything missing or unknown keeps its default (dark, teal, D-079). */
        fun read(get: (String) -> String?): Appearance = Appearance(
            runCatching { ThemeMode.valueOf(get("theme")!!) }.getOrDefault(ThemeMode.Dark),
            runCatching { ChorusAccent.valueOf(get("accent")!!) }.getOrDefault(ChorusAccent.Teal),
            get("member_colours") != "false",
            runCatching { TextSize.valueOf(get("text_size")!!) }.getOrDefault(TextSize.Default),
            runCatching { Spacing.valueOf(get("density")!!) }.getOrDefault(Spacing.Comfortable))
    }
}
val LocalChorusAppearance = staticCompositionLocalOf { Appearance(ThemeMode.Dark) }

/** [base] in [accent]'s colours for this mode. */
fun ChorusPalette.withAccent(accent: ChorusAccent, dark: Boolean): ChorusPalette {
    val t = if (dark) accent.dark else accent.light
    return copy(accent = t.accent, accentSoft = t.accentSoft, onAccent = t.onAccent, bubbleOut = t.bubbleOut)
}
@OptIn(ExperimentalTextApi::class)
val HankenGrotesk = FontFamily(
    Font(R.font.hanken_grotesk, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.hanken_grotesk, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.hanken_grotesk, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.hanken_grotesk, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    Font(R.font.hanken_grotesk, FontWeight.Black, variationSettings = FontVariation.Settings(FontVariation.weight(900))),
)

/** D-079: dark by default; an explicit device preference may follow the OS or select light. */
@Composable
fun ChorusTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("chorus.appearance", 0) }
    var appearance by remember { mutableStateOf(Appearance.read { prefs.getString(it, null) }) }
    val mode = appearance.mode
    val dark = mode == ThemeMode.Dark || (mode == ThemeMode.System && isSystemInDarkTheme())
    SideEffect {
        (context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val p = (if (dark) Tokens.Dark else Tokens.Light).withAccent(appearance.accent, dark)
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
    val density = LocalDensity.current
    val provided = remember(appearance) { appearance.copy(update = { next ->
        appearance = next.copy(update = {})
        prefs.edit().apply { next.stored().forEach { (k, v) -> putString(k, v) } }.apply()
    }) }
    CompositionLocalProvider(LocalChorusPalette provides p, LocalChorusDark provides dark,
        LocalChorusAppearance provides provided,
        LocalDensity provides Density(density.density, density.fontScale * appearance.textSize.scale)) {
        // Compose's content colour is black outside a Surface; the app draws on bare backgrounds,
        // so text and icons without an explicit colour would vanish in dark mode
        MaterialTheme(colorScheme = scheme, typography = typography,
            shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(22.dp))) {
            CompositionLocalProvider(LocalContentColor provides p.ink, content = content)
        }
    }
}
