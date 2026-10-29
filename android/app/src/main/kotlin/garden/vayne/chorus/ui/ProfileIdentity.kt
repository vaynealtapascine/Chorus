package garden.vayne.chorus.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import garden.vayne.chorus.data.Blobs
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.Member
import garden.vayne.chorus.designsystem.LocalChorusPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shared profile/editor identity. Missing private/offline media keeps a quiet colour fallback. */
@Composable
internal fun ProfileIdentity(member: Member, chorus: Chorus) {
    val p = LocalChorusPalette.current
    val ctx = LocalContext.current
    val dev = chorus.device
    val image by produceState<android.graphics.Bitmap?>(null, member.bannerBlob, dev?.session) {
        value = null
        value = member.bannerBlob?.let { hash -> withContext(Dispatchers.IO) {
            Blobs.image(ctx, hash, dev, kind = "profile", maxPx = 1500, maxBytes = 10L * 1024 * 1024)
        } }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.fillMaxWidth().height(184.dp)) {
            Box(Modifier.fillMaxWidth().height(144.dp).clip(RoundedCornerShape(14.dp)).background(p.surface2)) {
                image?.let { Image(it.asImageBitmap(), "Profile banner", contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()) }
            }
            Avatar(member.glyph, member.color, 88.dp, avatarBlob = member.avatarBlob,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp))
        }
        Text(member.shownName, color = tonesOf(member.color).name,
            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (member.displayName != null && member.name != member.displayName) Text(member.name, color = p.ink3, style = MaterialTheme.typography.bodyMedium)
        if (!member.pronouns.isNullOrBlank()) Text(member.pronouns, color = p.ink2, style = MaterialTheme.typography.bodyMedium)
        if (member.sigils.isNotEmpty()) Text(member.sigils.filter(String::isNotBlank).joinToString(" · "), color = p.ink3, style = MaterialTheme.typography.bodyMedium)
    }
}
