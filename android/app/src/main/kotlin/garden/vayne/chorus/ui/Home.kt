package garden.vayne.chorus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.Entry
import garden.vayne.chorus.data.Front
import garden.vayne.chorus.data.FrontScene
import garden.vayne.chorus.data.FrontingDisplay
import garden.vayne.chorus.data.FrontingDisplay.Preset
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.NamedFront
import garden.vayne.chorus.data.SceneLayer
import garden.vayne.chorus.data.Subject
import garden.vayne.chorus.designsystem.HankenGrotesk
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Simple fuzzy match (same as the web): every query char in order. Lower = better, null = no match. */
fun fuzzy(query: String, text: String): Int? {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return 0
    val t = text.lowercase()
    val direct = t.indexOf(q)
    if (direct >= 0) return direct
    var ti = 0
    var gaps = 0
    for (ch in q) {
        val found = t.indexOf(ch, ti)
        if (found < 0) return null
        gaps += found - ti
        ti = found + 1
    }
    return 100 + gaps
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val p = LocalChorusPalette.current
    Box(
        modifier.heightIn(min = 48.dp).background(p.surface, RoundedCornerShape(10.dp))
            .border(1.dp, p.line, RoundedCornerShape(10.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        if (value.isEmpty()) Text(placeholder, color = p.ink3, fontSize = 15.sp)
        BasicTextField(
            value, onChange, singleLine = true,
            textStyle = TextStyle(color = p.ink, fontSize = 15.sp, fontFamily = HankenGrotesk),
            cursorBrush = SolidColor(p.accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The fronts a system shows as tabs on Fronting, in its order; Body alone when unconfigured. */
fun shownFronts(model: Model, accountId: String): List<NamedFront> {
    val named = model.namedFronts
    val display = model.accountPrefs.frontingDisplay
    val live = named?.fronts?.values?.filter { !it.deleted }.orEmpty()
        .ifEmpty { listOf(NamedFront(accountId, "Body", emptyList(), null, false, false, null, null, emptyMap())) }
    return FrontingDisplay.ordered(live, named?.bodyId ?: accountId, display)
}

fun frontName(f: NamedFront?): String = f?.name?.ifBlank { null } ?: "Body"

/** When each subject in a front arrived (its open interval), read from the front's effective
 * history: never folded during Model.parse, and cached per front revision. */
@Composable
fun rememberSince(chorus: Chorus, model: Model, frontId: String): Map<String, Long> {
    val revision by chorus.frontHistoryRevision.collectAsState()
    val configured = model.namedFronts != null
    val loaded by produceState<Map<String, Long>?>(null, revision, frontId, configured) {
        value = if (!configured) null else runCatching { chorus.frontHistory(frontId) }.getOrNull()
            ?.takeIf { it.frontId == frontId }?.intervals?.filter { it.endAt == null }
            ?.groupBy { it.presence.subjectId }?.mapValues { (_, v) -> v.minOf { it.startAt } }
    }
    return if (configured) loaded.orEmpty()
    else model.frontIntervals.filter { it.endAt == null }.groupBy { it.subjectId }.mapValues { (_, v) -> v.minOf { it.startAt } }
}

/** The newest note on the front's saved history, for the Complete view's "front notes". */
@Composable
private fun rememberFrontNote(chorus: Chorus, model: Model, frontId: String): String? {
    val revision by chorus.frontHistoryRevision.collectAsState()
    val configured = model.namedFronts != null
    val note by produceState<String?>(null, revision, frontId, configured) {
        value = if (!configured) model.switches.lastOrNull { !it.retracted }?.note
        else runCatching { chorus.frontHistory(frontId) }.getOrNull()?.saved?.lastOrNull { !it.retracted }?.note
    }
    return note?.takeIf { it.isNotBlank() }
}

/**
 * Fronting (Figma F3.1, F3.11): one tab per front, the selected front's layers as nested
 * containers, separately listed layers, Away, the other fronts, and a Switch button. Each front
 * can be shown Simple, Detailed or Complete (F3.9, `fronting.display`, D-122).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Home(chorus: Chorus, model: Model, onFronts: () -> Unit = {}, onHistory: (String) -> Unit = {}, onLayers: (String) -> Unit = {}) {
    val p = LocalChorusPalette.current
    val accountId = chorus.device?.accountId.orEmpty()
    val named = model.namedFronts
    val display = model.accountPrefs.frontingDisplay
    val fronts = shownFronts(model, accountId)
    val tabs = fronts.filter { !display.front(it.id).hidden || it.id == (named?.bodyId ?: accountId) }
    var selectedFront by rememberSaveable(accountId) { mutableStateOf<String?>(null) }
    val frontId = selectedFront?.takeIf { id -> fronts.any { it.id == id } } ?: named?.bodyId?.takeIf { it.isNotEmpty() } ?: accountId
    val front = fronts.firstOrNull { it.id == frontId }
    val entries = if (named != null) named.entries(frontId) else model.current
    val scene = FrontScene.build(model, frontId, entries, accountId)
    val view = display.front(frontId)
    val since = rememberSince(chorus, model, frontId)
    val note = if (view.preset == Preset.Complete) rememberFrontNote(chorus, model, frontId) else null
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    val undo by Front.undo.collectAsState()
    LaunchedEffect(undo) { val u = undo ?: return@LaunchedEffect; delay(10_000); Front.dismiss(u.opId) }
    val actions = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var switcher by remember { mutableStateOf<String?>(null) } // null closed, "" whole sheet, else a layer to add to
    var placing by remember { mutableStateOf<Subject?>(null) }
    var stateFor by remember { mutableStateOf<Subject?>(null) }
    var awayOpen by remember(frontId) { mutableStateOf(!view.collapseAway) }
    var expanded by remember(frontId) { mutableStateOf(setOf<String>()) }
    var menu by remember { mutableStateOf(false) }
    fun save(next: FrontingDisplay.Settings) = actions.launch {
        try { chorus.create("pref.set", null, FrontingDisplay.payload(next)); error = null } catch (e: Exception) { error = e.message }
    }
    val time = DateFormat.getTimeInstance(DateFormat.SHORT)
    val frontSince = since.values.minOrNull() ?: model.since.takeIf { named == null }
    val resolved = named?.resolved?.get(frontId)

    if (switcher != null) Switcher(chorus, model, if (named != null) frontId else null, addTo = switcher?.ifEmpty { null }) { switcher = null }
    placing?.let { s -> PlaceSheet(chorus, model, frontId, s, since[s.id], onState = { stateFor = s; placing = null }) { placing = null } }
    stateFor?.let { s -> MemberStateSheet(chorus, model, s) { stateFor = null } }

    fun open(e: Entry) { model.subject(e.subjectType, e.subjectId)?.let { placing = it } }
    fun lineFor(e: Entry): String? {
        val status = if (view.preset != Preset.Simple && e.subjectType == "member") model.accountPrefs.memberStatus[e.subjectId]?.text else null
        if (status != null) return status
        val pronouns = if (e.subjectType == "member") model.member(e.subjectId)?.pronouns?.takeIf { it.isNotBlank() } else null
        val at = since[e.subjectId]?.let { "since ${time.format(Date(it))}" }
        return listOfNotNull(pronouns, at).joinToString(" · ").ifEmpty { null }
    }
    fun took(e: Entry): String? = if (view.showTime) since[e.subjectId]?.let { FrontingDisplay.duration(now - it) } else null

    Box(Modifier.fillMaxSize().background(p.bg)) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Column(Modifier.fillMaxWidth().background(p.surface)) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Fronting", color = p.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 26.sp)
                            val total = fronts.firstOrNull()?.let { if (named != null) named.entries(named.bodyId.ifEmpty { accountId }).size else model.current.size } ?: 0
                            Text(listOfNotNull(model.systemName?.takeIf { it.isNotBlank() },
                                if (view.preset == Preset.Simple) "simple view" else "$total here now").joinToString(" · "),
                                color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp)
                        }
                        if (!model.isPerson) {
                            BarIcon(R.drawable.ic_history, "Front history", p.ink) { onHistory(frontId) }
                            BarIcon(R.drawable.ic_sliders, "Layers and display", p.ink, onClick = { onLayers(frontId) })
                        }
                    }
                    if (!model.isPerson) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .drawBehind { drawLine(p.line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
                        .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        tabs.forEach { f ->
                            val on = f.id == frontId
                            val count = if (named != null) named.entries(f.id).size else model.current.size
                            Row(Modifier.clip(RoundedCornerShape(18.dp)).background(if (on) p.accentSoft else p.surface2)
                                .then(if (on) Modifier.border(1.dp, p.accent, RoundedCornerShape(18.dp)) else Modifier)
                                .clickable(role = Role.Tab) { selectedFront = f.id }
                                .semantics { contentDescription = "${frontName(f)}, $count here" + if (on) ", selected" else "" }
                                .padding(start = 10.dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Glyph(frontIconRes(FrontingDisplay.icon(f.id, f.name, named?.bodyId ?: accountId, display)), if (on) p.accent else p.ink3, 16.dp)
                                Text(frontName(f), color = if (on) p.accent else p.ink, fontSize = 13.sp,
                                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium)
                                Text("$count", color = if (on) p.accent else p.ink3, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Box(Modifier.size(32.dp).clip(CircleShape).layerOutline(p.line, 16.dp, true, 1.dp)
                            .clickable(onClickLabel = "Fronts", onClick = onFronts).semantics { contentDescription = "Add or manage fronts" },
                            contentAlignment = Alignment.Center) { Glyph(R.drawable.ic_plus, p.ink3, 16.dp) }
                    }
                }
            }
            if (resolved != null && resolved.state != "independent") item {
                val source = resolved.source?.let { named.fronts[it]?.name } ?: "its source"
                Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth().background(p.surface, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(when (resolved.state) {
                        "following" -> "Follows $source"
                        "overridden" -> "Changed by hand · not following $source"
                        else -> if (resolved.issue == "cycle") "Following loops back on itself" else "Its source is unavailable"
                    }, color = p.ink2, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    if (resolved.state == "overridden") Text("Resume following", color = p.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { actions.launch { try { Front.resume(chorus, resolved.id); error = null } catch (e: Exception) { error = e.message } } }.padding(8.dp))
                }
            }
            error?.let { item { Text(it, color = p.danger, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) } }
            // an unconfigured account's review cards (F3.6) are answered on Front history
            val reviews = if (named == null) model.reviews.count { it.id !in model.resolvedReviews } else 0
            if (reviews > 0) item {
                Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(p.surface).clickable { onHistory(frontId) }.padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Glyph(R.drawable.ic_history, p.warn, 18.dp)
                    Text(if (reviews == 1) "Two devices switched together · review" else "$reviews switches to review", color = p.ink,
                        fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Glyph(R.drawable.ic_chevron_right, p.ink3, 18.dp)
                }
            }
            item {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (view.preset == Preset.Simple) {
                        SimpleCard(model, scene, frontSince, ::lineFor, ::took, ::open, time)
                        if (!display.hintDismissed) Row(Modifier.fillMaxWidth().background(p.surface2, RoundedCornerShape(16.dp)).padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Glyph(R.drawable.ic_layers, p.accent, 18.dp)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("Simple view is on", color = p.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text("Only one layer is shown, empty layers and other fronts are hidden. Switch back to Detailed at any time.",
                                    color = p.ink2, fontSize = 12.sp, lineHeight = 16.sp)
                                Text("Layers & display", color = p.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.clickable { onLayers(frontId) }.padding(vertical = 4.dp))
                            }
                            Box(Modifier.size(32.dp).clip(CircleShape).clickable { save(display.copy(hintDismissed = true)) }
                                .semantics { contentDescription = "Dismiss" }, contentAlignment = Alignment.Center) { Glyph(R.drawable.ic_close, p.ink3, 14.dp) }
                        }
                    } else Column(Modifier.fillMaxWidth().background(p.surface, RoundedCornerShape(22.dp)).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Glyph(frontIconRes(FrontingDisplay.icon(frontId, frontName(front), named?.bodyId ?: accountId, display)), p.accent, 18.dp)
                            Text(frontName(front), color = p.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text(listOfNotNull(frontSince?.let { "since ${time.format(Date(it))}" }, "${scene.here} here").joinToString(" · "),
                                color = p.ink3, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Box(Modifier.size(32.dp).clip(CircleShape).clickable { menu = true }.semantics { contentDescription = "${frontName(front)} options" },
                                contentAlignment = Alignment.Center) { Glyph(R.drawable.ic_more_vert, p.ink3, 20.dp) }
                            MenuDrawer(menu, { menu = false }, frontName(front), "${scene.here} here") {
                                MenuRow("Switch…", R.drawable.ic_swap) { menu = false; switcher = "" }
                                MenuRow("Layers & display", R.drawable.ic_layers) { menu = false; onLayers(frontId) }
                                MenuRow("Front history", R.drawable.ic_history) { menu = false; onHistory(frontId) }
                                MenuRow("Manage fronts", R.drawable.ic_sliders) { menu = false; onFronts() }
                            }
                        }
                        note?.let { Text(it, color = p.ink2, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 4.dp)) }
                        val showEmpty = !view.hideEmpty || view.preset == Preset.Complete
                        val rings = scene.nested.filterIndexed { i, l -> i == 0 || showEmpty || l.entries.isNotEmpty() }
                        if (rings.isNotEmpty()) Containment(model, rings, rings.lastIndex, ::lineFor, ::took, ::open) { layer -> switcher = layer }
                        scene.separate.filter { showEmpty || it.entries.isNotEmpty() }.forEach { l ->
                            SeparateRow(model, l, l.id in expanded, ::open) { expanded = if (l.id in expanded) expanded - l.id else expanded + l.id }
                        }
                        if (scene.unlisted.isNotEmpty()) Text("Other: " + scene.unlisted.mapNotNull { model.subject(it.subjectType, it.subjectId)?.name }.joinToString(", "),
                            color = p.ink2, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp))
                    }
                    if (scene.awayShown && scene.away.isNotEmpty() && view.preset != Preset.Simple) {
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.surface).clickable { awayOpen = !awayOpen }
                            .padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Glyph(R.drawable.ic_eye_off, p.ink3, 18.dp)
                            Text("Away · ${scene.away.size} member${if (scene.away.size == 1) "" else "s"}", color = p.ink3, fontSize = 14.sp,
                                fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            if (!awayOpen) FaceStack(scene.away.map { Subject("member", it.id, it.shownName, it.color, it.glyph, it.avatarBlob) }, muted = true)
                            Glyph(if (awayOpen) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right, p.ink3, 18.dp)
                        }
                        if (awayOpen) FlowRow(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            scene.away.take(200).forEach { m ->
                                val s = Subject("member", m.id, m.shownName, m.color, m.glyph, m.avatarBlob)
                                FaceChip(s, null, faint = true) { placing = s }
                            }
                        }
                    }
                    val others = tabs.filter { it.id != frontId }
                    if (view.showOthers && view.preset != Preset.Simple && others.isNotEmpty() && named != null) {
                        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Other fronts", color = p.ink3, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text("Manage", color = p.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable(onClick = onFronts).padding(4.dp))
                        }
                        others.forEach { f -> OtherFrontRow(model, f, accountId) { selectedFront = f.id } }
                    }
                    Spacer(Modifier.height(96.dp))
                }
            }
        }
        if (!model.isPerson) Column(Modifier.align(Alignment.BottomEnd).padding(16.dp), horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            undo?.let { u ->
                Row(Modifier.fillMaxWidth().background(p.surface2, RoundedCornerShape(12.dp)).padding(start = 16.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(u.label, color = p.ink, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
                    Text("Undo", color = p.accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .clickable { actions.launch { try { Front.undo(chorus); error = null } catch (e: Exception) { error = e.message } } }.padding(12.dp))
                }
            }
            Row(Modifier.shadow(8.dp, CircleShape).clip(CircleShape).background(p.accent).clickable(onClickLabel = "Switch") { switcher = "" }
                .padding(start = 16.dp, end = 20.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Glyph(R.drawable.ic_switch_arrows, p.onAccent, 20.dp)
                Text("Switch", color = p.onAccent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** The nested layers, drawn from the outside in (Figma F3.1 "containment"). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Containment(model: Model, rings: List<SceneLayer>, i: Int, line: (Entry) -> String?, took: (Entry) -> String?,
    open: (Entry) -> Unit, onAdd: (String) -> Unit) {
    val p = LocalChorusPalette.current
    val l = rings[i]
    val ink = layerInk(l.style, parseHex(l.color), p)
    if (i == 0) {
        LayerBox(ink, 16.dp) {
            Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(l.name, color = ink.label, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    val n = l.entries.size
                    Text(listOfNotNull(FrontingDisplay.shortAbout(l.about), if (n > 1) "$n co-${l.name.lowercase()}" else null).joinToString(" · "),
                        color = p.ink3, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    AddButton(l.name, ink.label) { onAdd(l.id) }
                }
                if (l.entries.isEmpty()) Text("No one in ${l.name}", color = p.ink2, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
                l.entries.forEach { e ->
                    val s = model.subject(e.subjectType, e.subjectId) ?: return@forEach
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.surface).clickable { open(e) }
                        .padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Avatar(s.glyph, s.color, 40.dp, avatarBlob = s.avatarBlob, ring = false)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(s.name, color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            line(e)?.let { Text(it, color = p.ink3, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                        took(e)?.let { Text(it, color = p.ink3, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { Glyph(R.drawable.ic_more_vert, p.ink3, 18.dp, "${s.name} options") }
                    }
                }
            }
        }
        return
    }
    LayerBox(ink, (16 + 2 * i).dp) {
        Column(Modifier.padding(bottom = 10.dp)) {
            Containment(model, rings, i - 1, line, took, open, onAdd)
            Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column {
                    Text(l.name, color = ink.label, fontSize = 12.sp, fontWeight = FontWeight.Bold, lineHeight = 16.sp)
                    FrontingDisplay.shortAbout(l.about)?.let { Text(it, color = p.ink3, fontSize = 11.sp, lineHeight = 14.sp) }
                }
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    l.entries.forEach { e -> model.subject(e.subjectType, e.subjectId)?.let { s ->
                        FaceChip(s, took(e), faint = l.style == FrontingDisplay.Style.Faint || l.style == FrontingDisplay.Style.Plain) { open(e) }
                    } }
                }
                AddButton(l.name, ink.label) { onAdd(l.id) }
            }
        }
    }
}

@Composable
private fun AddButton(layer: String, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(Modifier.size(32.dp).clip(CircleShape).clickable(onClickLabel = "Add to $layer", onClick = onClick)
        .semantics { contentDescription = "Add someone to $layer" }, contentAlignment = Alignment.Center) { Glyph(R.drawable.ic_plus, tint, 18.dp) }
}

/** A face with its name (and time) in a raised pill: outer layers, Away, the simple view. */
@Composable
fun FaceChip(s: Subject, time: String?, faint: Boolean = false, tag: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    Row(Modifier.clip(RoundedCornerShape(16.dp)).background(p.surface2).clickable(onClick = onClick)
        .padding(start = 3.dp, end = 10.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Avatar(s.glyph, s.color, 24.dp, avatarBlob = s.avatarBlob, ring = false, modifier = if (faint) Modifier.alpha(.7f) else Modifier)
        Text(s.name, color = if (faint) p.ink3 else p.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        time?.let { Text(it, color = p.ink3, fontSize = 12.sp) }
        tag?.invoke()
    }
}

/** A layer the system lists separately, inside the front card ("Messy (3) · Hammond, Gertie…"). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeparateRow(model: Model, l: SceneLayer, open: Boolean, onOpen: (Entry) -> Unit, onToggle: () -> Unit) {
    val p = LocalChorusPalette.current
    val faces = l.entries.mapNotNull { model.subject(it.subjectType, it.subjectId) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.line)) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Glyph(R.drawable.ic_layers, p.ink2, 18.dp)
            Column(Modifier.weight(1f)) {
                Text(buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(l.name) }
                    append(" (${faces.size})")
                    if (faces.isNotEmpty()) append(" · " + faces.joinToString(", ") { it.name })
                }, color = p.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                l.about?.let { Text(it, color = p.ink3, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            FaceStack(faces)
            Glyph(if (open) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right, p.ink3, 18.dp)
        }
        if (open) FlowRow(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            l.entries.forEach { e -> model.subject(e.subjectType, e.subjectId)?.let { s -> FaceChip(s, null) { onOpen(e) } } }
        }
    }
}

/** One of the other fronts, under the selected one ("Chorus · posting as Foxglove · follows Body"). */
@Composable
private fun OtherFrontRow(model: Model, f: NamedFront, accountId: String, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    val named = model.namedFronts ?: return
    val entries = named.entries(f.id)
    val layers = FrontScene.layers(model)
    val resolved = named.resolved[f.id]
    val faces = entries.mapNotNull { model.subject(it.subjectType, it.subjectId) }
    val speaking = resolved?.authorship?.firstOrNull()?.let { model.subject(it.subjectType, it.subjectId)?.name }
    val summary = listOfNotNull(
        speaking?.takeIf { f.followSource != null }?.let { "posting as $it" },
        when (resolved?.state) {
            "following" -> "follows ${frontName(named.fronts[resolved.source])}"
            "overridden" -> "changed by hand"
            else -> null
        },
    ).ifEmpty {
        layers.mapNotNull { l -> entries.filter { it.level == l.id }.takeIf { it.isNotEmpty() }?.let { here ->
            "${l.name}: " + if (here.size == 1) model.subject(here[0].subjectType, here[0].subjectId)?.name.orEmpty() else "${here.size}"
        } }
    }.joinToString(" · ").ifEmpty { "No one here" }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.surface).clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(36.dp).background(p.surface2, CircleShape), contentAlignment = Alignment.Center) {
            Glyph(frontIconRes(FrontingDisplay.icon(f.id, f.name, named.bodyId.ifEmpty { accountId }, model.accountPrefs.frontingDisplay)), p.ink2, 18.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(frontName(f), color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(summary, color = p.ink3, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        FaceStack(faces)
        Glyph(R.drawable.ic_chevron_right, p.ink3, 18.dp)
    }
}

/** The Simple view (F3.11): who's fronting, and one line for everyone else here. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SimpleCard(model: Model, scene: FrontScene, since: Long?, line: (Entry) -> String?, took: (Entry) -> String?,
    open: (Entry) -> Unit, time: DateFormat) {
    val p = LocalChorusPalette.current
    var more by remember { mutableStateOf(false) }
    val inner = scene.innermost
    val rest = scene.all.filter { it.id != inner?.id && !it.hidden }.flatMap { l -> l.entries.map { it to l } }
    Column(Modifier.fillMaxWidth().background(p.surface, RoundedCornerShape(22.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Glyph(R.drawable.ic_target, p.accent, 18.dp)
            Text("${inner?.name ?: "Fronting"} now", color = p.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            since?.let { Text("since ${time.format(Date(it))}", color = p.ink3, fontSize = 12.sp) }
        }
        val fronting = inner?.entries.orEmpty()
        if (fronting.isEmpty()) Text("No one in ${inner?.name ?: "front"}", color = p.ink2, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
        fronting.forEach { e ->
            val s = model.subject(e.subjectType, e.subjectId) ?: return@forEach
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.accentSoft).clickable { open(e) }
                .padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Avatar(s.glyph, s.color, 40.dp, avatarBlob = s.avatarBlob, ring = false)
                Column(Modifier.weight(1f)) {
                    Text(s.name, color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    line(e)?.let { Text(it, color = p.ink3, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                took(e)?.let { Text(it, color = p.ink3, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { Glyph(R.drawable.ic_more_vert, p.ink3, 18.dp, "${s.name} options") }
            }
        }
        if (rest.isNotEmpty()) {
            val names = rest.mapNotNull { (e, _) -> model.subject(e.subjectType, e.subjectId) }
            val label = rest.map { it.second.name }.distinct().singleOrNull()?.lowercase() ?: "here"
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { more = !more }.padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FaceStack(names, size = 22.dp)
                Text("+${names.size} $label · ${names.joinToString(", ") { it.name }}", color = p.ink3, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Glyph(R.drawable.ic_chevron_down, p.ink3, 18.dp, modifier = Modifier.rotate(if (more) 180f else 0f))
            }
            if (more) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                rest.forEach { (e, l) -> model.subject(e.subjectType, e.subjectId)?.let { s ->
                    FaceChip(s, took(e), tag = { LayerTag(l.name, layerInk(l.style, parseHex(l.color), p)) }) { open(e) }
                } }
            }
        }
    }
}
