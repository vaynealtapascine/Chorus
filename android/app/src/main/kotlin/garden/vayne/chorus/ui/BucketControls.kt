package garden.vayne.chorus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.BucketControls
import garden.vayne.chorus.data.CeilingField
import garden.vayne.chorus.data.FollowInfo
import garden.vayne.chorus.data.FollowPresets
import garden.vayne.chorus.data.Member
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.PrivacyBucket
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R
import org.json.JSONArray
import org.json.JSONObject

/** A bucket's icon (D-115), from the shared set; an unknown one shows the default. */
internal fun bucketIconRes(icon: String?): Int = when (BucketControls.icon(icon)) {
    "members" -> AppIcon.Members.resource
    "heart" -> R.drawable.ic_heart
    "lock" -> AppIcon.Lock.resource
    "globe" -> R.drawable.ic_globe
    "chat" -> AppIcon.Chat.resource
    "journal" -> AppIcon.Journal.resource
    "front" -> AppIcon.Front.resource
    "layers" -> AppIcon.Layers.resource
    "bell" -> R.drawable.ic_bell
    "bookmark" -> R.drawable.ic_bookmark
    "folder" -> R.drawable.ic_folder
    else -> AppIcon.Friends.resource
}

/**
 * A bucket's icon (D-115): a built-in icon or its own glyph (an emoji), in its colour when it has
 * one, toned for light and dark like a member's. [disc] sets it on a soft circle of that colour.
 */
@Composable
internal fun BucketGlyph(icon: String?, color: String?, size: Dp = 16.dp, disc: Boolean = false) {
    val p = LocalChorusPalette.current
    val shown = BucketControls.icon(icon)
    val tones = BucketControls.color(color)?.let { tonesOf(it) }
    val ink = tones?.name ?: if (disc) p.accent else p.ink2
    val inner = if (disc) size * 0.55f else size
    Box(Modifier.size(size).then(if (disc) Modifier.background(tones?.tint ?: p.accentSoft, CircleShape) else Modifier),
        contentAlignment = Alignment.Center) {
        if (BucketControls.isBuiltIn(shown)) Icon(painterResource(bucketIconRes(shown)), null, Modifier.size(inner), tint = ink)
        else Text(shown, fontSize = (inner.value * 0.85f).sp, lineHeight = (inner.value).sp, maxLines = 1)
    }
}

/** A bucket as it reads everywhere it can be chosen: its icon in its colour, then its name. */
@Composable
internal fun BucketLabel(name: String, icon: String?, color: String? = null, modifier: Modifier = Modifier) {
    val p = LocalChorusPalette.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BucketGlyph(icon, color)
        Text(name, color = p.ink, maxLines = 1)
    }
}

/**
 * Advanced notification controls (UI11c, D-115): every ceiling detail, inherited until
 * customized, for the account default, a bucket or a follow override alike. [inherited] says
 * what an uncustomized detail follows; [omit] skips details the screen has its own control for.
 */
@Composable
internal fun CeilingControls(ceiling: JSONObject, inherited: String, enabled: Boolean, omit: Set<String> = emptySet(),
    onChange: (JSONObject) -> Unit) {
    val p = LocalChorusPalette.current
    var open by rememberSaveable { mutableStateOf(false) }
    val fields = BucketControls.fields.filter { it.key !in omit }
    val customized = fields.count { ceiling.has(it.key) }
    TextButton(onClick = { open = !open }, contentPadding = PaddingValues(horizontal = 0.dp)) {
        Text((if (open) "Hide advanced notification controls" else "Advanced notification controls") +
            if (customized > 0) " · $customized customized" else "")
    }
    if (open) Column {
        Text("Delay hides when you switched as it happens; the time shown hides the exact time afterwards. " +
            "A follower in several buckets gets the most open setting for each detail; their own setting wins.",
            color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp)
        for (f in fields) key(f.key) {
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = p.line)
            CeilingFieldRow(f, ceiling, inherited, enabled) { value, inherit -> onChange(BucketControls.withField(ceiling, f.key, value, inherit)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CeilingFieldRow(f: CeilingField, ceiling: JSONObject, inherited: String, enabled: Boolean, save: (Any?, Boolean) -> Unit) {
    val p = LocalChorusPalette.current
    val custom = ceiling.has(f.key)
    val value: Any? = (if (custom) ceiling.opt(f.key) else f.default).takeIf { it != JSONObject.NULL }
    fun put(v: Any?) { if (BucketControls.valid(f.key, v)) save(v, false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(f.label, color = p.ink, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            TextButton(enabled = enabled, onClick = { if (custom) save(null, true) else save(f.default, false) }) {
                Text(if (custom) "Use ${inherited.lowercase()}" else "Customize", fontSize = 13.sp)
            }
        }
        f.hint?.let { Text(it, color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp) }
        if (!custom) { Text(inherited, color = p.ink3, fontSize = 13.sp); return@Column }
        when (f.kind) {
            "bool" -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Switch(value == true, { put(it) }, enabled = enabled, modifier = Modifier.semantics { contentDescription = f.label })
                Text(if (value == true) "On" else "Off", color = p.ink2)
            }
            "enum" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in f.choices) FilterChip(value == c, { put(c) }, label = { Text(BucketControls.choiceLabel(c)) }, enabled = enabled)
            }
            "levels" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val on = (value as? JSONArray)?.let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? String } }.orEmpty()
                for (c in f.choices) FilterChip(c in on, { put(JSONArray(f.choices.filter { x -> if (x == c) c !in on else x in on })) },
                    label = { Text(BucketControls.choiceLabel(c)) }, enabled = enabled)
            }
            "range" -> {
                val r = value as? JSONObject ?: JSONObject()
                MinutesRange(r.optLong("min_s"), r.optLong("max_s"), enabled) { min, max -> put(BucketControls.range(min, max)) }
            }
            "time" -> {
                val t = value as? JSONObject ?: JSONObject()
                val mode = t.optString("mode")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (c in f.choices) FilterChip(mode == c, {
                        put(when (c) { "round" -> JSONObject().put("mode", c).put("round_min", 15); "jitter" -> JSONObject().put("mode", c).put("jitter_min", 15)
                            else -> JSONObject().put("mode", c) })
                    }, label = { Text(BucketControls.choiceLabel(c)) }, enabled = enabled)
                }
                if (mode == "round" || mode == "jitter") {
                    val k = if (mode == "round") "round_min" else "jitter_min"
                    WholeNumber(if (mode == "round") "To the nearest (min)" else "By up to (min)", t.optLong(k, 15), 1, BucketControls.MINUTES_MAX, enabled) {
                        put(JSONObject().put("mode", mode).put(k, it))
                    }
                }
            }
            "limit" -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Switch(value != null, { put(if (it) 10 else null) }, enabled = enabled, modifier = Modifier.semantics { contentDescription = "Limit pings a day" })
                if (value != null) WholeNumber("A day", (value as Number).toLong(), 0, BucketControls.PER_DAY_MAX, enabled) { put(it.toInt()) }
                else Text("No limit", color = p.ink2)
            }
            "quiet" -> {
                val q = value as? JSONObject
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Switch(q != null, { put(if (it) JSONObject().put("from", "23:00").put("to", "08:00") else null) }, enabled = enabled,
                        modifier = Modifier.semantics { contentDescription = "Quiet hours" })
                    Text(if (q == null) "None" else "${q.optString("from")}–${q.optString("to")}", color = p.ink2)
                }
                if (q != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ClockField("From", q.optString("from"), enabled, Modifier.weight(1f)) { put(JSONObject(q.toString()).put("from", it)) }
                    ClockField("Until", q.optString("to"), enabled, Modifier.weight(1f)) { put(JSONObject(q.toString()).put("to", it)) }
                }
            }
        }
    }
}

/** A delay range entered in minutes, stored in seconds; saved when both ends make sense. */
@Composable
private fun MinutesRange(minS: Long, maxS: Long, enabled: Boolean, save: (Long, Long) -> Unit) {
    fun text(s: Long) = if (s % 60 == 0L) (s / 60).toString() else "%.1f".format(s / 60.0)
    var from by remember(minS) { mutableStateOf(text(minS)) }
    var to by remember(maxS) { mutableStateOf(text(maxS)) }
    val a = from.trim().toDoubleOrNull()?.let { Math.round(it * 60) }
    val b = to.trim().toDoubleOrNull()?.let { Math.round(it * 60) }
    val ok = a != null && b != null && a in 0..BucketControls.RANGE_MAX_S && b in a..BucketControls.RANGE_MAX_S
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ChorusField(from, { from = it }, Modifier.weight(1f), enabled = enabled, singleLine = true, label = { Text("From (min)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = !ok)
        ChorusField(to, { to = it }, Modifier.weight(1f), enabled = enabled, singleLine = true, label = { Text("To (min)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = !ok)
        TextButton(enabled = enabled && ok && (a != minS || b != maxS), onClick = { save(a!!, b!!) }) { Text("Save") }
    }
}

@Composable
private fun WholeNumber(label: String, current: Long, min: Long, max: Long, enabled: Boolean, save: (Long) -> Unit) {
    var text by remember(current) { mutableStateOf(current.toString()) }
    val n = text.trim().toLongOrNull()?.takeIf { it in min..max }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ChorusField(text, { text = it }, Modifier.width(140.dp), enabled = enabled, singleLine = true, label = { Text(label) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = n == null)
        TextButton(enabled = enabled && n != null && n != current, onClick = { save(n!!) }) { Text("Save") }
    }
}

@Composable
private fun ClockField(label: String, current: String, enabled: Boolean, modifier: Modifier, save: (String) -> Unit) {
    var text by remember(current) { mutableStateOf(current) }
    val ok = Regex("([01][0-9]|2[0-3]):[0-5][0-9]").matches(text)
    ChorusField(text, { text = it.take(5); if (Regex("([01][0-9]|2[0-3]):[0-5][0-9]").matches(text) && text != current) save(text) },
        modifier, enabled = enabled, singleLine = true, label = { Text("$label (HH:MM)") }, isError = !ok,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}

/** The Sharing buckets section of People: one row per bucket, then a way to make one. */
internal fun LazyListScope.bucketItems(model: Model, onOpen: (String?) -> Unit) {
    item(key = "buckets:title") {
        val p = LocalChorusPalette.current
        Column(Modifier.padding(top = 12.dp)) {
            Text("Sharing buckets", color = p.ink, fontWeight = FontWeight.SemiBold)
            Text(if (model.isPerson) "Group followers to choose who sees which posts."
                else "Group followers to choose what each group sees and hears. A follower in several buckets gets the most open setting for each detail.",
                color = p.ink2)
        }
    }
    for (b in model.privacyBuckets) item(key = "bucket:${b.id}") {
        val p = LocalChorusPalette.current
        val n = model.bucketAssignments[b.id].orEmpty().size
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.surface).clickable(onClickLabel = "Edit ${b.name}") { onOpen(b.id) }
            .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BucketGlyph(b.icon, b.color, 36.dp, disc = true)
            Column(Modifier.weight(1f)) {
                Text(b.name, color = p.ink, maxLines = 1)
                Text(listOfNotNull("$n ${if (n == 1) "follower" else "followers"}",
                    if (model.isPerson) null else presetLabel(FollowPresets.choiceOf(b.ceiling))).joinToString(" · "), color = p.ink3, fontSize = 12.sp)
            }
            ChorusIcon(AppIcon.Chevron, null, Modifier.size(16.dp))
        }
    }
    item(key = "buckets:new") {
        val p = LocalChorusPalette.current
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onOpen(null) }.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ChorusIcon(AppIcon.Plus, null, Modifier.size(20.dp))
            Text("New bucket", color = p.accent)
        }
    }
}

private fun presetLabel(choice: String) = when (choice) {
    "inherit" -> "Account default"; "close" -> "Close"; "gentle" -> "Gentle"; "private" -> "Private"
    "digest" -> "Digest"; "off" -> "No switch alerts"; else -> "Custom"
}

/**
 * One bucket, or a new one ([bucketId] null): its name, icon, notification preset and Advanced
 * controls (systems), who is in it, and deleting it. Every change saves as it's made.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BucketEditor(model: Model, bucketId: String?, followers: List<FollowInfo>, enabled: Boolean, newId: () -> String,
    onDismiss: () -> Unit, create: (String, String, JSONObject) -> Unit) {
    val p = LocalChorusPalette.current
    val id = rememberSaveable(bucketId) { bucketId ?: newId() }
    val bucket: PrivacyBucket? = model.privacyBuckets.find { it.id == id }
    var name by rememberSaveable(id) { mutableStateOf(bucket?.name.orEmpty()) }
    var icon by rememberSaveable(id) { mutableStateOf(bucket?.icon ?: BucketControls.DEFAULT_ICON) }
    var color by rememberSaveable(id) { mutableStateOf(bucket?.color) }
    var emoji by rememberSaveable(id) { mutableStateOf((bucket?.icon ?: "").takeUnless { BucketControls.isBuiltIn(it) }.orEmpty()) }
    fun setIcon(option: String) {
        icon = option
        if (bucket != null && bucket.icon != option) create("bucket.set", id, JSONObject().put("icon", option))
    }
    fun setColor(option: String?) {
        color = option
        if (bucket != null && bucket.color != option) create("bucket.set", id, JSONObject().put("color", option ?: JSONObject.NULL))
    }
    var preset by rememberSaveable(id) { mutableStateOf("inherit") }
    var confirmDelete by remember { mutableStateOf(false) }
    Drawer(onDismiss, if (bucket == null) "New bucket" else "Edit bucket", "A group of followers who see the same things") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ChorusField(name, { name = it.take(60) }, Modifier.fillMaxWidth(), enabled = enabled, singleLine = true, label = { Text("Name") })
            if (bucket != null && name.isNotBlank() && name.trim() != bucket.name)
                TextButton(enabled = enabled, onClick = { create("bucket.set", id, JSONObject().put("name", name.trim())) }) { Text("Save name") }
            Text("Icon", color = p.ink2, fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (option in BucketControls.icons) {
                    val on = (bucket?.icon ?: icon) == option
                    Box(Modifier.size(40.dp).clip(CircleShape).background(if (on) p.accentSoft else p.surface2)
                        .border(1.dp, if (on) p.accent else p.surface2, CircleShape)
                        .clickable(enabled = enabled, role = Role.RadioButton, onClickLabel = option) { setIcon(option) }
                        .semantics { contentDescription = "$option icon${if (on) ", chosen" else ""}" },
                        contentAlignment = Alignment.Center) {
                        Icon(painterResource(bucketIconRes(option)), null, Modifier.size(20.dp), tint = if (on) p.accent else p.ink2)
                    }
                }
            }
            // its own icon: any emoji or symbol
            val ownIcon = !BucketControls.isBuiltIn(bucket?.icon ?: icon)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChorusField(emoji, { emoji = it.take(BucketControls.CUSTOM_ICON_MAX) }, Modifier.width(150.dp), enabled = enabled,
                    singleLine = true, label = { Text("Or an emoji") }, isError = emoji.isNotBlank() && !BucketControls.isCustomIcon(emoji.trim()))
                if (BucketControls.isCustomIcon(emoji.trim()) && emoji.trim() != (bucket?.icon ?: icon))
                    TextButton(enabled = enabled, onClick = { setIcon(emoji.trim()) }) { Text("Use it") }
                else if (ownIcon) Text("In use", color = p.accent, fontSize = 13.sp)
            }
            Text("Colour", color = p.ink2, fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val current = bucket?.color ?: color
                Box(Modifier.size(36.dp).clip(CircleShape).border(2.dp, if (current == null) p.ink else p.line, CircleShape)
                    .clickable(enabled = enabled, role = Role.RadioButton, onClickLabel = "No colour") { setColor(null) }
                    .semantics { contentDescription = "No colour${if (current == null) ", chosen" else ""}" }, contentAlignment = Alignment.Center) {
                    BucketGlyph(bucket?.icon ?: icon, null, 18.dp)
                }
                for (swatch in BucketControls.swatches) {
                    val on = current == swatch
                    Box(Modifier.size(36.dp).clip(CircleShape).background(tonesOf(swatch).ring).border(2.dp, if (on) p.ink else p.bg, CircleShape)
                        .clickable(enabled = enabled, role = Role.RadioButton, onClickLabel = "Colour $swatch") { setColor(swatch) }
                        .semantics { contentDescription = "Colour $swatch${if (on) ", chosen" else ""}" })
                }
            }
            ColourField(color ?: bucket?.color ?: "#42A397", { setColor(BucketControls.color(it)) }, label = "Another colour", enabled = enabled)
            if (bucket == null) {
                if (!model.isPerson) FollowPresetPicker("Sees switches", preset) { preset = it }
                Button(enabled = enabled && name.isNotBlank(), onClick = {
                    create("bucket.set", id, JSONObject().put("name", name.trim()).put("icon", icon).put("color", color ?: JSONObject.NULL)
                        .put("ceiling", if (preset == "inherit") JSONObject() else FollowPresets.ceiling(preset)))
                }) { Text("Create bucket") }
                return@Column
            }
            if (!model.isPerson) {
                FollowPresetPicker("Sees switches", FollowPresets.choiceOf(bucket.ceiling)) { choice ->
                    create("bucket.set", id, JSONObject().put("ceiling", FollowPresets.ceiling(choice, bucket.ceiling)))
                }
                CeilingControls(bucket.ceiling, "Account default", enabled) { create("bucket.set", id, JSONObject().put("ceiling", it)) }
            }
            HorizontalDivider(color = p.line)
            Text("Who's in it", color = p.ink, fontWeight = FontWeight.Medium)
            val inside = model.bucketAssignments[id].orEmpty()
            if (followers.isEmpty()) Text("No followers yet.", color = p.ink3)
            for (f in followers) Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Checkbox) {
                create(if (f.account.id in inside) "bucket.unassign" else "bucket.assign", id, JSONObject().put("follower_account_id", f.account.id))
            }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(f.account.id in inside, null, enabled = enabled)
                Text(f.account.shownName, color = p.ink, modifier = Modifier.padding(start = 8.dp))
            }
            HorizontalDivider(color = p.line)
            if (confirmDelete) {
                Text("Delete “${bucket.name}”? Anything shared only with it goes back to just you.", color = p.ink2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = enabled, onClick = { create("bucket.delete", id, JSONObject()); onDismiss() },
                        colors = ButtonDefaults.buttonColors(containerColor = p.danger)) { Text("Delete") }
                    TextButton(onClick = { confirmDelete = false }) { Text("Keep") }
                }
            } else TextButton(enabled = enabled, onClick = { confirmDelete = true }) { Text("Delete bucket", color = p.danger) }
        }
    }
}

/** A follower's buckets, on their card: tap one to put them in or take them out. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FollowerBuckets(model: Model, follower: String, enabled: Boolean, toggle: (String, Boolean) -> Unit) {
    if (model.privacyBuckets.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (b in model.privacyBuckets) {
            val on = follower in model.bucketAssignments[b.id].orEmpty()
            FilterChip(on, { toggle(b.id, !on) }, enabled = enabled, label = { Text(b.name, maxLines = 1) },
                leadingIcon = { BucketGlyph(b.icon, b.color) })
        }
    }
}

/**
 * Who hears when [member] fronts (`member.notify_policy.announce`, NOTIFICATIONS §2.4): every
 * follower, only some buckets, or nobody. Other policy keys are kept as they are.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MemberAnnounce(model: Model, member: Member, enabled: Boolean, save: (JSONObject) -> Unit) {
    val p = LocalChorusPalette.current
    val policy = member.notifyPolicy?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
    val announce = policy.opt("announce")
    val mode = when { announce == "nobody" -> "nobody"; announce is JSONObject -> "buckets"; else -> "everyone" }
    val chosen = (announce as? JSONObject)?.optJSONArray("buckets")?.let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? String } }.orEmpty()
    fun put(value: Any) = save(JSONObject(policy.toString()).put("announce", value))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Who hears when ${member.shownName} fronts", color = p.ink2, fontSize = 13.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(mode == "everyone", { put("everyone") }, enabled = enabled, label = { Text("Every follower") })
            if (model.privacyBuckets.isNotEmpty()) FilterChip(mode == "buckets", { put(JSONObject().put("buckets", JSONArray(chosen))) },
                enabled = enabled, label = { Text("Some buckets") })
            FilterChip(mode == "nobody", { put("nobody") }, enabled = enabled, label = { Text("Nobody") })
        }
        if (mode == "buckets") FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (b in model.privacyBuckets) {
                val on = b.id in chosen
                FilterChip(on, { put(JSONObject().put("buckets", JSONArray(if (on) chosen - b.id else chosen + b.id))) }, enabled = enabled,
                    label = { Text(b.name, maxLines = 1) }, leadingIcon = { BucketGlyph(b.icon, b.color) })
            }
            if (chosen.none { id -> model.privacyBuckets.any { it.id == id } }) Text("No bucket chosen: nobody hears.", color = p.ink3, fontSize = 12.sp)
        }
    }
}

