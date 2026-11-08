package garden.vayne.chorus.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.AccountPrefs
import garden.vayne.chorus.data.BucketControls
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.FollowInfo
import garden.vayne.chorus.data.FollowPresets
import garden.vayne.chorus.data.Friends
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.PeopleApi
import garden.vayne.chorus.data.PrivacyBucket
import garden.vayne.chorus.data.SpaceAccount
import garden.vayne.chorus.data.Subject
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Ceiling details the bucket page (F7.10) and "Just for …" show as rows; the rest are under More details. */
private val MAIN_DETAILS = listOf("levels", "delay", "time", "stacking", "announce_leaving", "quiet_hours")
private val SHARING_KEYS = setOf("share_current_front", "share_history", "share_stats")
/** A short line under a detail set here (the Figma's "Hides when you switched"). */
private val DETAIL_NOTES = mapOf("delay" to "Hides when you switched", "time" to "Hides the exact time afterwards",
    "levels" to "Who counts as here", "stacking" to "Several switches in a row", "quiet_hours" to "Your system’s time")

/** F7.8 Sharing: buckets with who's in them and how much they hear, then everyone else. */
internal fun LazyListScope.sharingItems(model: Model, followers: List<FollowInfo>, onBucket: (String) -> Unit, onNewBucket: () -> Unit,
    onAdvanced: () -> Unit, chorus: Chorus) {
    item(key = "h:buckets") { SectionLabel("Buckets", Modifier.padding(horizontal = 12.dp)) {
        Text("who · how much", color = LocalChorusPalette.current.ink3, fontSize = 12.sp) } }
    for (b in model.privacyBuckets) item(key = "b:${b.id}") { BucketRow(model, b, followers.map { it.account }) { onBucket(b.id) } }
    item(key = "new") { NewBucketButton(onNewBucket) }
    item(key = "h:else") { SectionLabel("Everyone else", Modifier.padding(horizontal = 12.dp)) }
    item(key = "start") { StartInRow(chorus, model) }
    item(key = "advanced") {
        LinkRow("Advanced sharing", "Everyone else, front history, stats, extra fronts", R.drawable.ic_sliders, onClick = onAdvanced)
    }
}

/** One bucket (F7.8): its tile, who's in it, how much they hear. */
@Composable
internal fun BucketRow(model: Model, b: PrivacyBucket, followers: List<SpaceAccount>, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    val inside = followers.filter { it.id in model.bucketAssignments[b.id].orEmpty() }
    Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Open ${b.name}", onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        BucketTile(b.icon, b.color, 44.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(b.name, color = p.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (inside.isEmpty()) Text("Nobody yet", color = p.ink3, fontSize = 13.sp)
            else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FaceStack(inside.map { Subject("account", it.id, it.shownName, accountColor(it.id), it.shownName.removePrefix("@").take(1).uppercase(), it.avatarBlob) },
                    max = 3, size = 22.dp)
                Text(inside.joinToString(", ") { it.shownName }, color = p.ink2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (!model.isPerson) Text(Friends.presetName(FollowPresets.choiceOf(b.ceiling)).let { if (it == "From their buckets") "Everyone else’s" else it },
            color = p.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(p.surface2).padding(horizontal = 10.dp, vertical = 4.dp))
        Glyph(R.drawable.ic_chevron_right, p.ink3, 18.dp)
    }
}

/** The dashed "+ New bucket" (F7.8). */
@Composable
internal fun NewBucketButton(onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(14.dp))
        .layerOutline(p.line, 14.dp, true, 1.5.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_plus), null, Modifier.size(18.dp), tint = p.accent)
        Spacer(Modifier.width(8.dp))
        Text("New bucket", color = p.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** "New friends start in" (F7.8): the bucket a request's accept drawer starts on. */
@Composable
private fun StartInRow(chorus: Chorus, model: Model) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    val start = startBucket(model)
    val fallback = Friends.presetName(Friends.everyoneElse(model.accountPrefs.followCeiling))
    LinkRow("New friends start in", start?.let { "${it.name} · ${Friends.presetName(FollowPresets.choiceOf(it.ceiling))}" } ?: "No bucket · $fallback",
        R.drawable.ic_user_plus) { open = true }
    fun choose(id: String?) {
        open = false
        scope.launch { runCatching { chorus.create("pref.set", null, AccountPrefs.payload(Friends.START_BUCKET, id ?: "")) } }
    }
    MenuDrawer(open, { open = false }, "New friends start in") {
        MenuRow("No bucket", subtitle = "Everyone else · $fallback", selected = start == null, leading = { BucketTile(null, null, 36.dp) }) { choose(null) }
        for (b in model.privacyBuckets) MenuRow(b.name, subtitle = Friends.presetName(FollowPresets.choiceOf(b.ceiling)), selected = start?.id == b.id,
            leading = { BucketTile(b.icon, b.color, 36.dp) }) { choose(b.id) }
    }
}

/**
 * F7.9: what one follower sees of us right now, how much they hear, their buckets, what else we
 * share with them, and the rest just for them. Every change saves as it's made.
 */
@Composable
internal fun FriendSharingPage(chorus: Chorus, model: Model, f: FollowInfo, onBack: () -> Unit, onBucket: (String) -> Unit,
    act: (String, suspend () -> Unit) -> Unit, onRemoved: () -> Unit) {
    val p = LocalChorusPalette.current
    val name = f.account.shownName
    val ownName = model.systemName?.takeIf { it.isNotBlank() } ?: "you"
    val now = remember { System.currentTimeMillis() }
    val override = model.followCeilings[f.id] ?: JSONObject()
    val theirs = model.privacyBuckets.filter { f.account.id in model.bucketAssignments[it.id].orEmpty() }
    val seen by produceState<garden.vayne.chorus.data.FollowerView?>(null, f.id, override.toString(), theirs.map { it.ceiling.toString() }) {
        val dev = chorus.device ?: return@produceState
        value = runCatching { PeopleApi.followerSees(dev, f.id) }.getOrNull()
    }
    fun setCeiling(next: JSONObject) = act("Couldn’t save that.") { chorus.create("follow.set_ceiling", f.id, JSONObject().put("ceiling", next)) }
    fun bucketOp(kind: String, bucket: String) = act("Couldn’t change their buckets.") {
        chorus.create(kind, bucket, JSONObject().put("follower_account_id", f.account.id))
    }
    fun inheritedBool(key: String, default: Boolean): Boolean {
        val account = model.accountPrefs.followCeiling
        val fallback = if (account.has(key)) account.optBoolean(key) else default
        return if (theirs.isEmpty()) fallback else theirs.any { if (it.ceiling.has(key)) it.ceiling.optBoolean(key) else fallback }
    }
    fun effective(key: String, default: Boolean) = if (override.has(key)) override.optBoolean(key) else inheritedBool(key, default)
    var adding by remember { mutableStateOf(false) }
    var bucketMenu by remember { mutableStateOf<PrivacyBucket?>(null) }
    var hears by remember { mutableStateOf(false) }
    var justFor by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(p.bg)) {
        SubpageBar("Sharing with $name", listOfNotNull("Follows $ownName", Friends.friendsSince(f.createdAt, now)?.removePrefix("friends ")).joinToString(" · "), onBack) {
            BarIcon(R.drawable.ic_check, "Done", p.accent, onClick = onBack)
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            val pad = Modifier.padding(horizontal = 16.dp)
            if (!model.isPerson) Box(pad) { SeenCard(name, seen, now) }
            if (!model.isPerson) {
                SectionLabel("How much they hear", Modifier.padding(horizontal = 12.dp))
                val choice = FollowPresets.choiceOf(override)
                Segmented(Friends.PRESETS.map(Friends::presetName), Friends.PRESETS.indexOf(choice), pad, accent = true) { i ->
                    setCeiling(FollowPresets.ceiling(Friends.PRESETS[i], override))
                }
                val bucketsSay = theirs.firstOrNull()?.let { "${it.name}: ${Friends.presetName(FollowPresets.choiceOf(it.ceiling).let { c ->
                    if (c == "inherit") Friends.everyoneElse(model.accountPrefs.followCeiling) else c })}" }
                    ?: "Everyone else: ${Friends.presetName(Friends.everyoneElse(model.accountPrefs.followCeiling))}"
                Text(when (choice) { "inherit" -> "As their bucket says ($bucketsSay). Pick one to set it just for them."
                    "custom" -> "Set detail by detail under Just for $name."; else -> Friends.presetAbout(choice) },
                    color = p.ink2, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp, start = 20.dp, end = 16.dp))
            }
            SectionLabel("Buckets", Modifier.padding(horizontal = 12.dp))
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(pad, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (b in theirs) Row(Modifier.clip(RoundedCornerShape(10.dp)).background(p.accentSoft).clickable { bucketMenu = b }
                    .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BucketGlyph(b.icon, b.color, 15.dp)
                    Text(b.name, color = p.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                if (model.privacyBuckets.any { it !in theirs }) Row(Modifier.clip(RoundedCornerShape(10.dp)).background(p.surface2).clickable { adding = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(painterResource(R.drawable.ic_plus), null, Modifier.size(14.dp), tint = p.ink2)
                    Text("Add", color = p.ink2, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                if (model.privacyBuckets.isEmpty()) EmptyState(R.drawable.ic_shield, "No buckets yet")
            }
            if (!model.isPerson) {
                SectionLabel("Also share", Modifier.padding(horizontal = 12.dp))
                ToggleRow("Who’s here now", effective("share_current_front", true), "On their Friends tab and our profile") {
                    setCeiling(BucketControls.withField(override, "share_current_front", it, false))
                }
                ToggleRow("Front history", effective("share_history", false), "Last 30 days, times as shared") {
                    setCeiling(FollowPresets.withSharing(override, "share_history", it))
                }
                ToggleRow("Fronting stats", effective("share_stats", false), "Each member’s share, whole days only") {
                    setCeiling(FollowPresets.withSharing(override, "share_stats", it))
                }
                val hearing = model.active.filter { !it.isSelf }.map { it.shownName to Friends.announcedTo(it.notifyPolicy, theirs.map { b -> b.id }.toSet()) }
                LinkRow("Who they hear about", Friends.hearsAbout(hearing), null) { hears = true }
                LinkRow("Just for $name", "Delay, time shown, quiet hours, extra fronts", R.drawable.ic_sliders) { justFor = true }
            }
            Text("Remove follower", color = p.danger, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 20.dp).clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button) { removing = true }.padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }
    MenuDrawer(adding, { adding = false }, "Add to a bucket", "Most open bucket wins") {
        for (b in model.privacyBuckets.filter { it !in theirs }) MenuRow(b.name, subtitle = if (model.isPerson) null else Friends.presetName(FollowPresets.choiceOf(b.ceiling)),
            leading = { BucketTile(b.icon, b.color, 36.dp) }) { adding = false; bucketOp("bucket.assign", b.id) }
    }
    bucketMenu?.let { b ->
        MenuDrawer(true, { bucketMenu = null }, b.name, null) {
            MenuRow("Open ${b.name}", R.drawable.ic_chevron_right) { bucketMenu = null; onBucket(b.id) }
            MenuRow("Take $name out", R.drawable.ic_close, danger = true) { bucketMenu = null; bucketOp("bucket.unassign", b.id) }
        }
    }
    if (hears) Drawer({ hears = false }, "Who $name hear about") {
        val bucketIds = theirs.map { it.id }.toSet()
        for (m in model.active.filter { !it.isSelf }) {
            var editing by remember(m.id) { mutableStateOf(false) }
            val on = Friends.announcedTo(m.notifyPolicy, bucketIds)
            MenuRow(m.shownName, subtitle = if (on) "They hear when ${m.shownName} fronts" else "Kept from them", leading = { ChatAvatar(m, 36.dp) }) { editing = true }
            if (editing) Drawer({ editing = false }, m.shownName, "Who hears when ${m.shownName} fronts") {
                MemberAnnounce(model, m, true) { policy ->
                    act("Couldn’t save that.") { chorus.create("member.set", m.id, JSONObject().put("notify_policy", policy)) }
                }
            }
        }
    }
    if (justFor) Drawer({ justFor = false }, "Just for $name") {
        // what they'd get without these: their (first) bucket over everyone else's
        val beneath = JSONObject(model.accountPrefs.followCeiling.toString()).apply {
            theirs.firstOrNull()?.ceiling?.let { c -> for (k in c.keys()) put(k, c.get(k)) }
        }
        CeilingDetails(model, override, if (theirs.isEmpty()) "Everyone else" else "their buckets", beneath, true, keys = null) { setCeiling(it) }
        if (override.length() > 0) DrawerButton("Use their buckets for everything", {
            setCeiling(JSONObject().apply { for (k in listOf("fronts", "layers")) if (override.has(k)) put(k, override.get(k)) })
        }, ButtonKind.Neutral)
    }
    if (removing) ConfirmDrawer("Remove $name as a follower?", "They stop hearing your switches and seeing what you share with followers.", "Remove",
        danger = true, onDismiss = { removing = false }, onConfirm = {
            removing = false
            act("Couldn’t remove them.") { chorus.create("follow.end", f.id, JSONObject()); onRemoved() }
        })
}

/** "What … see right now" (F7.9): the follower's own view of us, as the server holds it. */
@Composable
private fun SeenCard(name: String, seen: garden.vayne.chorus.data.FollowerView?, now: Long) {
    val p = LocalChorusPalette.current
    Column(Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(16.dp)).background(p.accentSoft).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Glyph(R.drawable.ic_eye, p.accent, 18.dp)
            Text("What $name see right now", color = p.accent, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold)
        }
        val shown = seen?.entries.orEmpty()
        when {
            seen == null -> Text("Loading…", color = p.ink3, fontSize = 14.sp, lineHeight = 19.sp)
            !seen.shared -> Text("Nothing: you don’t share who’s here with them.", color = p.ink, fontSize = 14.sp, lineHeight = 19.sp)
            shown.isEmpty() -> Text("Nobody yet. They see your next switch as you share it.", color = p.ink, fontSize = 14.sp, lineHeight = 19.sp)
            else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FaceStack(shown.map { it.subject() }, max = 3, size = 32.dp)
                Text(Friends.nowLine(shown), color = p.ink, fontSize = 15.sp, lineHeight = 20.sp)
            }
        }
        if (seen != null && shown.isNotEmpty()) {
            val told = seen.revealedAt?.let { "told them ${compactAgo(it)} ago" }
            Text(listOfNotNull(Friends.sinceLine(seen, now), told).joinToString(" · "), color = p.ink3, fontSize = 12.sp)
        }
    }
}

/**
 * Ceiling details as rows (F7.10): each shows its value and opens a drawer to change it, or to go
 * back to [inherited]. Switch-outs is a switch. [keys] limits the rows (null: the main ones, then
 * More details); extra fronts and layers (D-084) come last.
 */
@Composable
internal fun CeilingDetails(model: Model, ceiling: JSONObject, inherited: String, inheritedCeiling: JSONObject?, enabled: Boolean,
    keys: List<String>?, onChange: (JSONObject) -> Unit) {
    var open by remember { mutableStateOf<String?>(null) }
    var more by remember { mutableStateOf(false) }
    var grants by remember { mutableStateOf(false) }
    val fields = BucketControls.fields.associateBy { it.key }
    fun valueOf(key: String): Pair<String, Boolean> {
        val f = fields.getValue(key)
        if (ceiling.has(key)) return Friends.fieldValue(key, ceiling.opt(key)) to true
        val from = inheritedCeiling?.takeIf { it.has(key) }?.opt(key) ?: f.default
        return Friends.fieldValue(key, from) to false
    }
    for (key in keys ?: MAIN_DETAILS) {
        val f = fields[key] ?: continue
        val (value, custom) = valueOf(key)
        if (f.kind == "bool") {
            val on = if (ceiling.has(key)) ceiling.optBoolean(key) else (inheritedCeiling?.takeIf { it.has(key) }?.optBoolean(key) ?: (f.default == true))
            ToggleRow(if (key == "announce_leaving") "Switch-outs" else f.label, on,
                if (key == "announce_leaving") "Tell them when someone leaves" else f.hint, enabled) {
                onChange(BucketControls.withField(ceiling, key, it, false))
            }
        } else DetailRow(when (key) { "levels" -> "Layers"; "delay" -> "Delay before telling"; "stacking" -> "Several switches close together"; else -> f.label },
            // F7.10: an inherited detail says what it inherits ("23:00–08:00 · from Everyone else")
            if (custom) DETAIL_NOTES[key] else "$value · from $inherited",
            if (custom) value else "Inherit", enabled) { open = key }
    }
    if (keys == null) {
        val grantNames = grantSummary(model, ceiling)
        DetailRow("Extra fronts", grantNames, null, enabled) { grants = true }
        DetailRow("More details", "Daily limit, digest, who’s hidden, “notify now”", null, enabled) { more = true }
    }
    open?.let { key ->
        val f = fields.getValue(key)
        Drawer({ open = null }, f.label, f.hint) {
            CeilingFieldRow(f, ceiling, inherited.replaceFirstChar { it.uppercase() }, enabled, titled = false) { value, inherit ->
                onChange(BucketControls.withField(ceiling, key, value, inherit))
            }
        }
    }
    if (more) Drawer({ more = false }, "More details") {
        CeilingControls(ceiling, inherited.replaceFirstChar { it.uppercase() }, enabled, omit = MAIN_DETAILS.toSet() + SHARING_KEYS, onChange = onChange)
    }
    if (grants) GrantsDrawer(model, ceiling, enabled, { grants = false }, onChange)
}

/** A detail row: title and a line under it, the value in the accent (or a chevron). */
@Composable
private fun DetailRow(title: String, subtitle: String?, value: String?, enabled: Boolean, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) p.ink else p.ink3, fontSize = 15.sp, lineHeight = 20.sp)
            subtitle?.let { Text(it, color = p.ink3, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        if (value != null) Text(value, color = p.accent, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        else Glyph(R.drawable.ic_chevron_right, p.ink3, 18.dp)
    }
}

private fun grantSummary(model: Model, ceiling: JSONObject): String {
    val named = model.namedFronts ?: return "Body only"
    val fronts = FollowPresets.granted(ceiling, "fronts").mapNotNull { id -> named.fronts[id]?.takeIf { !it.deleted }?.name }
    val layers = FollowPresets.granted(ceiling, "layers").mapNotNull { id -> named.layers.find { it.id == id && !it.deleted }?.name }
    return (listOf("Body") + fronts + layers).joinToString(", ").let { if (fronts.isEmpty() && layers.isEmpty()) "Body only" else it }
}

/** Additional fronts and custom layers (D-084): hidden until granted here. */
@Composable
private fun GrantsDrawer(model: Model, ceiling: JSONObject, enabled: Boolean, onDismiss: () -> Unit, onChange: (JSONObject) -> Unit) {
    val p = LocalChorusPalette.current
    val named = model.namedFronts
    val fronts = named?.fronts?.values?.filter { !it.deleted && it.id != named.bodyId }?.sortedBy { it.name.lowercase() }.orEmpty()
    val layers = named?.layers?.filter { !it.deleted && it.id !in setOf("front", "cocon", "present") }.orEmpty()
    Drawer(onDismiss, "Extra fronts") {
        if (fronts.isEmpty() && layers.isEmpty()) Text("You have no other fronts or custom layers.", color = p.ink3, fontSize = 14.sp, lineHeight = 19.sp)
        for ((key, rows) in listOf("fronts" to fronts.map { it.id to it.name }, "layers" to layers.map { it.id to it.name })) {
            val chosen = FollowPresets.granted(ceiling, key)
            for ((id, name) in rows) ToggleRow(name, id in chosen, if (key == "fronts") "Front" else "Layer", enabled) {
                onChange(FollowPresets.withGrant(ceiling, key, id, it))
            }
        }
    }
}

/**
 * A bucket (F7.10), or a new one ([bucketId] null): its look, its preset and every detail as a
 * row, who's in it, and deleting it. Changes save as they're made; the name saves on leaving.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BucketPage(chorus: Chorus, model: Model, bucketId: String?, followers: List<FollowInfo>, onBack: () -> Unit) {
    val p = LocalChorusPalette.current
    val scope = rememberCoroutineScope()
    val id = rememberSaveable(bucketId) { bucketId ?: chorus.newId() }
    val bucket = model.privacyBuckets.find { it.id == id }
    var name by rememberSaveable(id) { mutableStateOf(bucket?.name.orEmpty()) }
    var icon by rememberSaveable(id) { mutableStateOf(bucket?.icon ?: BucketControls.DEFAULT_ICON) }
    var color by rememberSaveable(id) { mutableStateOf(bucket?.color) }
    var preset by rememberSaveable(id) { mutableStateOf("gentle") }
    var look by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun create(kind: String, payload: JSONObject) {
        scope.launch {
            try { chorus.create(kind, id, payload); error = null }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Couldn’t save this bucket." }
        }
    }
    fun leave() {
        if (bucket != null && name.isNotBlank() && name.trim() != bucket.name) create("bucket.set", JSONObject().put("name", name.trim()))
        onBack()
    }
    BackHandler { leave() }
    val inside = model.bucketAssignments[id].orEmpty()
    Column(Modifier.fillMaxSize().background(p.bg)) {
        SubpageBar(bucket?.name ?: "New bucket", if (bucket == null) "Bucket" else "Bucket · ${inside.size} ${if (inside.size == 1) "friend" else "friends"}", ::leave) {
            if (bucket != null) BarIcon(R.drawable.ic_check, "Done", p.accent, onClick = ::leave)
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).clickable(onClickLabel = "Change the icon") { look = true }) {
                    BucketTile(bucket?.icon ?: icon, bucket?.color ?: color, 56.dp)
                }
                ChorusField(name, { name = it.take(60) }, Modifier.weight(1f), singleLine = true, label = { Text("NAME") })
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val current = bucket?.color ?: color
                for (swatch in BucketControls.swatches.take(7)) {
                    val on = current == swatch
                    Box(Modifier.size(30.dp).clip(CircleShape).background(tonesOf(swatch).ring).border(2.dp, if (on) p.ink else p.bg, CircleShape)
                        .clickable(role = Role.RadioButton, onClickLabel = "Colour $swatch") {
                            color = swatch; if (bucket != null) create("bucket.set", JSONObject().put("color", swatch))
                        }.semantics { contentDescription = "Colour $swatch${if (on) ", chosen" else ""}" })
                }
            }
            error?.let { Text(it, color = p.danger, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp)) }
            if (bucket == null) {
                if (!model.isPerson) {
                    SectionLabel("Preset", Modifier.padding(horizontal = 12.dp))
                    Segmented(Friends.PRESETS.map(Friends::presetName), Friends.PRESETS.indexOf(preset), Modifier.padding(horizontal = 16.dp), accent = true) { preset = Friends.PRESETS[it] }
                    Text(Friends.presetAbout(preset), color = p.ink2, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                }
                DrawerButton("Create bucket", {
                    create("bucket.set", JSONObject().put("name", name.trim()).put("icon", icon).put("color", color ?: JSONObject.NULL)
                        .put("ceiling", if (model.isPerson) JSONObject() else FollowPresets.ceiling(preset)))
                }, enabled = name.isNotBlank(), modifier = Modifier.padding(16.dp))
                return@Column
            }
            if (!model.isPerson) {
                val choice = FollowPresets.choiceOf(bucket.ceiling)
                SectionLabel("Preset", Modifier.padding(horizontal = 12.dp)) {
                    if (choice !in Friends.PRESETS) Text(if (choice == "inherit") "Everyone else’s" else "Custom", color = p.ink3, fontSize = 12.sp)
                }
                Segmented(Friends.PRESETS.map(Friends::presetName), Friends.PRESETS.indexOf(choice), Modifier.padding(horizontal = 16.dp), accent = true) {
                    create("bucket.set", JSONObject().put("ceiling", FollowPresets.ceiling(Friends.PRESETS[it], bucket.ceiling)))
                }
                SectionLabel("Details", Modifier.padding(horizontal = 12.dp)) { Text("inherits from Everyone else", color = p.ink3, fontSize = 12.sp) }
                CeilingDetails(model, bucket.ceiling, "Everyone else", model.accountPrefs.followCeiling, true, null) {
                    create("bucket.set", JSONObject().put("ceiling", it))
                }
            }
            SectionLabel("Who’s in it", Modifier.padding(horizontal = 12.dp)) { Text("${inside.size}", color = p.ink3, fontSize = 12.sp) }
            if (followers.isEmpty()) Text("No followers yet.", color = p.ink3, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.padding(horizontal = 16.dp))
            for (f in followers) {
                val on = f.account.id in inside
                Row(Modifier.fillMaxWidth().clickable(role = Role.Checkbox) {
                    create(if (on) "bucket.unassign" else "bucket.assign", JSONObject().put("follower_account_id", f.account.id))
                }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    AccountAvatar(f.account, 36.dp)
                    Text(f.account.shownName, color = p.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Box(Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(if (on) p.accent else androidx.compose.ui.graphics.Color.Transparent)
                        .layerOutline(if (on) p.accent else p.ink3, 7.dp, false, 2.dp), contentAlignment = Alignment.Center) {
                        if (on) Glyph(R.drawable.ic_check, p.onAccent, 16.dp)
                    }
                }
            }
            Text("Delete bucket", color = p.danger, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 20.dp).clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button) { deleting = true }.padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }
    if (look) Drawer({ look = false }, "Icon and colour", null) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in BucketControls.icons) {
                val on = (bucket?.icon ?: icon) == option
                Box(Modifier.size(44.dp).clip(CircleShape).background(if (on) p.accentSoft else p.surface2)
                    .border(1.dp, if (on) p.accent else p.surface2, CircleShape)
                    .clickable(role = Role.RadioButton, onClickLabel = option) { icon = option; if (bucket != null) create("bucket.set", JSONObject().put("icon", option)) }
                    .semantics { contentDescription = "$option icon${if (on) ", chosen" else ""}" }, contentAlignment = Alignment.Center) {
                    Icon(painterResource(bucketIconRes(option)), null, Modifier.size(22.dp), tint = if (on) p.accent else p.ink2)
                }
            }
        }
        var emoji by rememberSaveable { mutableStateOf((bucket?.icon ?: icon).takeUnless { BucketControls.isBuiltIn(it) }.orEmpty()) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChorusField(emoji, { emoji = it.take(BucketControls.CUSTOM_ICON_MAX) }, Modifier.weight(1f), singleLine = true, label = { Text("Or an emoji") },
                isError = emoji.isNotBlank() && !BucketControls.isCustomIcon(emoji.trim()))
            if (BucketControls.isCustomIcon(emoji.trim())) SmallButton("Use it") {
                icon = emoji.trim(); if (bucket != null) create("bucket.set", JSONObject().put("icon", emoji.trim()))
            }
        }
        ColourField(color ?: bucket?.color ?: "#42A397", { c ->
            BucketControls.color(c)?.let { color = it; if (bucket != null) create("bucket.set", JSONObject().put("color", it)) }
        }, label = "Another colour")
        DrawerButton("No colour", { color = null; if (bucket != null) create("bucket.set", JSONObject().put("color", JSONObject.NULL)) }, ButtonKind.Neutral)
    }
    if (deleting && bucket != null) ConfirmDrawer("Delete “${bucket.name}”?", "Anything shared only with it goes back to just you. The friends in it stay friends.",
        "Delete", danger = true, onDismiss = { deleting = false }, onConfirm = {
            deleting = false; create("bucket.delete", JSONObject()); onBack()
        })
}

/** Everyone else (the account default, NOTIFICATIONS §3) and what every follower may also see. */
@Composable
internal fun AdvancedSharingPage(chorus: Chorus, model: Model, onBack: () -> Unit) {
    val p = LocalChorusPalette.current
    val scope = rememberCoroutineScope()
    val ceiling = model.accountPrefs.followCeiling
    var error by remember { mutableStateOf<String?>(null) }
    fun save(next: JSONObject) {
        scope.launch {
            try { chorus.create("pref.set", null, AccountPrefs.payload("follow_ceiling", next)); error = null }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Couldn’t save that." }
        }
    }
    Column(Modifier.fillMaxSize().background(p.bg)) {
        SubpageBar("Advanced sharing", "Everyone else: friends in no bucket", onBack)
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            error?.let { Text(it, color = p.danger, fontSize = 13.sp, modifier = Modifier.padding(16.dp)) }
            SectionLabel("How much everyone else hears", Modifier.padding(horizontal = 12.dp))
            val choice = Friends.everyoneElse(ceiling)
            Segmented(Friends.PRESETS.map(Friends::presetName), Friends.PRESETS.indexOf(choice), Modifier.padding(horizontal = 16.dp), accent = true) {
                save(FollowPresets.ceiling(Friends.PRESETS[it], ceiling))
            }
            Text(Friends.presetAbout(choice),
                color = p.ink2, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            SectionLabel("Also share with every follower", Modifier.padding(horizontal = 12.dp))
            ToggleRow("Who’s here now", ceiling.optBoolean("share_current_front", true), "On their Friends tab and our profile") {
                save(BucketControls.withField(ceiling, "share_current_front", it, false))
            }
            ToggleRow("Front history", ceiling.optBoolean("share_history", false), "Last 30 days, only what they were told, times as shared") {
                save(FollowPresets.withSharing(ceiling, "share_history", it))
            }
            ToggleRow("Fronting stats", ceiling.optBoolean("share_stats", false), "Each member’s share, whole days only") {
                save(FollowPresets.withSharing(ceiling, "share_stats", it))
            }
            SectionLabel("Details", Modifier.padding(horizontal = 12.dp)) { Text("buckets inherit these", color = p.ink3, fontSize = 12.sp) }
            CeilingDetails(model, ceiling, "Chorus’s default", null, true, null) { save(it) }
        }
    }
}
