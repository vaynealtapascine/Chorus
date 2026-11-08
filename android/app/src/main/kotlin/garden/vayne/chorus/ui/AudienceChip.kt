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
        MenuDrawer(open, { open = false }, "Who can see ${label.lowercase()}") {
            @Composable fun item(title: String, sub: String?, on: Boolean, pick: () -> Unit) =
                MenuRow(title, subtitle = sub, selected = on) { pick(); open = false }
            inherit?.let { item(it, null, !own) { onChange(null) } }
            item(if (person) "Only me" else "Only us", null, choosing && value.mode == "private") { onChange(Audience.PRIVATE) }
            item("Friends", "you follow each other", choosing && value.mode == "friends") { onChange(Audience("friends")) }
            item("Followers", if (person) "everyone who follows me" else "everyone who follows us", choosing && value.mode == "followers") { onChange(Audience("followers")) }
            item("Following", if (person) "everyone I follow" else "everyone we follow", choosing && value.mode == "following") { onChange(Audience("following")) }
            if (buckets.isNotEmpty()) {
                SectionLabel("Only some followers")
                val (shown, folded) = garden.vayne.chorus.data.BucketControls.listed(buckets, { it.first }, chosenBuckets, allBuckets)
                for ((id, name) in shown) Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp))
                    .clickable {
                        val next = if (id in chosenBuckets) chosenBuckets - id else chosenBuckets + id
                        onChange(if (next.isEmpty()) Audience.PRIVATE else Audience("buckets", next))
                    }.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(id in chosenBuckets, null)
                    BucketLabel(name, looks[id]?.icon, looks[id]?.color)
                }
                if (folded > 0) MenuRow("$folded more ${if (folded == 1) "bucket" else "buckets"}…") { allBuckets = true }
            }
            item("Everyone here", "any account on this server", choosing && value.mode == "server") { onChange(Audience("server")) }
            if (onUseByDefault != null && defaultAudience != null) {
                val isDefault = value.same(defaultAudience)
                MenuDivider()
                // Only us is the built-in default: there's nothing to turn off
                ToggleRow("Use this audience by default", isDefault, enabled = !(isDefault && value.mode == "private")) { onUseByDefault(!isDefault) }
            }
        }
    }
}
