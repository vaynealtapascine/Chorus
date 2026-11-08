package garden.vayne.chorus.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.Audience
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.Member
import garden.vayne.chorus.data.MemberSharing
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.PeopleApi
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Who can see a member (UI21, Figma F4.3): the whole profile as one choice, each naming who is
 * in it, then the parts, each never wider than the profile (D-101, D-126). Profile and parts
 * belong to the editor's draft and save with it; who hears when they front is the member's
 * notify policy (NOTIFICATIONS §2.4) and saves as it's chosen, as before.
 */
@Composable
internal fun MemberAudiencePage(chorus: Chorus, model: Model, name: String, profile: Audience, parts: Any?, fields: List<MemberSharing.Field>,
    saved: Member?, enabled: Boolean, onProfile: (Audience) -> Unit, onParts: (Any?) -> Unit, onAnnounce: (JSONObject) -> Unit, onBack: () -> Unit) {
    val p = LocalChorusPalette.current
    BackHandler(onBack = onBack)
    val buckets = model.sharingBuckets
    val names = remember(buckets) { buckets.toMap() }
    val person = model.isPerson
    // who is in each choice, by name, once the server has said (offline it's a count, or nothing)
    val dev = chorus.device
    val follows by produceState<garden.vayne.chorus.data.FollowList?>(null, dev?.session) {
        value = dev?.let { d -> withContext(Dispatchers.IO) { runCatching { PeopleApi.list(d) }.getOrNull() } }
    }
    val friendNames = follows?.let { MemberSharing.friendNames(it) }
    val choices = MemberSharing.profileChoices(profile, buckets)
    val groups = MemberSharing.partGroups(fields)
    var choosing by remember { mutableStateOf<MemberSharing.PartGroup?>(null) }
    var eachOf by remember { mutableStateOf<MemberSharing.PartGroup?>(null) }
    var announcing by remember { mutableStateOf(false) }
    val closed = profile.isPrivate(names.keys)

    Column(Modifier.fillMaxSize().background(p.bg)) {
        SubpageBar("Who can see $name", null, onBack) { BarIcon(R.drawable.ic_check, "Done", p.accent, onClick = onBack) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            SectionLabel("The whole profile")
            FormCard {
                choices.forEachIndexed { i, a ->
                    if (i > 0) FormDivider()
                    val sub = when (a.mode) {
                        "private" -> if (person) null else model.systemName?.takeIf { it.isNotBlank() }
                        "buckets" -> follows?.let { f -> MemberSharing.names(MemberSharing.bucketNames(f, model.bucketAssignments, a.buckets)) }
                            ?: a.buckets.sumOf { model.bucketAssignments[it]?.size ?: 0 }.let { n -> "$n ${if (n == 1) "friend" else "friends"}" }
                        "friends" -> friendNames?.let(MemberSharing::names)
                        "server" -> dev?.base?.let { runCatching { java.net.URI(it).host }.getOrNull() }
                        else -> null
                    }
                    ProfileChoiceRow(a.label(names).let { if (person && it == "Only us") "Only me" else it }, sub, a.same(profile), enabled,
                        tile = { AudienceTile(a, model) }) { onProfile(a) }
                }
            }
            SectionLabel("Each part")
            FormCard {
                groups.forEachIndexed { i, g ->
                    if (i > 0) FormDivider()
                    val shown = MemberSharing.summary(profile, parts, g, names, fields)
                    PartRow(g.title, g.subtitle, shown ?: "Mixed", shown == null || closed || shown == "Only us", enabled) {
                        if (g.keys.size > 1) eachOf = g else choosing = g
                    }
                }
                if (saved != null && !person) {
                    FormDivider()
                    val announce = MemberSharing.announce(saved.notifyPolicy)
                    PartRow("Fronting status", null, MemberSharing.announceLabel(announce, model.privacyBuckets.associate { it.id to it.name }),
                        announce.mode == "nobody", enabled) { announcing = true }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    choosing?.let { g ->
        AudienceMenu("Who can see ${g.title.lowercase()}", model, MemberSharing.summaryAudience(parts, g), MemberSharing.inherits(parts, g),
            inherit = if (g.keys.any { it == "name" } || g.keys.any { it in fields.map { f -> f.id } }) "Default" else "Same as profile",
            onDismiss = { choosing = null }) { a -> onParts(MemberSharing.withAll(parts, g.keys, a)); choosing = null }
    }
    eachOf?.let { g ->
        Drawer({ eachOf = null }, g.title, footer = { DrawerButton("Done", { eachOf = null }) }) {
            AudienceMenuRows(model, MemberSharing.summaryAudience(parts, g), MemberSharing.inherits(parts, g), inherit = "Default") { a -> onParts(MemberSharing.withAll(parts, g.keys, a)) }
            MenuDivider()
            for (key in g.keys) {
                val field = fields.find { it.id == key }
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(field?.name ?: MemberSharing.partName(key), color = p.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    val partsNow = parts
                    AudienceChip(field?.name ?: MemberSharing.partName(key), MemberSharing.effective(profile, partsNow, key, field),
                        own = garden.vayne.chorus.data.ProfileAudience.own(partsNow, key) != null, buckets = buckets,
                        onChange = { onParts(garden.vayne.chorus.data.ProfileAudience.with(partsNow, key, it)) },
                        inherit = if (field != null || key == "name") "Default" else "Same as profile", limited = closed, enabled = enabled,
                        person = person, looks = model.bucketLooks)
                }
            }
        }
    }
    if (announcing && saved != null) {
        val announce = MemberSharing.announce(saved.notifyPolicy)
        Drawer({ announcing = false }, "Who hears when $name fronts", footer = { DrawerButton("Done", { announcing = false }) }) {
            Column {
                MenuRow("Every follower", selected = announce.mode == "everyone") { onAnnounce(MemberSharing.announcePolicy(saved.notifyPolicy, "everyone")) }
                for (b in model.privacyBuckets) MenuRow(b.name, selected = b.id in announce.buckets, leading = { BucketTile(b.icon, b.color, 32.dp) }) {
                    val next = if (b.id in announce.buckets) announce.buckets - b.id else announce.buckets + b.id
                    onAnnounce(MemberSharing.announcePolicy(saved.notifyPolicy, if (next.isEmpty()) "nobody" else JSONObject().put("buckets", org.json.JSONArray(next))))
                }
                MenuRow("Nobody", selected = announce.mode == "nobody") { onAnnounce(MemberSharing.announcePolicy(saved.notifyPolicy, "nobody")) }
            }
        }
    }
}

@Composable
private fun ProfileChoiceRow(title: String, subtitle: String?, on: Boolean, enabled: Boolean, tile: @Composable () -> Unit, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .semantics { selected = on }.padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Radio(on)
        Column(Modifier.weight(1f)) {
            Text(title, color = p.ink, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium)
            subtitle?.let { Text(it, color = p.ink3, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        tile()
    }
}

/** The tile at a choice's right: a lock for only us, a bucket's own look, people for friends, a globe for everyone. */
@Composable
private fun AudienceTile(a: Audience, model: Model) {
    when (a.mode) {
        "private" -> IconTile(R.drawable.ic_lock, null, 32.dp)
        "buckets" -> a.buckets.singleOrNull()?.let { model.bucketLooks[it] }?.let { BucketTile(it.icon, it.color, 32.dp) }
            ?: IconTile(R.drawable.ic_shield, "#8FB8E8", 32.dp)
        "friends" -> IconTile(R.drawable.ic_users, "#EEAA62", 32.dp)
        "server" -> IconTile(R.drawable.ic_globe, "#42A397", 32.dp)
        else -> IconTile(R.drawable.ic_friends, "#B99CE8", 32.dp)
    }
}

@Composable
private fun PartRow(title: String, subtitle: String?, pill: String, quiet: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(enabled = enabled, onClick = onClick).padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, color = p.ink, fontSize = 16.sp, lineHeight = 21.sp)
            subtitle?.let { Text(it, color = p.ink3, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Row(Modifier.clip(CircleShape).background(if (quiet) p.surface2 else p.accentSoft).padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (quiet) Glyph(R.drawable.ic_lock, p.ink2, 13.dp)
            Text(pill, color = if (quiet) p.ink2 else p.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
        Glyph(R.drawable.ic_chevron_right, p.ink3, 18.dp)
    }
}

/** The audience choices as menu rows: [inherit] (null), only us, each bucket, friends, everyone here. */
@Composable
private fun AudienceMenuRows(model: Model, current: Audience?, inheriting: Boolean, inherit: String, onPick: (Audience?) -> Unit) {
    val person = model.isPerson
    MenuRow(inherit, selected = inheriting) { onPick(null) }
    MenuRow(if (person) "Only me" else "Only us", R.drawable.ic_lock, selected = current?.mode == "private") { onPick(Audience.PRIVATE) }
    for (b in model.privacyBuckets) MenuRow(b.name, selected = current?.mode == "buckets" && current.buckets == listOf(b.id),
        leading = { BucketTile(b.icon, b.color, 22.dp) }) { onPick(Audience("buckets", listOf(b.id))) }
    MenuRow("Friends", R.drawable.ic_users, selected = current?.mode == "friends") { onPick(Audience("friends")) }
    MenuRow("Everyone here", R.drawable.ic_globe, selected = current?.mode == "server") { onPick(Audience("server")) }
}

@Composable
private fun AudienceMenu(title: String, model: Model, current: Audience?, inheriting: Boolean, inherit: String, onDismiss: () -> Unit, onPick: (Audience?) -> Unit) {
    MenuDrawer(true, onDismiss, title) { AudienceMenuRows(model, current, inheriting, inherit, onPick) }
}
