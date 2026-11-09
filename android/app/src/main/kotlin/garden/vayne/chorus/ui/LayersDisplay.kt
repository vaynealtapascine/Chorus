package garden.vayne.chorus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.Front
import garden.vayne.chorus.data.FrontScene
import garden.vayne.chorus.data.FrontingDisplay
import garden.vayne.chorus.data.FrontingDisplay.Preset
import garden.vayne.chorus.data.FrontingDisplay.Style
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.SceneLayer
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Layers & display (Figma F3.9) for one front: a preset, the layers inner to outer (order, look,
 * whether Fronting shows them), clutter switches and suggested speakers. Layer names and order are
 * the system's (`front.layer`); everything else here is the display pref.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LayersDisplayScreen(chorus: Chorus, model: Model, frontId: String, onClose: () -> Unit) {
    val p = LocalChorusPalette.current
    val actions = rememberCoroutineScope()
    val account = chorus.device?.accountId.orEmpty()
    val named = model.namedFronts
    val display = model.accountPrefs.frontingDisplay
    val view = display.front(frontId)
    val front = named?.fronts?.get(frontId)
    val entries = if (named != null) named.entries(frontId) else model.current
    val scene = FrontScene.build(model, frontId, entries, account)
    var error by remember { mutableStateOf<String?>(null) }
    var editingLayer by remember { mutableStateOf<SceneLayer?>(null) }
    var adding by remember { mutableStateOf(false) }
    var speakers by remember { mutableStateOf(false) }
    var newState by remember { mutableStateOf("") }
    fun act(block: suspend () -> Unit) = actions.launch { try { block(); error = null } catch (e: Exception) { error = e.message ?: "Could not save." } }
    fun save(next: FrontingDisplay.Settings) = act { chorus.create("pref.set", null, FrontingDisplay.payload(next)) }
    fun setView(f: (FrontingDisplay.FrontView) -> FrontingDisplay.FrontView) = save(FrontingDisplay.withFront(display, frontId, f))
    val layers = FrontScene.layers(model)

    editingLayer?.let { l -> LayerEditor(chorus, model, l) { editingLayer = null } }
    if (adding) LayerEditor(chorus, model, null) { adding = false }
    if (speakers && front != null) PurposeDrawer(chorus, model, frontId, "authorship", "Suggested speakers in chat") { speakers = false }

    Column(Modifier.fillMaxSize().background(p.bg)) {
        SubpageBar("Layers & display", frontName(front), onClose)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)) {
            error?.let { Text(it, color = p.danger) }
            SectionLabel("Preset", Modifier.padding(top = 0.dp))
            Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Preset.entries.forEach { preset ->
                    val on = view.preset == preset
                    Column(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (on) p.accentSoft else p.surface)
                        .then(if (on) Modifier.border(1.5.dp, p.accent, RoundedCornerShape(14.dp)) else Modifier)
                        .clickable(role = Role.RadioButton) { setView { it.copy(preset = preset, hideEmpty = preset != Preset.Complete) } }
                        .padding(horizontal = 8.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PresetGlyph(preset)
                        Text(preset.label, color = if (on) p.accent else p.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(preset.about, color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp, textAlign = TextAlign.Center)
                    }
                }
            }
            SectionLabel("Layers · inner to outer")
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.surface)) {
                ReorderColumn(scene.all, { it.id }, { next ->
                    // a new display order is written as fresh levels, inner first (never rewriting history)
                    act { next.forEachIndexed { i, l -> if (layers.firstOrNull { it.id == l.id }?.level != i) Front.layer(chorus, l.id, JSONObject().put("level", i)) } }
                }) { l, handle ->
                    LayerRow(l.name, l.about ?: if (l.separate) "Listed separately" else null, l.style, parseHex(l.color), !l.hidden, handle,
                        onOpen = { editingLayer = l }) { shown ->
                        setView { v -> v.copy(hiddenLayers = if (shown) v.hiddenLayers - l.id else v.hiddenLayers + l.id) }
                    }
                }
                LayerRow("Away", "Not fronting · " + when {
                    !scene.awayShown -> "hidden on Fronting"
                    view.collapseAway -> "collapsed on Fronting"
                    else -> "listed on Fronting"
                }, Style.Plain, null, scene.awayShown, null, onOpen = null) { shown ->
                    setView { v -> v.copy(hiddenLayers = if (shown) v.hiddenLayers - FrontingDisplay.AWAY else v.hiddenLayers + FrontingDisplay.AWAY) }
                }
                Row(Modifier.fillMaxWidth().clickable { adding = true }.padding(start = 10.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Glyph(R.drawable.ic_plus, p.accent, 20.dp)
                    Text("Add a layer, e.g. Passive influence", color = p.accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
            SectionLabel("Reduce clutter")
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.surface)) {
                ToggleRow("Hide empty layers", view.hideEmpty) { on -> setView { it.copy(hideEmpty = on) } }
                ToggleRow("Collapse away members", view.collapseAway) { on -> setView { it.copy(collapseAway = on) } }
                ToggleRow("Show time in front", view.showTime) { on -> setView { it.copy(showTime = on) } }
                ToggleRow("Show other fronts on Fronting", view.showOthers) { on -> setView { it.copy(showOthers = on) } }
                if (front != null) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { speakers = true }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Suggested speakers in chat", color = p.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text(purposeSummary(front.authorshipLayers, layers.map { it.id to it.name }), color = p.accent, fontSize = 14.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Glyph(R.drawable.ic_chevron_right, p.ink3, 18.dp)
                }
            }
            SectionLabel("Front states")
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.surface).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    model.states.forEach { st ->
                        Row(Modifier.clip(CircleShape).background(p.surface2).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(st.name, color = p.ink, fontSize = 13.sp)
                            BarIcon(R.drawable.ic_close, "Remove ${st.name}", p.ink3, 14.dp) { act { chorus.create("state.delete", st.id, JSONObject()) } }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChorusField(newState, { newState = it.take(40) }, label = { Text("New state") }, singleLine = true, modifier = Modifier.weight(1f))
                    SmallButton("Add", primary = true, enabled = newState.isNotBlank()) {
                        val n = newState.trim(); newState = ""
                        act { chorus.create("state.create", chorus.newId(), JSONObject().put("name", n).put("icon", "?")) }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** A preset's little picture: one box, three nested, five nested (F3.9). */
@Composable
private fun PresetGlyph(preset: Preset) {
    val p = LocalChorusPalette.current
    val steps = when (preset) { Preset.Simple -> 0; Preset.Detailed -> 2; Preset.Complete -> 4 }
    // Figma's steps: Detailed 56×40 → 42×29 → 28×18, Complete 56×40 → 47×33 → … → 20×12
    val (dw, dh) = if (steps == 2) 14 to 11 else 9 to 7
    val w = { i: Int -> if (steps == 0) 56 else 56 - i * dw }
    val h = { i: Int -> if (steps == 0) 40 else 40 - i * dh }
    Box(Modifier.size(56.dp, 40.dp), contentAlignment = Alignment.Center) {
        for (i in 0 until steps) Box(Modifier.size(w(i).dp, h(i).dp).layerOutline(p.ink3.copy(alpha = .45f), (8 - i).dp, true, 1.2.dp))
        Box(Modifier.size(w(steps).dp, h(steps).dp).clip(RoundedCornerShape(6.dp)).background(p.accent.copy(alpha = .25f))
            .border(1.2.dp, p.accent, RoundedCornerShape(6.dp)))
    }
}

/** One layer: handle, its look as a swatch, name and description, and the eye (F3.9). */
@Composable
private fun LayerRow(name: String, about: String?, style: Style, color: androidx.compose.ui.graphics.Color?, shown: Boolean, handle: Modifier?,
    onOpen: (() -> Unit)?, onShown: (Boolean) -> Unit) {
    val p = LocalChorusPalette.current
    val ink = layerInk(style, color, p)
    Row(Modifier.fillMaxWidth().background(p.surface).then(if (onOpen != null) Modifier.clickable(onClickLabel = "Edit $name", onClick = onOpen) else Modifier)
        .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box((handle ?: Modifier).size(36.dp), contentAlignment = Alignment.Center) { if (handle != null) Glyph(R.drawable.ic_grip, p.ink3, 18.dp) }
        Box(Modifier.size(28.dp, 20.dp).clip(RoundedCornerShape(6.dp)).background(if (style == Style.Plain) androidx.compose.ui.graphics.Color.Transparent else ink.fill)
            .layerOutline(ink.border, 6.dp, ink.dashed))
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(name, color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            about?.let { Text(it, color = p.ink3, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        BarIcon(if (shown) R.drawable.ic_eye else R.drawable.ic_eye_off, if (shown) "Hide $name on Fronting" else "Show $name on Fronting",
            if (shown) p.ink2 else p.ink3, 20.dp) { onShown(!shown) }
    }
    Spacer(Modifier.fillMaxWidth().height(1.dp).background(p.line))
}

/** Add a layer, or edit one: name (the system's), description, look, colour, listed separately. */
@Composable
private fun LayerEditor(chorus: Chorus, model: Model, layer: SceneLayer?, onClose: () -> Unit) {
    val p = LocalChorusPalette.current
    val actions = rememberCoroutineScope()
    val display = model.accountPrefs.frontingDisplay
    val look = layer?.let { display.layer(it.id) }
    var name by remember { mutableStateOf(layer?.name.orEmpty()) }
    var about by remember { mutableStateOf(look?.about ?: layer?.about.orEmpty()) }
    var style by remember { mutableStateOf(look?.style) }
    var color by remember { mutableStateOf(look?.color) }
    var separate by remember { mutableStateOf(look?.separate ?: false) }
    var error by remember { mutableStateOf<String?>(null) }
    val swatches = listOf(null, "#42A397", "#E8776D", "#E0B560", "#B99CE8", "#6FA8DC", "#86BFA5")
    fun save() = actions.launch {
        try {
            val id = layer?.id ?: chorus.newId()
            if (layer == null) Front.layer(chorus, id, JSONObject().put("name", name.trim()).put("level",
                (FrontScene.layers(model).maxOfOrNull { it.level } ?: -1) + 1))
            else if (name.trim() != layer.name) Front.layer(chorus, id, JSONObject().put("name", name.trim()))
            val text = about.trim().ifEmpty { null }?.takeIf { layer == null || it != FrontingDisplay.defaultAbout(layer.id) }
            chorus.create("pref.set", null, FrontingDisplay.payload(FrontingDisplay.withLayer(model.accountPrefs.frontingDisplay, id) {
                FrontingDisplay.LayerLook(style, text, separate, color) }))
            onClose()
        } catch (e: Exception) { error = e.message ?: "Could not save." }
    }
    Drawer(onClose, if (layer == null) "New layer" else layer.name, footer = {
        error?.let { Text(it, color = p.danger, fontSize = 14.sp) }
        DrawerButton("Save", { save() }, enabled = name.isNotBlank())
        if (layer != null && layer.id !in ORIGINAL) DrawerButton("Remove layer", {
            actions.launch { try { Front.layer(chorus, layer.id, JSONObject().put("deleted", true)); onClose() } catch (e: Exception) { error = e.message } }
        }, ButtonKind.Quiet)
    }) {
        ChorusField(name, { name = it.take(40) }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
        ChorusField(about, { about = it.take(80) }, Modifier.fillMaxWidth(), label = { Text("Description") }, placeholder = { Text("In control · counts as fronting") })
        SectionLabel("Look", Modifier.padding(top = 4.dp))
        // the look as the tiles Fronting draws (F3.9): Automatic, then each style in this colour
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (listOf<Style?>(null) + Style.entries).forEach { s ->
                val on = style == s
                val ink = layerInk(s ?: Style.Dashed, parseHex(color), p)
                Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (on) p.accentSoft else cardColor())
                    .then(if (on) Modifier.border(1.5.dp, p.accent, RoundedCornerShape(12.dp)) else Modifier)
                    .clickable(role = Role.RadioButton) { style = s }.padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(32.dp, 22.dp).clip(RoundedCornerShape(6.dp))
                        .background(if (s == null || s == Style.Plain) androidx.compose.ui.graphics.Color.Transparent else ink.fill)
                        .layerOutline(if (s == null) p.ink3.copy(alpha = .5f) else ink.border, 6.dp, s == null || ink.dashed)) {
                        if (s == null) Text("A", color = p.ink3, fontSize = 11.sp, modifier = Modifier.align(Alignment.Center))
                    }
                    Text(s?.label ?: "Auto", color = if (on) p.accent else p.ink2, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                }
            }
        }
        SectionLabel("Colour")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            swatches.forEach { hex ->
                val c = parseHex(hex)
                Box(Modifier.size(32.dp).clip(CircleShape).background(c ?: cardColor())
                    .border(if (color == hex) 2.dp else 1.dp, if (color == hex) p.ink else p.line, CircleShape)
                    .clickable(onClickLabel = hex ?: "Accent") { color = hex }, contentAlignment = Alignment.Center) {
                    if (hex == null) Text("A", color = p.ink3, fontSize = 12.sp)
                }
            }
        }
        FormCard { ToggleRow("List separately", separate, inset = 14.dp) { separate = it } }
    }
}
