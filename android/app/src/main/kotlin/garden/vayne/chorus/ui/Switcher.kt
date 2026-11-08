package garden.vayne.chorus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.Entry
import garden.vayne.chorus.data.Front
import garden.vayne.chorus.data.FrontScene
import garden.vayne.chorus.data.FrontingDisplay
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.Placement
import garden.vayne.chorus.data.SceneLayer
import garden.vayne.chorus.data.Subject
import garden.vayne.chorus.data.Subsystems
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R
import garden.vayne.chorus.widget.WidgetPins
import java.text.DateFormat
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/** The sheet's header: a title, a quiet line under it and a close button (F3.2, F3.3). */
@Composable
private fun SheetHeader(title: String, subtitle: String?, onClose: () -> Unit, lead: (@Composable () -> Unit)? = null,
    actions: @Composable () -> Unit = {}) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        lead?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, color = p.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp)
            subtitle?.let { Text(it, color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp) }
        }
        actions()
        BarIcon(R.drawable.ic_close, "Close", p.ink2, onClick = onClose)
    }
}

/**
 * Switch (Figma F3.2). Each tap places someone on the innermost layer, the next tap moves them
 * one layer outward, and past the last layer they are out again. Replace front starts empty and
 * becomes the whole front; Add to front starts from who is here and changes only who was tapped.
 * `addTo` opens in Add mode with the first tap landing on that layer (a layer's + on Fronting).
 * A fresh sheet snapshots the front so live deltas do not erase edits.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Switcher(chorus: Chorus, model: Model, frontId: String? = null, addTo: String? = null, handover: Boolean = false, onClose: () -> Unit) {
    val p = LocalChorusPalette.current
    val ctx = LocalContext.current
    val actions = rememberCoroutineScope()
    val named = model.namedFronts
    val accountId = chorus.device?.accountId.orEmpty()
    val target = frontId ?: named?.bodyId
    val frontLabel = frontName(target?.let { named?.fronts?.get(it) })
    val scene = FrontScene.build(model, target ?: accountId, if (named != null && target != null) named.entries(target) else model.current, accountId)
    // the cycle: nested layers inner to outer, then the separately listed ones
    val cycle = scene.all.filter { !it.separate } + scene.all.filter { it.separate }
    val current = remember { if (named != null && target != null) named.entries(target) else model.current }
    var add by remember { mutableStateOf(addTo != null) }
    val placed = remember { mutableStateListOf<Entry>().also { if (addTo != null) it.addAll(current) } }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    // a handover opens with the note ready for whoever comes next
    var options by remember { mutableStateOf(handover) }
    var note by remember { mutableStateOf("") }
    var at by remember { mutableStateOf<Long?>(null) }
    var notify by remember { mutableStateOf("default") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pins by remember(accountId) { mutableStateOf(false) }
    if (pins) WidgetPinsSheet(model, accountId) { pins = false }

    fun mode(toAdd: Boolean) {
        add = toAdd
        placed.clear()
        if (toAdd) placed.addAll(current)
    }
    val subjects = remember(model, query) {
        val recents = model.recents(40)
        val pinIds = WidgetPins.read(ctx, accountId)
        val own = model.active.filter { it.createdByAccountId == null || it.createdByAccountId == accountId }
        val members = own.map { Subject("member", it.id, it.shownName, it.color, it.glyph, it.avatarBlob) to
            listOfNotNull(it.name, it.displayName, it.pronouns, *it.sigils.toTypedArray()).joinToString(" ") }
        val groups = model.groups.filter { it.isSubsystem && Subsystems.memberOf(model, it.id) == null }
            .map { Subject("group", it.id, it.name, it.color ?: "#A09184", "◌") to it.name }
        val states = model.states.map { Subject("state", it.id, it.name, it.color ?: "#A39E92", it.icon ?: "?") to it.name }
        val rank = { s: Subject -> pinIds.indexOf(s.id).takeIf { it >= 0 } ?: (1_000 + recents.indexOf(s.id).let { if (it < 0) 10_000 else it }) }
        (members.sortedBy { rank(it.first) } + groups + states).mapNotNull { (s, text) -> fuzzy(query, text)?.let { s to it } }
            .sortedBy { if (query.isBlank()) 0 else it.second }.map { it.first }
    }

    fun tap(s: Subject, layer: String? = null) {
        val i = placed.indexOfFirst { it.subjectType == s.type && it.subjectId == s.id }
        val ids = cycle.map { it.id }
        if (i < 0) {
            val first = layer ?: addTo?.takeIf { add } ?: ids.firstOrNull() ?: "front"
            placed.add(Entry(s.type, s.id, first, false))
        } else {
            val next = ids.indexOf(placed[i].level) + 1
            if (next in 1 until ids.size) placed[i] = placed[i].copy(level = ids[next], isPrimary = false) else placed.removeAt(i)
        }
    }
    fun withPrimary(entries: List<Entry>): List<Entry> {
        val inner = cycle.firstOrNull()?.id ?: return entries
        val primary = entries.indexOfFirst { it.isPrimary && it.level == inner }.takeIf { it >= 0 } ?: entries.indexOfFirst { it.level == inner }
        return entries.mapIndexed { n, e -> e.copy(isPrimary = n == primary) }
    }
    val layerOf = { e: Entry -> cycle.firstOrNull { it.id == e.level } }
    val changes = if (!add) emptyList() else placed.mapNotNull { e ->
        val was = current.firstOrNull { it.subjectType == e.subjectType && it.subjectId == e.subjectId }
        if (was?.level == e.level) null else Placement(e.subjectType, e.subjectId, e.level, was != null)
    } + current.filter { c -> placed.none { it.subjectType == c.subjectType && it.subjectId == c.subjectId } }
        .map { Placement(it.subjectType, it.subjectId, null, true) }
    val summary = if (add) changes.mapNotNull { c -> model.subject(c.type, c.id)?.name?.let { n ->
        c.layerId?.let { id -> "$n ${cycle.firstOrNull { it.id == id }?.name?.lowercase() ?: id}" } ?: "$n away" } }.joinToString(" · ")
    else placed.mapNotNull { e -> model.subject(e.subjectType, e.subjectId)?.name?.let { "$it ${layerOf(e)?.name?.lowercase() ?: e.level}" } }.joinToString(" · ")

    fun commit() {
        busy = true
        actions.launch {
            try {
                if (add) {
                    val names = changes.mapNotNull { model.subject(it.type, it.id)?.name }
                    Front.placeAll(chorus, changes, "Changed $frontLabel: ${names.joinToString(", ")}", if (named != null) target else null, at)
                } else {
                    val entries = withPrimary(placed.toList())
                    val inner = entries.filter { it.level == cycle.firstOrNull()?.id }.mapNotNull { model.subject(it.subjectType, it.subjectId)?.name }
                    Front.switch(chorus, entries, if (entries.isEmpty()) "Switched $frontLabel out" else "Switched $frontLabel to ${inner.ifEmpty { entries.mapNotNull { model.subject(it.subjectType, it.subjectId)?.name } }.joinToString(" & ")}",
                        at, note, notify, frontId = if (named != null) target else null)
                }
                onClose()
            } catch (e: Exception) { error = e.message; busy = false }
        }
    }
    var pickingTime by remember { mutableStateOf(false) }
    fun pickTime() { pickingTime = true }

    ChorusSheet(onDismissRequest = onClose, skipPartiallyExpanded = true, containerColor = p.surface) {
        SheetHeader("Switch · $frontLabel", null, onClose, actions = {
            BarIcon(R.drawable.ic_search, if (searching) "Close search" else "Search", p.ink2) { searching = !searching; if (!searching) query = "" }
        })
        Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 6.dp)) {
            Segmented(listOf("Replace front", "Add to front"), if (add) 1 else 0) { mode(it == 1) }
        }
        if (searching) SearchField(query, { query = it }, "Search ${subjects.size} members", Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth())
        LazyVerticalGrid(GridCells.Fixed(4), Modifier.fillMaxWidth().heightIn(max = 360.dp).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 10.dp, bottom = 6.dp)) {
            items(subjects, key = { "${it.type}:${it.id}" }) { s ->
                val e = placed.firstOrNull { it.subjectType == s.type && it.subjectId == s.id }
                val layer = e?.let(layerOf)
                PickFace(s, layer, e?.level, Modifier.combinedClickable(onClickLabel = "Place ${s.name}", onLongClick = {
                    placed.removeAll { it.subjectType == s.type && it.subjectId == s.id }
                }) { tap(s) })
            }
        }
        if (subjects.isEmpty()) Text(if (query.isBlank()) "No members yet. Add them in Members." else "No one matches “$query”.",
            color = p.ink3, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
        Column(Modifier.fillMaxWidth().border(width = 0.dp, color = Color.Transparent)) {
            Spacer(Modifier.fillMaxWidth().height(1.dp).background(p.line))
            Row(Modifier.fillMaxWidth().clickable { options = !options }.padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Glyph(R.drawable.ic_clock, p.ink2, 18.dp)
                val stamp = at?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) }
                Text(stamp?.let { "Starting $it" } ?: "Starting now", color = p.ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(if (options) "Done" else "Change", color = p.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            if (options) Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(at == null, onClick = { at = null }, label = { Text("Now") })
                    FilterChip(at != null, onClick = { pickTime() }, label = { Text(if (at == null) "Earlier…" else "Change time") })
                }
                if (pickingTime) DateTimeDrawer(at ?: System.currentTimeMillis(), { at = it; pickingTime = false }, { pickingTime = false },
                    "When the switch happened", latest = System.currentTimeMillis())
                if (!add) {
                    ChorusField(note, { note = it }, label = { Text(if (handover) "Handover note for whoever's next" else "Note (optional)") }, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("default" to "Usual alerts", "silent" to "Silent", "now" to "Alert now", "extra_delay" to "Delay").forEach { (value, label) ->
                            FilterChip(notify == value, onClick = { notify = value }, label = { Text(label) })
                        }
                    }
                }
                Text("Arrange widget pins", color = p.accent, fontSize = 13.sp, modifier = Modifier.clickable { pins = true }.padding(vertical = 4.dp))
            }
        }
        error?.let { Text(it, color = p.danger, modifier = Modifier.padding(horizontal = 20.dp)) }
        // an empty Replace is never the big button: switching everyone out is its own quiet link
        val canSwitch = !busy && (if (add) changes.isNotEmpty() else placed.isNotEmpty())
        if (!add && placed.isEmpty() && current.isNotEmpty()) Text("Switch everyone out of $frontLabel", color = p.danger, fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 20.dp).clip(RoundedCornerShape(8.dp)).clickable(enabled = !busy) { commit() }.padding(vertical = 8.dp))
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 20.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (canSwitch) p.accent else p.surface3).clickable(enabled = canSwitch, role = Role.Button) { commit() }
            .padding(horizontal = 16.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Switch", color = if (canSwitch) p.onAccent else p.ink3, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(summary.ifEmpty { "Tap someone to place them" }, color = if (canSwitch) p.onAccent else p.ink3,
                fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A face in the switch grid, ringed and tagged with the layer it's placed on (F3.2). */
@Composable
private fun PickFace(s: Subject, layer: SceneLayer?, layerId: String?, modifier: Modifier) {
    val p = LocalChorusPalette.current
    val ink = layer?.let { layerInk(it.style, parseHex(it.color), p) }
    Column(modifier.clip(RoundedCornerShape(12.dp)).semantics { contentDescription = s.name + (layer?.let { ", ${it.name}" } ?: layerId?.let { ", $it" } ?: "") },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val ring = Modifier.size(62.dp).clip(CircleShape).then(when {
            ink == null -> Modifier
            layer.style == FrontingDisplay.Style.Plain -> Modifier.background(p.line)
            else -> Modifier.border(if (layer.style == FrontingDisplay.Style.Solid) 2.5.dp else 2.dp, ink.ring, CircleShape)
        })
        Box(ring, contentAlignment = Alignment.Center) {
            if (s.type == "state") Box(Modifier.size(52.dp).clip(CircleShape).background(p.surface2).layerOutline(p.ink3.copy(alpha = .6f), 26.dp, true),
                contentAlignment = Alignment.Center) { Text(s.glyph, color = p.ink2, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
            else Avatar(s.glyph, s.color, 52.dp, avatarBlob = s.avatarBlob, ring = false)
        }
        Text(s.name, color = p.ink, fontSize = 13.sp, fontWeight = if (layer != null) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        if (layer != null && ink != null) LayerTag(layer.name, ink) else Spacer(Modifier.height(15.dp))
    }
}

/**
 * Where one member is (Figma F3.3): their place in this front as nested choices, Away, the
 * separately listed layers, the other fronts they're also in, and their state (F3.10).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaceSheet(chorus: Chorus, model: Model, frontId: String, s: Subject, since: Long?, onState: () -> Unit, onClose: () -> Unit) {
    val p = LocalChorusPalette.current
    val actions = rememberCoroutineScope()
    val named = model.namedFronts
    val accountId = chorus.device?.accountId.orEmpty()
    val entries = if (named != null) named.entries(frontId) else model.current
    val scene = FrontScene.build(model, frontId, entries, accountId)
    val here = entries.firstOrNull { it.subjectType == s.type && it.subjectId == s.id }
    val front = frontName(named?.fronts?.get(frontId))
    var choice by remember(here?.level) { mutableStateOf(here?.level) }
    var error by remember { mutableStateOf<String?>(null) }
    val layerName = { id: String? -> id?.let { i -> scene.all.firstOrNull { it.id == i }?.name ?: i } ?: "Away" }
    val target = if (named != null) frontId else null
    fun act(block: suspend () -> Unit) = actions.launch { try { block(); error = null } catch (e: Exception) { error = e.message ?: "Could not save." } }
    val time = DateFormat.getTimeInstance(DateFormat.SHORT)

    ChorusSheet(onDismissRequest = onClose, skipPartiallyExpanded = true, containerColor = p.surface) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SheetHeader(s.name, "${layerName(here?.level).lowercase()} in $front" + (if (here != null && since != null) " · since ${time.format(Date(since))}" else ""),
                onClose, lead = { Avatar(s.glyph, s.color, 52.dp, avatarBlob = s.avatarBlob, ring = false) })
            Text("Place in $front", color = p.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 6.dp))
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val nested = scene.all.filter { !it.separate }
                if (nested.isNotEmpty()) NestedChoice(nested, nested.lastIndex, choice) { choice = it }
                ChoiceBox("Away", "not fronting in $front", choice == null) { choice = null }
                scene.all.filter { it.separate }.forEach { l ->
                    ChoiceBox(l.name, l.about ?: "listed separately", choice == l.id, hidden = l.hidden) { choice = l.id }
                }
            }
            if (s.type == "member") {
                val status = model.accountPrefs.memberStatus[s.id]
                Row(Modifier.fillMaxWidth().clickable(onClick = onState).padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("State", color = p.ink, fontSize = 15.sp)
                        status?.text?.let { Text(it, color = p.ink3, fontSize = 13.sp) }
                    }
                    Glyph(R.drawable.ic_chevron_right, p.ink3, 18.dp)
                }
            }
            val others = named?.fronts?.values?.filter { !it.deleted && it.id != frontId }.orEmpty()
            if (others.isNotEmpty()) {
                Text("Also present in", color = p.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 2.dp))
                FrontingDisplay.ordered(others, named?.bodyId ?: accountId, model.accountPrefs.frontingDisplay).forEach { f ->
                    val there = named?.entries(f.id)?.firstOrNull { it.subjectType == s.type && it.subjectId == s.id }
                    val layers = FrontScene.layers(model)
                    val following = named?.resolved?.get(f.id)?.state == "following"
                    ToggleRow(frontName(f), there != null, subtitle = there?.let { e -> layers.firstOrNull { it.id == e.level }?.name }
                        ?: if (following) "Follows ${frontName(named?.fronts?.get(f.followSource))}" else "Not here") { on ->
                        act {
                            Front.placeOne(chorus, Placement(s.type, s.id, if (on) layers.firstOrNull()?.id ?: "front" else null, there != null),
                                if (on) "${s.name} is in ${frontName(f)}" else "${s.name} left ${frontName(f)}", f.id)
                        }
                    }
                }
            }
            error?.let { Text(it, color = p.danger, modifier = Modifier.padding(horizontal = 20.dp)) }
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (here != null) Box(Modifier.clip(RoundedCornerShape(14.dp)).border(1.dp, p.line, RoundedCornerShape(14.dp)).clickable {
                    act { Front.placeOne(chorus, Placement(s.type, s.id, null, true), "${s.name} left $front", target); onClose() }
                }.padding(horizontal = 16.dp, vertical = 12.dp)) { Text("Remove", color = p.danger, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                val same = choice == here?.level
                Box(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(p.accent).clickable {
                    if (same) onClose() else act {
                        Front.placeOne(chorus, Placement(s.type, s.id, choice, here != null),
                            if (choice == null) "${s.name} left $front" else "${s.name} is ${layerName(choice).lowercase()} in $front", target)
                        onClose()
                    }
                }.padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                    Text(when {
                        same && here == null -> "Keep away"
                        same -> "Keep ${layerName(choice).lowercase()}"
                        choice == null -> "Set away"
                        else -> "Move to ${layerName(choice).lowercase()}"
                    }, color = p.onAccent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** The nested layer choices, drawn as the containment they are (outermost box first). */
@Composable
private fun NestedChoice(layers: List<SceneLayer>, i: Int, chosen: String?, onPick: (String) -> Unit) {
    val p = LocalChorusPalette.current
    val l = layers[i]
    val on = chosen == l.id
    val box = Modifier.fillMaxWidth().clip(RoundedCornerShape((14 + 2 * i).dp)).then(when {
        on -> Modifier.background(p.accent.copy(alpha = .1f)).border(2.dp, p.accent, RoundedCornerShape((14 + 2 * i).dp))
        i == 0 -> Modifier.background(p.surface2).border(1.dp, p.accent.copy(alpha = .5f), RoundedCornerShape(14.dp))
        else -> Modifier.layerOutline(p.ink3.copy(alpha = .4f), (14 + 2 * i).dp, true)
    }).padding(10.dp)
    Column(box, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (i > 0) NestedChoice(layers, i - 1, chosen, onPick)
        ChoiceLine(l.name, l.about?.lowercase() ?: "", on) { onPick(l.id) }
    }
}

@Composable
private fun ChoiceBox(title: String, about: String, on: Boolean, hidden: Boolean = false, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).then(if (on) Modifier.background(p.accent.copy(alpha = .1f))
        .border(2.dp, p.accent, RoundedCornerShape(14.dp)) else Modifier.border(1.dp, p.line, RoundedCornerShape(14.dp))).padding(10.dp)) {
        ChoiceLine(title, about, on, hidden, onClick)
    }
}

@Composable
private fun ChoiceLine(title: String, about: String, on: Boolean, hidden: Boolean = false, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(role = Role.RadioButton, onClick = onClick)
        .semantics { contentDescription = "$title${if (on) ", selected" else ""}" }.padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = if (on) p.accent else p.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (hidden) Glyph(R.drawable.ic_eye_off, p.ink3, 12.dp, "hidden on Fronting")
            }
            if (about.isNotEmpty()) Text(about, color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Box(Modifier.size(22.dp).clip(CircleShape).border(2.dp, if (on) p.accent else p.line, CircleShape), contentAlignment = Alignment.Center) {
            if (on) Box(Modifier.size(10.dp).background(p.accent, CircleShape))
        }
    }
}

/** A member's state (Figma F3.10): a few common ones, the system's custom states, or their own words. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemberStateSheet(chorus: Chorus, model: Model, s: Subject, onClose: () -> Unit) {
    val p = LocalChorusPalette.current
    val actions = rememberCoroutineScope()
    val current = model.accountPrefs.memberStatus[s.id]?.text
    val common = (listOf("Tired", "Nonverbal", "Busy") + model.states.map { it.name }).distinct()
    var picked by remember { mutableStateOf(current?.let { c -> common.firstOrNull { it.equals(c, true) } }) }
    var custom by remember { mutableStateOf(current?.takeIf { picked == null }.orEmpty()) }
    var own by remember { mutableStateOf(current != null && picked == null) }
    var error by remember { mutableStateOf<String?>(null) }
    fun set(text: String?) = actions.launch {
        try { chorus.create("pref.set", null, FrontingDisplay.statusPayload(s.id, text, System.currentTimeMillis())); onClose() }
        catch (e: Exception) { error = e.message }
    }
    ChorusSheet(onDismissRequest = onClose, skipPartiallyExpanded = true, containerColor = p.surface) {
        SheetHeader("Member state", s.name, onClose, lead = { Avatar(s.glyph, s.color, 40.dp, avatarBlob = s.avatarBlob, ring = false) })
        FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (common + "Custom").forEach { name ->
                val on = if (name == "Custom") own else picked == name && !own
                Text(name.uppercase(), color = if (on) p.accent else p.ink2, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = .5.sp,
                    modifier = Modifier.clip(CircleShape).background(if (on) p.accentSoft else p.surface2)
                        .clickable(role = Role.RadioButton) { if (name == "Custom") own = true else { own = false; picked = name } }
                        .padding(horizontal = 12.dp, vertical = 7.dp))
            }
        }
        if (own) ChorusField(custom, { custom = it.take(80) }, label = { Text("In your own words") }, singleLine = true,
            modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth())
        error?.let { Text(it, color = p.danger, modifier = Modifier.padding(horizontal = 20.dp)) }
        Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val text = if (own) custom.trim().ifEmpty { null } else picked
            Box(Modifier.clip(RoundedCornerShape(10.dp)).background(if (text != null) p.accent else p.surface3)
                .clickable(enabled = text != null) { set(text) }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("Set state", color = if (text != null) p.onAccent else p.ink3, fontWeight = FontWeight.SemiBold)
            }
            if (current != null) Box(Modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, p.line, RoundedCornerShape(10.dp))
                .clickable { set(null) }.padding(horizontal = 16.dp, vertical = 12.dp)) { Text("Clear", color = p.ink2, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** Arrange which members lead this device's widget (moved here from the old Home grid). */
@Composable
fun WidgetPinsSheet(model: Model, accountId: String, onClose: () -> Unit) {
    val p = LocalChorusPalette.current
    val ctx = LocalContext.current
    var pins by remember(accountId) { mutableStateOf(WidgetPins.read(ctx, accountId)) }
    fun setPins(next: List<String>) { pins = next; WidgetPins.write(ctx, accountId, next) }
    val own = model.active.filter { it.createdByAccountId == null || it.createdByAccountId == accountId }
    ChorusSheet(onDismissRequest = onClose, containerColor = p.surface) {
        SheetHeader("Widget pins", "Pinned members lead this device's widget and the switch sheet.", onClose)
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
            val shown = pins.filter { id -> own.any { it.id == id } }
            shown.forEachIndexed { i, id ->
                val m = own.first { it.id == id }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("★ ${m.shownName}", color = p.ink, modifier = Modifier.weight(1f))
                    Text("↑", color = if (i > 0) p.accent else p.ink3, modifier = Modifier.clickable(enabled = i > 0) {
                        val n = pins.toMutableList(); val a = n.indexOf(id); val b = n.indexOf(shown[i - 1]); n[a] = shown[i - 1]; n[b] = id; setPins(n)
                    }.pinTarget("Move ${m.shownName} up"))
                    Text("↓", color = if (i < shown.lastIndex) p.accent else p.ink3, modifier = Modifier.clickable(enabled = i < shown.lastIndex) {
                        val n = pins.toMutableList(); val a = n.indexOf(id); val b = n.indexOf(shown[i + 1]); n[a] = shown[i + 1]; n[b] = id; setPins(n)
                    }.pinTarget("Move ${m.shownName} down"))
                    Text("Remove", color = p.accent, modifier = Modifier.clickable { setPins(pins - id) }.pinTarget("Unpin ${m.shownName}"))
                }
            }
            Text("Tap to pin", color = p.ink3, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            own.filter { it.id !in pins }.forEach { m ->
                Row(Modifier.fillMaxWidth().clickable { setPins(pins + m.id) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Avatar(m.glyph, m.color, 28.dp, avatarBlob = m.avatarBlob, ring = false)
                    Text(m.shownName, color = p.ink)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** A pin-arranging control: at least 48 dp square, its glyph centred, read out by `label`. */
private fun Modifier.pinTarget(label: String): Modifier = this
    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
    .wrapContentSize()
    .semantics { contentDescription = label }

/** Strict local wall-time parse; user_time itself travels to core for time correction. */
fun parseSwitchTime(input: String): Long? {
    val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { isLenient = false }
    val position = ParsePosition(0)
    val date = format.parse(input.trim(), position) ?: return null
    return date.time.takeIf { position.index == input.trim().length }
}
