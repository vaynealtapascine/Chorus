package garden.vayne.chorus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.produceState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.data.Chorus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject
import uniffi.chorus_ffi.adaptColor

/** A member colour adapted for the theme by the core (DESIGN.md §2): name ink, ring, tint. */
data class Tones(val name: Color, val ring: Color, val tint: Color)

private val toneCache = ConcurrentHashMap<Pair<String, Boolean>, Tones>()

private fun parse(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color(0xFFA09184))

fun tones(color: String, dark: Boolean): Tones = toneCache.getOrPut(color to dark) {
    runCatching {
        val j = JSONObject(adaptColor(color, dark, "subtle"))
        Tones(parse(j.getString("name")), parse(j.getString("ring")), parse(j.getString("tint")))
    }.getOrElse { Tones(parse(color), parse(color), Color.Transparent) }
}

@Composable
fun tonesOf(color: String): Tones = tones(color, isSystemInDarkTheme())

/** Circular avatar: sigil or initial on a soft disc, ringed in the member's colour. */
@Composable
fun Avatar(glyph: String, color: String, size: Dp = 48.dp, modifier: Modifier = Modifier,
    avatarBlob: String? = null, ring: Boolean = true) {
    val p = LocalChorusPalette.current
    val t = tonesOf(color)
    val ctx = LocalContext.current
    val device = Chorus.get(ctx).device
    val bitmap = produceState<android.graphics.Bitmap?>(initialValue = null, avatarBlob, device?.session) {
        value = if (avatarBlob != null && device != null) withContext(Dispatchers.IO) {
            AvatarBlobs.load(ctx.applicationContext, avatarBlob, device)
        } else null
    }
    Box(
        modifier.size(size).then(if (ring) Modifier.border(2.dp, t.ring, CircleShape) else Modifier)
            .background(p.surface2, CircleShape).clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap.value
        if (image != null) Image(image.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        else Text(glyph, fontSize = (size.value * 0.45f).sp, fontWeight = FontWeight.Medium, color = t.name)
    }
}

/** "just now", "5 min", "2 h", "3 d" */
fun ago(ms: Long, now: Long = System.currentTimeMillis()): String {
    val s = ((now - ms) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min"
        s < 86_400 -> "${s / 3600} h ${(s % 3600) / 60} min".replace(" 0 min", "")
        else -> "${s / 86_400} d"
    }
}
