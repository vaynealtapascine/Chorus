package garden.vayne.chorus.ui

import garden.vayne.chorus.InspectionDialogContent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import garden.vayne.chorus.designsystem.LocalChorusPalette
import kotlin.math.roundToInt

/** Strict six-digit RGB parsing also used to validate precision input before Apply. */
internal object ColourValue {
    fun normalize(raw: String): String? = raw.trim().removePrefix("#").takeIf { it.matches(Regex("[0-9a-fA-F]{6}")) }?.let { "#" + it.uppercase(java.util.Locale.ROOT) }
    fun rgb(raw: String): Color = Color((normalize(raw) ?: "#42A397").drop(1).toLong(16) or 0xFF000000)
    fun hsv(raw: String): FloatArray {
        val value = (normalize(raw) ?: "#42A397").drop(1).toInt(16)
        val r = (value shr 16 and 255) / 255f
        val g = (value shr 8 and 255) / 255f
        val b = (value and 255) / 255f
        val max = maxOf(r, g, b); val min = minOf(r, g, b); val delta = max - min
        val h = when { delta == 0f -> 0f; max == r -> 60f * (((g - b) / delta) % 6f)
            max == g -> 60f * ((b - r) / delta + 2f); else -> 60f * ((r - g) / delta + 4f) }
        return floatArrayOf((h + 360f) % 360f, if (max == 0f) 0f else delta / max, max)
    }
    fun hex(hue: Float, saturation: Float, value: Float): String {
        val h = ((hue % 360f) + 360f) % 360f / 60f
        val c = value * saturation; val x = c * (1f - kotlin.math.abs(h % 2f - 1f)); val m = value - c
        val rgb = when(h.toInt()) { 0 -> floatArrayOf(c,x,0f); 1 -> floatArrayOf(x,c,0f); 2 -> floatArrayOf(0f,c,x)
            3 -> floatArrayOf(0f,x,c); 4 -> floatArrayOf(x,0f,c); else -> floatArrayOf(c,0f,x) }
        val channels = rgb.map { ((it + m) * 255).roundToInt().coerceIn(0, 255) }
        return "#%02X%02X%02X".format(java.util.Locale.ROOT, channels[0], channels[1], channels[2])
    }
}

/** Staged, reusable editor: only Apply writes back to its parent draft. */
@Composable
internal fun ColourField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, label: String = "Colour", enabled: Boolean = true) {
    var open by rememberSaveable { mutableStateOf(false) }
    val p = LocalChorusPalette.current
    OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = modifier.heightIn(min = 56.dp)) {
        Box(Modifier.size(24.dp).background(ColourValue.rgb(value), CircleShape).border(1.dp, p.ink3, CircleShape))
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.Start) {
            Text(label)
            Text(ColourValue.normalize(value) ?: value, style = MaterialTheme.typography.labelSmall, color = p.ink2)
        }
    }
    if (open) ColourPicker(value, onDismiss = { open = false }, onApply = { onChange(it); open = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ColourPicker(initial: String, onDismiss: () -> Unit, onApply: (String) -> Unit) {
    val p = LocalChorusPalette.current
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("chorus.colours", 0) }
    val start = remember(initial) { ColourValue.hsv(initial) }
    var hue by rememberSaveable { mutableStateOf(start[0]) }
    var saturation by rememberSaveable { mutableStateOf(start[1]) }
    var brightness by rememberSaveable { mutableStateOf(start[2]) }
    var precision by rememberSaveable { mutableStateOf(false) }
    var hex by rememberSaveable { mutableStateOf(ColourValue.normalize(initial) ?: "#42A397") }
    val current = ColourValue.hex(hue, saturation, brightness)
    fun choose(value: String) {
        val hsv = ColourValue.hsv(value)
        hue = hsv[0]; saturation = hsv[1]; brightness = hsv[2]; hex = value
    }
    val swatches = listOf("#42A397", "#86BFA5", "#6D9ED2", "#A58BCC", "#D08EA4", "#C0694E", "#E0B560", "#B1ACA1")
    val recent = remember { prefs.getString("recent", "").orEmpty().split(',').mapNotNull(ColourValue::normalize).distinct().take(8) }
    ChorusSheet(onDismissRequest = onDismiss, skipPartiallyExpanded = true, containerColor = p.surface) {

        InspectionDialogContent {
                Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Choose colour", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = onDismiss) { Text("Cancel") }
                }
                // Actual identity preview, with the shared core's theme-aware contrast adaptation.
                val tones = tonesOf(current)
                Row(Modifier.fillMaxWidth().background(tones.tint, RoundedCornerShape(16.dp)).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Avatar("A", current, 44.dp)
                    Column {
                        Text("Colour preview", color = tones.name, style = MaterialTheme.typography.titleMedium)
                        Text(current, color = p.ink, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text("Palette", style = MaterialTheme.typography.labelLarge)
                ColourSwatches(swatches, current, ::choose)
                if (recent.isNotEmpty()) {
                    Text("Recent", style = MaterialTheme.typography.labelLarge)
                    ColourSwatches(recent, current, ::choose)
                }
                Box(Modifier.fillMaxWidth().height(10.dp).background(Brush.horizontalGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)), CircleShape))
                ColourSlider("Hue", hue, 0f..360f, "${hue.roundToInt()} degrees") { hue = it; hex = ColourValue.hex(it, saturation, brightness) }
                Box(Modifier.fillMaxWidth().height(8.dp).background(Brush.horizontalGradient(listOf(ColourValue.rgb(ColourValue.hex(hue, 0f, brightness)), ColourValue.rgb(ColourValue.hex(hue, 1f, brightness)))), CircleShape))
                ColourSlider("Saturation", saturation, 0f..1f, "${(saturation * 100).roundToInt()} percent") { saturation = it; hex = ColourValue.hex(hue, it, brightness) }
                ColourSlider("Brightness", brightness, 0f..1f, "${(brightness * 100).roundToInt()} percent") { brightness = it; hex = ColourValue.hex(hue, saturation, it) }
                TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = { precision = !precision }) { Text(if (precision) "Hide hex value" else "Enter hex value") }
                if (precision) OutlinedTextField(hex, { raw ->
                    hex = raw.take(7)
                    ColourValue.normalize(hex)?.let { choose(it) }
                }, label = { Text("Hex colour") }, supportingText = { Text("Six digits, for example #42A397") }, singleLine = true,
                    isError = ColourValue.normalize(hex) == null, modifier = Modifier.fillMaxWidth())
                Button(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !precision || ColourValue.normalize(hex) != null, onClick = {
                    prefs.edit().putString("recent", (listOf(current) + recent).distinct().take(8).joinToString(",")).apply()
                    onApply(current)
                }) { Text("Apply colour") }
            }
        }
    }
}

@Composable
private fun ColourSwatches(values: List<String>, selected: String, choose: (String) -> Unit) {
    // Four columns keep every target at least 48dp on narrow phones and large text.
    values.chunked(4).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { value ->
                val chosen = value == selected
                Box(Modifier.weight(1f).heightIn(min = 48.dp).semantics {
                    contentDescription = "Colour $value"
                    this.selected = chosen
                    role = Role.Button
                }.clickable { choose(value) }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(34.dp).background(ColourValue.rgb(value), CircleShape)
                        .border(if (chosen) 3.dp else 1.dp, if (chosen) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline, CircleShape))
                }
            }
            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun ColourSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, spokenValue: String, change: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(spokenValue, style = MaterialTheme.typography.bodySmall)
    }
    Slider(value = value, onValueChange = change, valueRange = range,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = label; stateDescription = spokenValue })
}
