package garden.vayne.chorus.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.Entry
import garden.vayne.chorus.data.Front
import garden.vayne.chorus.data.FrontScene
import garden.vayne.chorus.data.FrontState
import garden.vayne.chorus.data.FrontStory
import garden.vayne.chorus.data.FrontingDisplay
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.NamedHistory
import garden.vayne.chorus.data.SceneLayer
import garden.vayne.chorus.data.StoryLine
import garden.vayne.chorus.data.SwitchRow
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/** A stretch of one subject on one layer, for the lane timeline. */
private data class Span(val type: String, val id: String, val layer: String, val start: Long, val end: Long?)

private enum class Zoom(val label: String, val days: Int) { Day("Day", 1), Week("Week", 7), Month("Month", 30) }

/**
 * Front history (Figma F3.7): a lane timeline per member (day, week or month), the changes as
 * sentences, review cards when two devices switched together (F3.6), the saved entries that can
 * be edited, undone or restored, and Log a past switch (F3.5). Read offline through shared core.
 */
@Composable
fun History(chorus: Chorus, model: Model, initialFront: String? = null, openReview: Boolean = false, onBack: () -> Unit = {}) {
    val p = LocalChorusPalette.current
    val ctx = LocalContext.current
    val actions = rememberCoroutineScope()
    val account = chorus.device?.accountId.orEmpty()
    val named = model.namedFronts
    val configured = named != null
    val fronts = shownFronts(model, account)
    var selected by rememberSaveable(initialFront) { mutableStateOf(initialFront.orEmpty()) }
    val front = selected.takeIf { id -> named?.fronts?.containsKey(id) == true } ?: named?.bodyId?.ifEmpty { null } ?: account
    var zoom by rememberSaveable { mutableStateOf(Zoom.Day) }
    var dayOffset by rememberSaveable { mutableIntStateOf(0) }
    var logging by rememberSaveable { mutableStateOf(false) }
    var savedOpen by rememberSaveable { mutableStateOf(false) }
    var shown by remember(front) { mutableIntStateOf(200) }
    var acting by remember { mutableStateOf<StoryLine?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val revision by chorus.frontHistoryRevision.collectAsState()
    val loaded by produceState<Result<NamedHistory?>?>(null, revision, front, retry, configured) {
        value = null
        value = if (configured) runCatching { chorus.frontHistory(front) } else Result.success(null)
    }
    val history = loaded?.getOrNull()?.takeIf { it.frontId == front }
    val loading = configured && loaded == null
    androidx.activity.compose.BackHandler(logging) { logging = false }
    if (logging) { LogPastSwitch(chorus, model, front, history) { logging = false }; return }

    val scene = FrontScene.build(model, front, emptyList(), account)
    val order = scene.all.filter { !it.separate }.map { it.id } + scene.all.filter { it.separate }.map { it.id }
    val layerOf = { id: String -> scene.all.firstOrNull { it.id == id } }
    val name = { t: String, id: String -> model.subject(t, id)?.name ?: "Someone" }
    val states = remember(model.switches, history, configured) {
        if (configured) history?.changes.orEmpty().filter { !it.deleted && it.state != "unavailable" }
            .map { FrontState(it.id, it.occurredAt, it.entries.map { e -> e.displayEntry() }) }
        else model.switches.filter { !it.retracted }.map { FrontState(it.id, it.occurredAt, it.resultingFront) }
    }
    val spans = remember(model.frontIntervals, history, configured) {
        if (configured) history?.intervals.orEmpty().map { Span(it.presence.subjectType, it.presence.subjectId, it.presence.layerId, it.startAt, it.endAt) }
        else model.frontIntervals.map { Span(it.subjectType, it.subjectId, it.level, it.startAt, it.endAt) }
    }
    val saved: List<SwitchRow> = if (configured) history?.saved.orEmpty() else model.switches
    val lines = remember(states, order) {
        FrontStory.lines(states, order, scene.all.lastOrNull { !it.separate }?.id, { layerOf(it)?.name ?: it }, name)
    }
    val now = System.currentTimeMillis()
    val dayStart = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, -dayOffset)
    }.timeInMillis
    val rangeEnd = dayStart + 86_400_000L
    val rangeStart = rangeEnd - zoom.days * 86_400_000L
    val inRange = lines.filter { it.at in rangeStart until rangeEnd }.asReversed()
    val reviews = (if (configured) history?.reviews.orEmpty() else model.reviews).filter { it.id !in model.resolvedReviews }
    var reviewing by rememberSaveable(openReview) { mutableStateOf(openReview) }
    fun leaveReview() { reviewing = false; if (openReview) onBack() }
    androidx.activity.compose.BackHandler(reviewing) { leaveReview() }
    // the last one was just answered, or there was nothing to check
    androidx.compose.runtime.LaunchedEffect(reviewing, reviews.isEmpty(), loading) { if (reviewing && reviews.isEmpty() && !loading) leaveReview() }
    val answering = reviews.firstOrNull().takeIf { reviewing }
    if (answering != null) { FrontReviewPage(chorus, model, answering, reviews.size, saved, front.takeIf { configured }, ::leaveReview); return }
    val dayLabel = SimpleDateFormat("EEEE d MMMM", Locale.getDefault())

    fun savedFor(line: StoryLine): SwitchRow? = if (!configured) saved.firstOrNull { it.id == line.changeId }
        else history?.changes?.firstOrNull { it.id == line.changeId }?.causes?.firstOrNull { it.frontId == front }
            ?.let { c -> saved.firstOrNull { it.id == c.opId } }
    fun act(id: String, block: suspend () -> Unit) = actions.launch {
        pending = id
        try { block(); error = null } catch (e: Exception) { error = e.message ?: "Could not save the change. Try again." } finally { pending = null }
    }
    var retiming by remember { mutableStateOf<SwitchRow?>(null) }
    fun editTime(row: SwitchRow) { retiming = row }
    retiming?.let { row ->
        DateTimeDrawer(row.occurredAt, { t ->
            retiming = null
            act(row.id) { Front.amendTime(chorus, row.id, t, frontId = front.takeIf { configured }) }
        }, { retiming = null }, "When it happened")
    }
    acting?.let { line ->
        val row = savedFor(line)
        val at = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(line.at))
        MenuDrawer(true, { acting = null }, line.text, listOfNotNull(at, row?.note,
            if (row == null && configured) "Comes from ${frontName(named?.fronts?.get(front))}" else null).joinToString(" · ")) {
            MenuRow("Edit time", R.drawable.ic_clock, enabled = row != null) { acting = null; row?.let(::editTime) }
            MenuRow("Undo this switch", R.drawable.ic_history, enabled = row != null) {
                acting = null; row?.let { r -> act(r.id) { Front.retract(chorus, r.id, frontId = front.takeIf { configured }) } }
            }
        }
    }
    var entry by remember { mutableStateOf<SwitchRow?>(null) }
    entry?.let { row ->
        MenuDrawer(true, { entry = null }, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(row.occurredAt)), row.note) {
            if (!row.retracted) MenuRow("Edit time", R.drawable.ic_clock) { entry = null; editTime(row) }
            MenuRow(if (row.retracted) "Redo this switch" else "Undo this switch", if (row.retracted) R.drawable.ic_restore else R.drawable.ic_history) {
                entry = null; act(row.id) { Front.retract(chorus, row.id, redo = row.retracted, frontId = front.takeIf { configured }) }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(p.bg)) {
        SubpageBar("Front history", dayLabel.format(Date(dayStart)), onBack) {
            BarIcon(R.drawable.ic_plus, "Log a past switch", p.ink) { logging = true }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Segmented(Zoom.entries.map { it.label }, zoom.ordinal) { zoom = Zoom.entries[it] } }
            if (configured && fronts.size > 1) item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    fronts.forEach { f ->
                        val on = f.id == front
                        Text(frontName(f), color = if (on) p.accent else p.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(CircleShape).background(if (on) p.accentSoft else p.surface)
                                .border(1.dp, if (on) p.accent else p.line, CircleShape).clickable(role = Role.Tab) { selected = f.id }
                                .padding(horizontal = 12.dp, vertical = 7.dp))
                    }
                }
            }
            if (reviews.isNotEmpty()) item(key = "reviews") {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.surface).clickable(role = Role.Button) { reviewing = true }
                    .padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Glyph(R.drawable.ic_alert, p.warn, 18.dp)
                    Text("${reviews.size} ${if (reviews.size == 1) "thing" else "things"} to check", color = p.ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Glyph(R.drawable.ic_chevron_right, p.ink3, 18.dp)
                }
            }
            item {
                Lanes(model, spans, scene, rangeStart, rangeEnd, now, zoom,
                    onPrev = { dayOffset += zoom.days }, onNext = { dayOffset = (dayOffset - zoom.days).coerceAtLeast(0) }, canNext = dayOffset > 0)
            }
            error?.let { item { Text(it, color = p.danger) } }
            if (loaded?.exceptionOrNull() != null) item {
                Text("Could not load saved history.", color = p.danger)
                Text("Try again", color = p.accent, modifier = Modifier.clickable { retry++ }.padding(8.dp))
            }
            item {
                Text(when {
                    zoom != Zoom.Day -> "Last ${zoom.days} days"
                    dayOffset == 0 -> "Today"
                    dayOffset == 1 -> "Yesterday"
                    else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(dayStart))
                }, color = p.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
            }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 16.dp), color = p.accent, trackColor = p.surface3) }
            else if (inRange.isEmpty()) item {
                Text("No changes recorded ${if (zoom == Zoom.Day) "this day" else "in this time"}.", color = p.ink3, modifier = Modifier.padding(4.dp))
            } else item {
                val fmt = if (zoom == Zoom.Day) DateFormat.getTimeInstance(DateFormat.SHORT) else SimpleDateFormat("EEE HH:mm", Locale.getDefault())
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.surface)) {
                    inRange.take(shown).forEachIndexed { i, line ->
                        if (i > 0) Spacer(Modifier.fillMaxWidth().height(1.dp).background(p.line))
                        val s = model.subject(line.subjectType, line.subjectId)
                        val layer = line.layerId?.let(layerOf)
                        Row(Modifier.fillMaxWidth().clickable { acting = line }.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(fmt.format(Date(line.at)), color = p.ink3, fontSize = 13.sp, maxLines = 1, modifier = Modifier.width(if (zoom == Zoom.Day) 64.dp else 76.dp))
                            Avatar(s?.glyph ?: "·", s?.color ?: "#A09184", 34.dp, avatarBlob = s?.avatarBlob, ring = false)
                            Text(line.text, color = p.ink, fontSize = 15.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f))
                            if (layer != null) LayerTag(layer.name, layerInk(layer.style, parseHex(layer.color), p), fontSize = 11)
                        }
                    }
                }
            }
            if (inRange.size > shown) item { Text("Show older", color = p.accent, modifier = Modifier.clickable { shown += 200 }.padding(8.dp)) }
            item {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { logging = true }.padding(horizontal = 4.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Glyph(R.drawable.ic_history, p.accent, 20.dp)
                    Text("Log a past switch", color = p.accent, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }
            item {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { savedOpen = !savedOpen }.padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Saved entries", color = p.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Glyph(R.drawable.ic_chevron_down, p.ink3, 18.dp)
                }
            }
            if (savedOpen) item(key = "saved") {
                FormCard {
                    saved.asReversed().take(shown).forEachIndexed { i, row ->
                        if (i > 0) FormDivider()
                        val lead = (row.resultingFront.firstOrNull { it.isPrimary } ?: row.resultingFront.firstOrNull() ?: row.entries.firstOrNull())
                            ?.let { model.subject(it.subjectType, it.subjectId) }
                        Row(Modifier.fillMaxWidth().clickable(enabled = pending != row.id, onClickLabel = "Options") { entry = row }
                            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Avatar(lead?.glyph ?: "·", lead?.color ?: "#A09184", 32.dp, avatarBlob = lead?.avatarBlob, ring = false)
                            Column(Modifier.weight(1f)) {
                                val who = (if (row.retracted) row.entries else row.resultingFront).joinToString(" & ") { e -> "${name(e.subjectType, e.subjectId)} (${layerOf(e.level)?.name ?: e.level})" }
                                Text(who.ifEmpty { "No one recorded" }, color = if (row.retracted) p.ink3 else p.ink, fontWeight = FontWeight.Medium,
                                    textDecoration = if (row.retracted) TextDecoration.LineThrough else null)
                                row.note?.let { Text(it, color = p.ink2, fontSize = 12.sp) }
                                Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(row.occurredAt)) + if (row.retracted) " · undone" else "",
                                    color = p.ink3, fontSize = 12.sp)
                            }
                            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { Glyph(R.drawable.ic_more_vert, p.ink3, 20.dp) }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** The lane timeline: one row per member, bars styled by layer, a now line and a legend (F3.7). */
@Composable
private fun Lanes(model: Model, spans: List<Span>, scene: FrontScene, from: Long, to: Long, now: Long, zoom: Zoom,
    onPrev: () -> Unit, onNext: () -> Unit, canNext: Boolean) {
    val p = LocalChorusPalette.current
    val visible = spans.filter { (it.end ?: now) > from && it.start < to }
    // a day zooms to the hours that were used (at least six), the longer views show it all
    val (start, end) = if (zoom != Zoom.Day || visible.isEmpty()) from to to else {
        val hour = 3_600_000L
        val a = ((visible.minOf { maxOf(it.start, from) } - from) / (3 * hour)) * 3 * hour + from
        val b = minOf(to, ((visible.maxOf { minOf(it.end ?: now, to) } - from + 3 * hour - 1) / (3 * hour)) * 3 * hour + from)
        if (b - a < 6 * hour) maxOf(from, minOf(a, b - 6 * hour)) to maxOf(b, a + 6 * hour).coerceAtMost(to) else a to b
    }
    val who = visible.groupBy { it.type to it.id }.entries.sortedBy { (_, v) -> v.minOf { it.start } }.take(12)
    val ticks = when (zoom) {
        Zoom.Day -> generateSequence(start) { it + 3 * 3_600_000L }.takeWhile { it <= end }.toList()
        Zoom.Week -> generateSequence(start) { it + 86_400_000L }.takeWhile { it < end }.toList()
        Zoom.Month -> generateSequence(start) { it + 7 * 86_400_000L }.takeWhile { it < end }.toList()
    }
    val tickFmt = when (zoom) { Zoom.Day -> SimpleDateFormat("HH", Locale.getDefault()); Zoom.Week -> SimpleDateFormat("EEE", Locale.getDefault())
        Zoom.Month -> SimpleDateFormat("d MMM", Locale.getDefault()) }
    val styleOf = { layer: String -> scene.all.firstOrNull { it.id == layer }?.style ?: FrontingDisplay.Style.Faint }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.surface).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(96.dp)) {
                Row {
                    Text("‹", color = p.accent, fontSize = 18.sp, modifier = Modifier.clickable(onClick = onPrev).padding(horizontal = 8.dp)
                        .semantics { contentDescription = "Earlier" })
                    if (canNext) Text("›", color = p.accent, fontSize = 18.sp, modifier = Modifier.clickable(onClick = onNext).padding(horizontal = 8.dp)
                        .semantics { contentDescription = "Later" })
                }
            }
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).height(16.dp)) {
                ticks.forEach { t ->
                    val f = ((t - start).toFloat() / (end - start)).coerceIn(0f, 1f)
                    // a label too near the end would run into the last one, so it is left out
                    if (f <= .92f) Text(tickFmt.format(Date(t)), color = p.ink3, fontSize = 11.sp, maxLines = 1,
                        modifier = Modifier.offset(x = maxWidth * f))
                }
            }
        }
        if (who.isEmpty()) Text("No one was here in this time.", color = p.ink3, fontSize = 13.sp)
        who.forEach { (key, mine) ->
            val s = model.subject(key.first, key.second)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.width(96.dp).padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Avatar(s?.glyph ?: "·", s?.color ?: "#A09184", 22.dp, avatarBlob = s?.avatarBlob, ring = false)
                    Text(s?.name ?: "Someone", color = p.ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
                val tone = tonesOf(s?.color ?: "#A09184").ring
                Canvas(Modifier.weight(1f).height(22.dp)) {
                    drawRoundRect(p.surface2, cornerRadius = CornerRadius(4.dp.toPx()))
                    val span = (end - start).toFloat()
                    mine.forEach { iv ->
                        val x0 = ((maxOf(iv.start, start) - start) / span * size.width).coerceIn(0f, size.width)
                        val x1 = ((minOf(iv.end ?: now, end) - start) / span * size.width).coerceIn(0f, size.width)
                        if (x1 <= x0) return@forEach
                        val w = maxOf(x1 - x0, 3.dp.toPx())
                        val r = CornerRadius(4.dp.toPx())
                        when (styleOf(iv.layer)) {
                            FrontingDisplay.Style.Solid -> drawRoundRect(tone, Offset(x0, 0f), Size(w, size.height), r)
                            FrontingDisplay.Style.Dashed -> drawRoundRect(tone.copy(alpha = .45f), Offset(x0, 0f), Size(w, size.height), r)
                            FrontingDisplay.Style.Plain -> drawRoundRect(p.line, Offset(x0, 0f), Size(w, size.height), r)
                            FrontingDisplay.Style.Faint -> drawRoundRect(tone, Offset(x0 + 1, 1f), Size(w - 2, size.height - 2), r,
                                style = Stroke(1.2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))))
                        }
                    }
                    if (now in start..end) {
                        val x = (now - start) / span * size.width
                        drawLine(p.accent, Offset(x, -4f), Offset(x, size.height + 4f), 2.dp.toPx())
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally)) {
            scene.all.filter { !it.separate }.take(3).forEach { l ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    val ink = layerInk(l.style, null, p)
                    Box(Modifier.size(14.dp, 10.dp).clip(RoundedCornerShape(3.dp)).background(when (l.style) {
                        FrontingDisplay.Style.Solid -> p.ink3; FrontingDisplay.Style.Dashed -> p.ink3.copy(alpha = .45f); else -> androidx.compose.ui.graphics.Color.Transparent
                    }).layerOutline(if (l.style == FrontingDisplay.Style.Faint) p.ink3 else androidx.compose.ui.graphics.Color.Transparent, 3.dp, ink.dashed, 1.dp))
                    Text(l.name, color = p.ink3, fontSize = 11.sp, maxLines = 1)
                }
            }
        }
    }
}
