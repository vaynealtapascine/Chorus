package garden.vayne.chorus.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import garden.vayne.chorus.data.cap
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.BucketControls
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.Entry
import garden.vayne.chorus.data.Front
import garden.vayne.chorus.data.Member
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.Words
import garden.vayne.chorus.designsystem.HankenGrotesk
import garden.vayne.chorus.designsystem.R
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * A new system's first minutes (F1.2–F1.4, SPEC §3.1 step 3): the words it uses, who's in it (or
 * Import from PluralKit), and who's steering now. Every step can be left with Continue as it is;
 * [onDone] ends setup.
 */
@Composable
internal fun SystemSetup(chorus: Chorus, model: Model, name: String, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableIntStateOf(0) }
    var words by remember { mutableStateOf(model.words) }
    var chosen by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    val members = model.active.filter { !it.isSelf }
    BackHandler(step > 0) { step-- }

    fun next() {
        error = null
        when (step) {
            0 -> {
                // the name typed at welcome becomes the system's name (it went to the account's display name)
                val patch = JSONObject()
                if (words != model.words) patch.put("terminology", Words.payload(model.terminology, words))
                if (model.systemName.isNullOrBlank() && name.isNotBlank()) patch.put("name", name)
                if (patch.length() > 0) scope.launch {
                    runCatching { chorus.create("system.set", chorus.device?.accountId, patch) }.onFailure { error = it.message }
                }
                step = 1
            }
            1 -> if (members.isEmpty()) onDone() else step = 2
            else -> {
                if (chosen.isEmpty()) { onDone(); return }
                busy = true
                scope.launch {
                    try {
                        val entries = chosen.mapIndexed { i, id -> Entry("member", id, "front", i == 0) }
                        Front.switch(chorus, entries, "Switched to ${chosen.mapNotNull { model.member(it)?.shownName }.joinToString(", ")}")
                        onDone()
                    } catch (e: Exception) { error = e.message ?: "Could not save that." }
                    finally { busy = false }
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        WelcomeBlobs(compact = step > 0)
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(bottom = 22.dp)) {
            if (step == 0) {
                Spacer(Modifier.weight(1f).heightIn(min = 24.dp))
                ChorusWordmark(Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.weight(1f).heightIn(min = 24.dp))
                WordChips(words, Modifier.padding(horizontal = 38.dp)) { words = it }
            } else {
                ChorusWordmark(Modifier.align(Alignment.CenterHorizontally).padding(top = 24.dp), line = buildAnnotatedString {
                    append("Who’s ")
                    if (step == 1) { append("in the "); withStyle(SpanStyle(color = Welcome.Sun)) { append(words.system) } }
                    else withStyle(SpanStyle(color = Welcome.Sun)) { append(words.fronting) }
                    append("?")
                })
                MemberGrid(members, step, chosen, Modifier.weight(1f).padding(top = 20.dp),
                    onPick = { id -> chosen = if (id in chosen) chosen - id else chosen + id },
                    onAdd = { adding = true }, onImport = { importing = true })
            }
            error?.let {
                Text(it, color = Color(0xFF9E2A1E), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 38.dp, vertical = 6.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp)).background(Welcome.Cream).padding(horizontal = 12.dp, vertical = 8.dp))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 38.dp).padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (step > 0) WelcomeButton("Back", R.drawable.ic_back, plain = true, enabled = !busy) { step-- }
                Spacer(Modifier.weight(1f))
                WelcomeButton("Continue", R.drawable.ic_arrow_right, trailing = true, enabled = !busy, busy = busy, onClick = ::next)
            }
        }
    }
    if (adding) NewMemberDrawer(chorus, members.size, onDismiss = { adding = false }) { error = it }
    if (importing) PkImportSheet(chorus) { importing = false }
}

/** F1.2: "We call ourselves" and "When someone is in control, they are", one word each, or a custom one. */
@Composable
private fun WordChips(words: Words, modifier: Modifier = Modifier, onChange: (Words) -> Unit) {
    var custom by remember { mutableStateOf<Int?>(null) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CapsLabel("We call ourselves")
        val systems = Words.SYSTEM_CHOICES + listOfNotNull((words.system to words.systems).takeIf { it !in Words.SYSTEM_CHOICES })
        ChipFlow {
            for ((one, many) in systems) WordChip(one.cap(), words.system == one, mine = (one to many) !in Words.SYSTEM_CHOICES) {
                onChange(words.copy(system = one, systems = many))
            }
            WordChip("Custom…", false, add = true) { custom = 0 }
        }
        Spacer(Modifier.height(10.dp))
        CapsLabel("When someone is in control, they are")
        val fronts = Words.FRONT_CHOICES + listOfNotNull((words.fronting to words.front).takeIf { it !in Words.FRONT_CHOICES })
        ChipFlow {
            for ((ing, noun) in fronts) WordChip(ing.cap(), words.fronting == ing, mine = (ing to noun) !in Words.FRONT_CHOICES) {
                onChange(words.copy(front = noun, fronting = ing))
            }
            WordChip("Custom…", false, add = true) { custom = 1 }
        }
    }
    custom?.let { which ->
        var text by remember { mutableStateOf("") }
        val done = {
            if (which == 0) Words.customSystem(text)?.let { (one, many) -> onChange(words.copy(system = one, systems = many)) }
            else Words.customFront(text)?.let { (ing, noun) -> onChange(words.copy(front = noun, fronting = ing)) }
            custom = null
        }
        Drawer({ custom = null }, if (which == 0) "We call ourselves" else "When someone is in control, they are", footer = {
            DrawerButton("Use this word", { done() }, enabled = text.isNotBlank())
        }) {
            ChorusField(text, { text = it.take(24) }, Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) done() }))
        }
    }
}

@Composable
private fun CapsLabel(text: String) {
    Text(text.uppercase(), color = Welcome.Cream, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = .4.sp, fontFamily = HankenGrotesk)
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChipFlow(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

/** F1.2's chips: cream with a check when chosen, outlined when not, a bubble on one the system added, "+" for Custom…. */
@Composable
private fun WordChip(label: String, on: Boolean, mine: Boolean = false, add: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(15.dp)
    val filled = on || mine
    Row(Modifier.heightIn(min = 30.dp).clip(shape)
        .then(if (filled) Modifier.background(Welcome.Cream) else Modifier.border(2.dp, Welcome.Cream, shape))
        .clickable(role = Role.Checkbox, onClick = onClick).semantics { selected = on }.padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        val ink = if (filled) Welcome.Ink else Welcome.Cream
        when {
            on -> Glyph(R.drawable.ic_check, Welcome.Teal, 16.dp)
            add -> Glyph(R.drawable.ic_plus_circle, ink, 15.dp)
            mine -> Glyph(R.drawable.ic_plus_circle, Welcome.Teal, 15.dp)
        }
        Text(label, color = if (filled) Welcome.Ink else Welcome.Cream, fontSize = 15.sp, fontFamily = HankenGrotesk,
            fontStyle = if (add) FontStyle.Italic else FontStyle.Normal, fontWeight = FontWeight.Medium)
    }
}

/** F1.3/F1.4: the members as cream cards, three across; at step 1 a dashed "+ new" (and PluralKit) tile; at step 2 a tap picks. */
@Composable
private fun MemberGrid(members: List<Member>, step: Int, chosen: List<String>, modifier: Modifier, onPick: (String) -> Unit, onAdd: () -> Unit,
    onImport: () -> Unit) {
    LazyVerticalGrid(GridCells.Fixed(3), modifier.fillMaxWidth().fadeBottom(), contentPadding = PaddingValues(horizontal = 30.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(members, key = { it.id }) { m ->
            val picked = m.id in chosen
            MemberCard(m, picked, enabled = step == 2) { onPick(m.id) }
        }
        if (step == 1) {
            item(key = "new") { DashedTile("new", R.drawable.ic_plus, onAdd) }
            item(key = "pk") { DashedTile("PluralKit", R.drawable.ic_down, onImport) }
        }
    }
}

@Composable
private fun MemberCard(m: Member, picked: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.height(118.dp).clip(shape).background(Welcome.Cream)
        .then(if (picked) Modifier.border(3.dp, Welcome.Ink, shape) else Modifier)
        .clickable(enabled = enabled, role = Role.Checkbox, onClick = onClick).semantics { selected = picked }.padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Avatar(m.glyph, m.color, 48.dp, avatarBlob = m.avatarBlob, ring = false)
        Spacer(Modifier.height(6.dp))
        Text(m.shownName, color = Color(0xFF1C1B19), fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        m.pronouns?.takeIf { it.isNotBlank() }?.let {
            Text(it.uppercase(), color = Color(0xFF8A847A), fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1, letterSpacing = .3.sp)
        }
    }
}

@Composable
private fun DashedTile(label: String, icon: Int, onClick: () -> Unit) {
    Column(Modifier.height(118.dp).clip(RoundedCornerShape(16.dp)).drawWithContent {
        drawContent()
        drawRoundRect(Welcome.Ink, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()))
    }.clickable(role = Role.Button, onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(48.dp).background(Welcome.Cream, CircleShape), contentAlignment = Alignment.Center) { Glyph(icon, Welcome.Ink, 22.dp) }
        Spacer(Modifier.height(8.dp))
        Text(label, color = Welcome.Cream, fontSize = 16.sp, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Medium)
    }
}

/** The grid fades out above the buttons (F1.4). */
private fun Modifier.fadeBottom(): Modifier = this.drawWithContent {
    drawContent()
    val h = 48.dp.toPx()
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Welcome.Teal), startY = size.height - h, endY = size.height),
        topLeft = Offset(0f, size.height - h))
}

/** A first member from setup: a name, a colour from the palette in turn. */
@Composable
private fun NewMemberDrawer(chorus: Chorus, count: Int, onDismiss: () -> Unit, onError: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val save = {
        busy = true
        scope.launch {
            try {
                val colors = BucketControls.swatches
                chorus.create("member.create", chorus.newId(), JSONObject().put("name", name.trim()).put("color", colors[count % colors.size]))
                onDismiss()
            } catch (e: Exception) { onError(e.message ?: "Could not add them."); onDismiss() }
            finally { busy = false }
        }
        Unit
    }
    Drawer(onDismiss, "New member", dismissible = !busy, footer = {
        DrawerButton("Add", save, enabled = name.isNotBlank(), busy = busy)
    }) {
        // the keyboard is up at once: name, Done, next (fewest taps)
        val focus = remember { androidx.compose.ui.focus.FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        ChorusField(name, { name = it.take(100) }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true, label = { Text("Name") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) save() }))
    }
}
