package garden.vayne.chorus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.HandoverCard
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R

/**
 * Home's handover card (Figma F0.1): "Handover from Amarynthe", its first line (or "Checklist · N items"), and Read,
 * which acknowledges it. Tapping the card opens the Handover chat at it; several waiting say how many more.
 */
@Composable
internal fun HandoverHomeCard(card: HandoverCard, busy: Boolean, onOpen: () -> Unit, onRead: () -> Unit) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(16.dp)).background(p.accentSoft)
        .clickable(role = Role.Button, onClickLabel = "Open the handover", onClick = onOpen).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(44.dp).background(p.surface2, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            ChatIcon(R.drawable.ic_handover, tint = p.accent)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(card.title, color = p.ink, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (card.line.isNotEmpty()) Text(card.line, color = p.ink3, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (card.more > 0) Text("${card.more} more waiting", color = p.accent, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Box(Modifier.clip(RoundedCornerShape(20.dp)).background(p.accent).clickable(enabled = !busy, role = Role.Button, onClick = onRead)
            .heightIn(min = 40.dp).padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
            Text("Read", color = p.onAccent, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
