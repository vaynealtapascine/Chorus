package garden.vayne.chorus.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import garden.vayne.chorus.data.Audience
import garden.vayne.chorus.designsystem.LocalChorusPalette

/** Who can see one profile part, field, or the whole profile (D-101): a chip with a menu of
 *  [inherit] ("Same as profile" / "Field default"), Only us, Friends, chosen buckets and, under
 *  More, everyone on this server. [onChange] gets `null` for [inherit]. */
@Composable
internal fun AudienceChip(label: String, value: Audience, own: Boolean, buckets: List<Pair<String, String>>,
    onChange: (Audience?) -> Unit, inherit: String? = null, limited: Boolean = false, enabled: Boolean = true) {
    val p = LocalChorusPalette.current
    var open by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    val names = remember(buckets) { buckets.toMap() }
    val private = value.isPrivate(names.keys) || limited
    val text = value.label(names).let { if (limited && !value.isPrivate(names.keys)) "$it (once shared)" else it }
    val choosing = own || inherit == null
    val chosenBuckets = if (choosing && value.mode == "buckets") value.buckets.filter { it in names } else emptyList()
    val shape = RoundedCornerShape(50)
    Box {
        Row(Modifier.clip(shape).border(1.dp, if (private) p.line else p.accent.copy(alpha = .5f), shape)
            .clickable(enabled = enabled) { open = true }.padding(horizontal = 10.dp, vertical = 4.dp)
            .semantics { contentDescription = "$label: $text${if (choosing) "" else " (${inherit!!.lowercase()})"}. Change who can see it" },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (private) ChorusIcon(AppIcon.Lock, null, Modifier.size(14.dp))
            Text(text, color = if (private) p.ink3 else p.accent, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
        DropdownMenu(open, { open = false }) {
            Text("Who can see ${label.lowercase()}", color = p.ink3, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            @Composable fun item(title: String, sub: String?, on: Boolean, pick: () -> Unit) = DropdownMenuItem(
                text = { Column {
                    Text(title, fontWeight = if (on) FontWeight.SemiBold else null, color = if (on) p.accent else p.ink)
                    sub?.let { Text(it, color = p.ink3, style = MaterialTheme.typography.bodySmall) }
                } }, onClick = { pick(); open = false })
            inherit?.let { item(it, null, !own) { onChange(null) } }
            item("Only us", null, choosing && value.mode == "private") { onChange(Audience.PRIVATE) }
            item("Friends", "everyone who follows us", choosing && value.mode == "followers") { onChange(Audience("followers")) }
            if (buckets.isNotEmpty()) {
                Text("Only some friends", color = p.ink3, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                for ((id, name) in buckets) DropdownMenuItem(
                    text = { Text(name) },
                    leadingIcon = { Checkbox(id in chosenBuckets, null) },
                    onClick = {
                        val next = if (id in chosenBuckets) chosenBuckets - id else chosenBuckets + id
                        onChange(if (next.isEmpty()) Audience.PRIVATE else Audience("buckets", next))
                    })
            }
            if (more || value.mode == "server") item("Everyone here", "any account on this server", choosing && value.mode == "server") { onChange(Audience("server")) }
            else DropdownMenuItem(text = { Text("More…", color = p.ink3) }, onClick = { more = true })
        }
    }
}
