package garden.vayne.chorus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.Front
import garden.vayne.chorus.data.FrontScene
import garden.vayne.chorus.data.FrontingDisplay
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.NamedFront
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Purposes a front selects layers for; null keeps the existing default (D-080, D-085). */
internal val PURPOSES = listOf(
    "authorship" to "Suggested speakers in chat",
    "notifications" to "Switch notifications",
    "mentions" to "@front mentions",
    "reading" to "Read tracking",
    "chat_alerts" to "“Only while fronting” alerts",
)
internal val ORIGINAL = setOf("front", "cocon", "present")

/** The sparse `front.define` patch that sets one purpose's selection. */
internal fun purposePatch(purpose: String, value: Set<String>?): JSONObject {
    val v: Any = value?.let { JSONArray(it.sorted()) } ?: JSONObject.NULL
    return when (purpose) {
        "authorship" -> JSONObject().put("authorship_layers", v)
        "notifications" -> JSONObject().put("notification_layers", v)
        else -> JSONObject().put("purpose_layers", JSONObject().put(purpose, v))
    }
}

internal fun selectionOf(front: NamedFront, purpose: String): Set<String>? = when (purpose) {
    "authorship" -> front.authorshipLayers
    "notifications" -> front.notificationLayers
    else -> front.purposeLayers[purpose]
}

/**
 * A column whose rows can be dragged into a new order by their handle (F3.4, F3.9). Rows are few
 * (fronts, layers), so it is a plain column; `onMove` gets the final order once, on release.
 */
@Composable
fun <T> ReorderColumn(items: List<T>, key: (T) -> String, onMove: (List<T>) -> Unit, modifier: Modifier = Modifier,
    spacing: androidx.compose.ui.unit.Dp = 0.dp, row: @Composable (item: T, handle: Modifier) -> Unit) {
    var order by remember(items.map(key)) { mutableStateOf(items) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val heights = remember { HashMap<String, Int>() }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing)) {
        order.forEach { item ->
            val k = key(item)
            val handle = Modifier.pointerInput(k) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { dragging = k; offset = 0f },
                    onDragEnd = { dragging = null; offset = 0f; onMove(order) },
                    onDragCancel = { dragging = null; offset = 0f; order = items },
                ) { change, amount ->
                    change.consume()
                    offset += amount.y
                    val i = order.indexOfFirst { key(it) == k }
                    val step = (heights[k] ?: 1) + spacing.toPx()
                    if (offset > step / 2 && i < order.lastIndex) { order = order.toMutableList().also { it.add(i + 1, it.removeAt(i)) }; offset -= step }
                    if (offset < -step / 2 && i > 0) { order = order.toMutableList().also { it.add(i - 1, it.removeAt(i)) }; offset += step }
                }
            }.semantics { contentDescription = "Hold and drag to reorder" }
            Box(Modifier.onSizeChanged { heights[k] = it.height }.zIndex(if (dragging == k) 1f else 0f)
                .offset { IntOffset(0, if (dragging == k) offset.roundToInt() else 0) }) { row(item, handle) }
        }
    }
}

/** Fronts (Figma F3.4): each front's orbit, how it's set up, whether Fronting shows it, its order,
 * and new fronts from templates. Following, overrides and purpose pools are resolved in core;
 * this screen writes sparse definitions and the display pref. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FrontsScreen(chorus: Chorus, model: Model, startNaming: Boolean = false, onNamingStarted: () -> Unit = {}, onClose: () -> Unit) {
    val p = LocalChorusPalette.current
    val actions = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }
    var naming by remember { mutableStateOf(false) }
    // the Fronting bar's "+" opens straight onto naming a new front (UI21)
    LaunchedEffect(startNaming) { if (startNaming) { naming = true; onNamingStarted() } }
    var arranging by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    val account = chorus.device?.accountId.orEmpty()
    val named = model.namedFronts
    val display = model.accountPrefs.frontingDisplay
    val body = named?.bodyId?.ifEmpty { null } ?: account
    val all = named?.fronts?.values?.toList() ?: listOf(NamedFront(account, "Body", emptyList(), null, false, false, null, null, emptyMap()))
    val live = FrontingDisplay.ordered(all.filter { !it.deleted }, body, display)
    fun act(block: suspend () -> Unit) = actions.launch {
        try { block(); error = null } catch (e: Exception) { error = e.message ?: "Could not save." }
    }
    fun saveDisplay(next: FrontingDisplay.Settings) = act { chorus.create("pref.set", null, FrontingDisplay.payload(next)) }
    // the first front an unconfigured account makes moves Body onto named fronts too: ask first
    var confirming by remember { mutableStateOf<Pair<String, FrontingDisplay.Template?>?>(null) }
    fun write(name: String, template: FrontingDisplay.Template?) {
        val n = name.trim().ifEmpty { return }
        act {
            val id = chorus.newId()
            Front.define(chorus, id, JSONObject().put("name", n).put("follow_source", if (template?.followsBody == true) body else JSONObject.NULL))
            if (template != null) chorus.create("pref.set", null, FrontingDisplay.payload(FrontingDisplay.withFront(display, id) {
                it.copy(icon = template.icon, about = template.about) }))
        }
    }
    fun create(name: String, template: FrontingDisplay.Template?) {
        if (name.isBlank()) return
        if (named == null) confirming = name to template else write(name, template)
    }
    confirming?.let { (name, template) ->
        ConfirmDrawer("Set up fronts?", "Turns on separate fronts. Body keeps its history. This can't be undone.",
            "Add front", { write(name, template); confirming = null }, { confirming = null }, icon = R.drawable.ic_layers)
    }
    editing?.let { id -> all.firstOrNull { it.id == id }?.let { f ->
        FrontEditor(chorus, model, f, all.filter { !it.deleted && it.id != f.id }, isBody = f.id == body) { editing = null }
    } }

    Column(Modifier.fillMaxSize().background(p.bg)) {
        SubpageBar("Fronts", null, onClose) {
            BarIcon(if (arranging) R.drawable.ic_check else R.drawable.ic_pencil, if (arranging) "Done arranging" else "Arrange fronts", p.ink) { arranging = !arranging }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            error?.let { Text(it, color = p.danger) }
            ReorderColumn(live, { it.id }, { next -> saveDisplay(display.copy(order = next.map { it.id })) }, spacing = 10.dp) { f, handle ->
                val i = live.indexOf(f)
                val move = { by: Int -> saveDisplay(display.copy(order = live.toMutableList().also { it.add(i + by, it.removeAt(i)) }.map { x -> x.id })); Unit }
                FrontCard(model, f, body, account, display, handle, arrange = if (arranging) move else null, isFirst = i == 0, isLast = i == live.lastIndex,
                    onOpen = { editing = f.id }) { hidden ->
                    saveDisplay(FrontingDisplay.withFront(display, f.id) { it.copy(hidden = hidden) })
                }
            }
            Column(Modifier.fillMaxWidth().layerOutline(p.line, 18.dp, true).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { naming = true }) {
                    Box(Modifier.size(36.dp).background(p.accentSoft, CircleShape), contentAlignment = Alignment.Center) { Glyph(R.drawable.ic_plus, p.accent, 20.dp) }
                    Text("New front", color = p.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // a template already in use isn't offered twice
                    (FrontingDisplay.TEMPLATES.map { it.name } + "Custom").filter { t -> live.none { it.name.equals(t, true) } }.forEach { t ->
                        Text(t, color = p.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.clip(CircleShape).background(p.surface2)
                            .clickable(role = Role.Button) { if (t == "Custom") naming = true else create(t, FrontingDisplay.TEMPLATES.first { it.name == t }) }
                            .padding(horizontal = 12.dp, vertical = 7.dp))
                    }
                }
                if (naming) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChorusField(newName, { newName = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.weight(1f))
                    SmallButton("Add", primary = true, enabled = newName.isNotBlank()) { create(newName, null); newName = ""; naming = false }
                }
            }
            val removed = all.filter { it.deleted }
            if (removed.isNotEmpty()) {
                SectionLabel("Removed fronts")
                FormCard {
                    removed.forEachIndexed { i, f ->
                        if (i > 0) FormDivider()
                        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(f.name, color = p.ink2, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            SmallButton("Restore") { act { Front.define(chorus, f.id, JSONObject().put("deleted", false)) } }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FrontCard(model: Model, f: NamedFront, body: String, account: String, display: FrontingDisplay.Settings, handle: Modifier,
    arrange: ((Int) -> Unit)?, isFirst: Boolean, isLast: Boolean, onOpen: () -> Unit, onHidden: (Boolean) -> Unit) {
    val p = LocalChorusPalette.current
    val named = model.namedFronts
    val entries = if (named != null) named.entries(f.id) else model.current
    val scene = FrontScene.build(model, f.id, entries, account)
    val resolved = named?.resolved?.get(f.id)
    val source = resolved?.source?.let { named.fronts[it]?.name }
    val tag = when (resolved?.state) {
        "following" -> "Follows ${source ?: "another front"}"
        "overridden" -> "Changed by hand"
        "unavailable" -> if (resolved.issue == "cycle") "Following loops" else "Source unavailable"
        else -> if (f.id == body) "Main" else "Own layers"
    }
    val view = display.front(f.id)
    val nested = scene.all.filter { !it.separate }
    val first = nested.firstOrNull()?.entries.orEmpty().mapNotNull { model.subject(it.subjectType, it.subjectId)?.name }
    val about = view.about ?: if (first.isEmpty()) "No one here right now"
        else "${first.joinToString(" and ")} ${nested.first().name.lowercase()}"
    val rings = nested.take(3).map { l -> l.entries.mapNotNull { model.subject(it.subjectType, it.subjectId) } }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.surface).clickable(onClickLabel = "Edit ${frontName(f)}", onClick = onOpen)
        .padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Orbit(rings, 76.dp, Modifier.alpha(if (view.hidden) .5f else 1f))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(frontName(f), color = p.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(tag, color = p.ink2, fontSize = 11.sp, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(p.surface2)
                    .padding(horizontal = 6.dp, vertical = 1.dp), maxLines = 1)
            }
            Text(about, color = p.ink2, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                scene.all.filter { it.entries.isNotEmpty() }.forEach { l ->
                    LayerTag("${l.name} ${l.entries.size}", layerInk(l.style, parseHex(l.color), p), fontSize = 11)
                }
            }
        }
        if (arrange != null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("↑", color = if (isFirst) p.ink3 else p.accent, fontSize = 20.sp, modifier = Modifier.size(48.dp).clickable(enabled = !isFirst) { arrange(-1) }
                .semantics { contentDescription = "Move ${frontName(f)} up" }.padding(12.dp))
            Text("↓", color = if (isLast) p.ink3 else p.accent, fontSize = 20.sp, modifier = Modifier.size(48.dp).clickable(enabled = !isLast) { arrange(1) }
                .semantics { contentDescription = "Move ${frontName(f)} down" }.padding(12.dp))
        } else Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BarIcon(if (view.hidden) R.drawable.ic_eye_off else R.drawable.ic_eye,
                if (view.hidden) "Show ${frontName(f)} on Fronting" else "Hide ${frontName(f)} from Fronting",
                if (view.hidden) p.ink3 else p.ink2, 20.dp) { onHidden(!view.hidden) }
            Box(handle.size(40.dp), contentAlignment = Alignment.Center) { Glyph(R.drawable.ic_grip, p.ink3, 18.dp) }
        }
    }
}

/** One front's own settings: name, icon, blurb, following, who counts for what, removal. Taps apply at
 * once; the name and description are saved when the drawer closes. */
@Composable
private fun FrontEditor(chorus: Chorus, model: Model, front: NamedFront, others: List<NamedFront>, isBody: Boolean, onClose: () -> Unit) {
    val p = LocalChorusPalette.current
    val actions = rememberCoroutineScope()
    val display = model.accountPrefs.frontingDisplay
    var name by remember(front.id, front.name) { mutableStateOf(front.name) }
    var about by remember(front.id) { mutableStateOf(display.front(front.id).about.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var following by remember { mutableStateOf(false) }
    var purpose by remember { mutableStateOf<Pair<String, String>?>(null) }
    val layers = FrontScene.layers(model).map { it.id to it.name }
    fun act(block: suspend () -> Unit) = actions.launch { try { block(); error = null } catch (e: Exception) { error = e.message ?: "Could not save." } }
    fun define(patch: JSONObject) = act { Front.define(chorus, front.id, patch) }
    fun look(f: (FrontingDisplay.FrontView) -> FrontingDisplay.FrontView) =
        act { chorus.create("pref.set", null, FrontingDisplay.payload(FrontingDisplay.withFront(model.accountPrefs.frontingDisplay, front.id, f))) }
    fun close() {
        if (name.isNotBlank() && name.trim() != front.name) define(JSONObject().put("name", name.trim()))
        if (about.trim() != display.front(front.id).about.orEmpty()) look { it.copy(about = about.trim().ifEmpty { null }) }
        onClose()
    }
    Drawer(::close, frontName(front), footer = {
        error?.let { Text(it, color = p.danger, fontSize = 14.sp) }
        DrawerButton("Done", ::close)
        if (!isBody) DrawerButton("Remove ${frontName(front)}", { define(JSONObject().put("deleted", true)); onClose() }, ButtonKind.Quiet)
    }) {
        ChoiceDrawer(following, "Follows", listOf<NamedFront?>(null) + others, others.firstOrNull { it.id == front.followSource },
            { it?.let(::frontName) ?: "Nothing" }, { o -> following = false; define(JSONObject().put("follow_source", o?.id ?: JSONObject.NULL)) },
            { following = false })
        purpose?.let { (key, label) -> PurposeDrawer(chorus, model, front.id, key, label) { purpose = null } }
        ChorusField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
        ChorusField(about, { about = it.take(140) }, Modifier.fillMaxWidth(), label = { Text("Description") })
        SectionLabel("Icon", Modifier.padding(top = 4.dp))
        val icon = FrontingDisplay.icon(front.id, front.name, model.namedFronts?.bodyId ?: front.id, display)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FrontingDisplay.ICONS.forEach { k ->
                Box(Modifier.size(40.dp).clip(CircleShape).background(if (k == icon) p.accentSoft else cardColor())
                    .then(if (k == icon) Modifier.border(1.dp, p.accent, CircleShape) else Modifier)
                    .clickable(onClickLabel = k) { look { it.copy(icon = k) } }, contentAlignment = Alignment.Center) {
                    Glyph(frontIconRes(k), if (k == icon) p.accent else p.ink2, 18.dp, k)
                }
            }
        }
        FormCard {
            FormValueRow("Follows", others.firstOrNull { it.id == front.followSource }?.let(::frontName) ?: "Nothing") { following = true }
            if (model.namedFronts?.resolved?.get(front.id)?.state == "overridden") {
                FormDivider()
                FormValueRow("Changed by hand", "Resume following") { act { Front.resume(chorus, front.id) } }
            }
        }
        SectionLabel("Who counts for what")
        FormCard {
            PURPOSES.forEachIndexed { i, (key, label) ->
                if (i > 0) FormDivider()
                FormValueRow(label, purposeSummary(selectionOf(front, key), layers)) { purpose = key to label }
            }
        }
    }
}

/** Which layers count for one of a front's purposes (D-080, D-085): Default, or the layers picked. */
@Composable
internal fun PurposeDrawer(chorus: Chorus, model: Model, frontId: String, purpose: String, title: String, onClose: () -> Unit) {
    val actions = rememberCoroutineScope()
    val front = model.namedFronts?.fronts?.get(frontId) ?: return
    val layers = FrontScene.layers(model)
    val sel = selectionOf(front, purpose)
    fun set(next: Set<String>?) = actions.launch { runCatching { Front.define(chorus, frontId, purposePatch(purpose, next)) } }
    Drawer(onClose, title, footer = { DrawerButton("Done", onClose) }) {
        FormCard {
            ToggleRow("Default", sel == null, purposeSummary(null, layers.map { it.id to it.name }), inset = 14.dp) { on ->
                set(if (on) null else layers.take(2).map { it.id }.toSet())
            }
            layers.forEach { l ->
                FormDivider()
                ToggleRow(l.name, sel?.contains(l.id) == true, enabled = sel != null, inset = 14.dp) { next ->
                    set(if (next) sel.orEmpty() + l.id else sel.orEmpty() - l.id)
                }
            }
        }
    }
}

/** Summary of a purpose selection for a settings row ("Steering + co-con"). */
internal fun purposeSummary(sel: Set<String>?, layers: List<Pair<String, String>>): String = when {
    sel == null -> layers.filter { it.first == "front" || it.first == "cocon" }.joinToString(" + ") { it.second }.ifEmpty { "Default" }
    sel.isEmpty() -> "Nobody"
    else -> layers.filter { it.first in sel }.joinToString(" + ") { it.second }
}

