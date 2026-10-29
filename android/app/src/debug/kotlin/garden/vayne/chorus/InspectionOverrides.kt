package garden.vayne.chorus

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import garden.vayne.chorus.ui.LocalChorusMotionOverride

private val LocalInspectionFontScale = staticCompositionLocalOf<Float?> { null }

/** Emulator inspection only: in-process configuration, no global settings or saved prefs. */
@Composable
internal fun InspectionOverrides(intent: Intent, content: @Composable () -> Unit) {
    val current = LocalDensity.current
    val scale = intent.getFloatExtra("chorus_test_font_scale", current.fontScale).coerceIn(.85f, 2f)
    val motion = if (intent.hasExtra("chorus_test_reduce_motion")) !intent.getBooleanExtra("chorus_test_reduce_motion", false) else null
    CompositionLocalProvider(LocalDensity provides Density(current.density, scale), LocalChorusMotionOverride provides motion, LocalInspectionFontScale provides scale, content = content)
}

/** Dialog owners replace LocalDensity; carry the debug scale across that boundary explicitly. */
@Composable
internal fun InspectionDialogContent(content: @Composable () -> Unit) {
    val current = LocalDensity.current
    val scale = LocalInspectionFontScale.current
    if (scale == null) content()
    else CompositionLocalProvider(LocalDensity provides Density(current.density, scale), content = content)
}
