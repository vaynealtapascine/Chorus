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
 *  [inherit] ("Same as profile" / "Field default"), Only us, Friends, chosen buckets (past eight
 *  the rest fold under "N more", D-115) and everyone on this server. [onChange] gets `null` for
 *  [inherit]. With [onUseByDefault] (a new post's chip, D-117) the menu ends with a checkbox that
 *  makes the audience shown the one [defaultAudience] new posts start with. */
@Composable
internal fun AudienceChip(label: String, value: Audience, own: Boolean, buckets: List<Pair<String, String>>,
    onChange: (Audience?) -> Unit, inherit: String? = null, limited: Boolean = false, enabled: Boolean = true, person: Boolean = false,
    /** Live buckets by id (D-115): each bucket wears its icon and colour as everywhere else. */
    looks: Map<String, garden.vayne.chorus.data.PrivacyBucket> = emptyMap(),
    defaultAudience: Audience? = null, onUseByDefault: ((Boolean) -> Unit)? = null) {
    val p = LocalChorusPalette.current
    var open by remember { mutableStateOf(false) }
    var allBuckets by remember { mutableStateOf(false) }
    val names = remember(buckets) { buckets.toMap() }
    val private = value.isPrivate(names.keys) || limited
    val text = value.label(names).let { if (person && it == "Only us") "Only me" else it }.let { if (limited && !value.isPrivate(names.keys)) "$it (once shared)" else it }
    val choosing = own || inherit == null
    val chosenBuckets = if (choosing && value.mode == "buckets") value.buckets.filter { it in names } else emptyList()
    val shape = RoundedCornerShape(50)
    Box {
        Row(Modifier.clip(shape).border(1.dp, if (private) p.line else p.accent.copy(alpha = .5f), shape)
            .clickable(enabled = enabled) { open = true }.padding(horizontal = 10.dp, vertical = 4.dp)
            .semantics { contentDescription = "$label: $text${if (choosing) "" else " (${inherit!!.lowercase()})"}. Change who can see it" },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (private) ChorusIcon(AppIcon.Lock, null, Modifier.size(14.dp))
            else garden.vayne.chorus.data.BucketControls.single(value, looks)?.let { BucketGlyph(it.icon, it.color, 14.dp) }
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
            item(if (person) "Only me" else "Only us", null, choosing && value.mode == "private") { onChange(Audience.PRIVATE) }
            item("Friends", if (person) "everyone who follows me" else "everyone who follows us", choosing && value.mode == "followers") { onChange(Audience("followers")) }
            if (buckets.isNotEmpty()) {
                Text("Only some friends", color = p.ink3, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                val (shown, folded) = garden.vayne.chorus.data.BucketControls.listed(buckets, { it.first }, chosenBuckets, allBuckets)
                for ((id, name) in shown) DropdownMenuItem(
                    text = { BucketLabel(name, looks[id]?.icon, looks[id]?.color) },
                    leadingIcon = { Checkbox(id in chosenBuckets, null) },
                    onClick = {
                        val next = if (id in chosenBuckets) chosenBuckets - id else chosenBuckets + id
                        onChange(if (next.isEmpty()) Audience.PRIVATE else Audience("buckets", next))
                    })
                if (folded > 0) DropdownMenuItem(text = { Text("$folded more ${if (folded == 1) "bucket" else "buckets"}…", color = p.accent) },
                    onClick = { allBuckets = true })
            }
            item("Everyone here", "any account on this server", choosing && value.mode == "server") { onChange(Audience("server")) }
            if (onUseByDefault != null && defaultAudience != null) {
                val isDefault = value.same(defaultAudience)
                HorizontalDivider(color = p.line)
                // Only us is the built-in default: there's nothing to turn off
                DropdownMenuItem(text = { Text("Use this audience by default") }, enabled = !(isDefault && value.mode == "private"),
                    leadingIcon = { Checkbox(isDefault, null) }, onClick = { onUseByDefault(!isDefault) })
            }
        }
    }
}
