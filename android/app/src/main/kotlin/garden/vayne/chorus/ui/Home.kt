package garden.vayne.chorus.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import garden.vayne.chorus.data.Entry
import androidx.compose.material3.MaterialTheme
import garden.vayne.chorus.designsystem.HankenGrotesk
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.Front
import garden.vayne.chorus.data.Mode
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.Subject
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.widget.WidgetPins
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

private sealed interface Tile {
    data class One(val s: Subject, val whole: Boolean = false) : Tile
    data class Folder(val id: String, val name: String, val color: String?, val count: Int) : Tile
}

@Composable
fun Home(chorus: Chorus, model: Model, onFronts: () -> Unit = {}) {
    val p = LocalChorusPalette.current
    val ctx = LocalContext.current
    val accountId = chorus.device?.accountId
    val ownMembers = model.active.filter { it.createdByAccountId == null || it.createdByAccountId == accountId }
    var pins by remember(accountId) { mutableStateOf(WidgetPins.read(ctx, accountId)) }
    var editingPins by remember { mutableStateOf(false) }
    fun setPins(next: List<String>) { pins = next; WidgetPins.write(ctx, accountId, next) }
    var mode by remember { mutableStateOf(Mode.Replace) }
    var modeUsed by remember { mutableLongStateOf(0L) }
    var query by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf<String?>(null) }
    val undo by Front.undo.collectAsState()
    val actions = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    // named fronts (D-080): pick which front this screen shows and switches; Body by default
    val named = model.namedFronts
    var selectedFront by remember(accountId) { mutableStateOf<String?>(null) }
    val frontId = selectedFront?.takeIf { id -> named?.fronts?.get(id)?.deleted == false } ?: named?.bodyId
    val shownEntries = if (named != null && frontId != null) named.entries(frontId) else model.current
    var switcherOpen by remember { mutableStateOf(false) }
    if (switcherOpen) Switcher(chorus, model, frontId) { switcherOpen = false }

    // the mode resets to Replace 30 s after last use (CLIENTS.md §3.2)
    LaunchedEffect(modeUsed) {
        if (mode != Mode.Replace) {
            delay(30_000)
            mode = Mode.Replace
        }
    }
    LaunchedEffect(undo) {
        val u = undo ?: return@LaunchedEffect
        delay(10_000)
        Front.dismiss(u.opId)
    }
    BackHandler(enabled = folder != null) { folder = folder?.let { model.group(it)?.parentId } }

    val here = shownEntries.map { it.subjectType to it.subjectId }.toSet()
    val tiles: List<Tile> = remember(model, query, folder, pins, accountId) { tiles(model, query, folder, pins, accountId) }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(84.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(horizontal = 16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                val choices = named?.channelChoices.orEmpty()
                if (choices.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    choices.forEach { f ->
                        val count = named?.entries(f.id)?.size ?: 0
                        FilterChip(f.id == frontId, onClick = { selectedFront = f.id },
                            label = { Text(if (count > 0) "${f.name.ifBlank { "Body" }} · $count" else f.name.ifBlank { "Body" }) })
                    }
                }
                val resolved = frontId?.let { named?.resolved?.get(it) }
                if (resolved != null && resolved.state != "independent") {
                    val source = resolved.source?.let { named?.fronts?.get(it)?.name } ?: "its source"
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp).background(p.surface, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(when (resolved.state) {
                            "following" -> "Follows $source"
                            "overridden" -> "Changed by hand · not following $source"
                            else -> if (resolved.issue == "cycle") "Following loops back on itself" else "Its source is unavailable"
                        }, color = p.ink2, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        if (resolved.state == "overridden") TextButton(onClick = {
                            actions.launch { try { Front.resume(chorus, resolved.id); error = null } catch (e: Exception) { error = e.message } }
                        }) { Text("Resume following") }
                    }
                }
                FrontCard(model, shownEntries, title = frontId?.let { named?.fronts?.get(it)?.name?.ifBlank { null } } ?: "Current front",
                    sinceAt = if (named != null) null else model.since)
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Quick switch", color = p.ink2, fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (!model.isPerson) TextButton(onClick = onFronts) { Text("Fronts") }
                    Button(onClick = { switcherOpen = true }) {
                        ChorusIcon(AppIcon.Swap)
                        Spacer(Modifier.width(8.dp))
                        Text("Switch")
                    }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            undo?.let { u ->
                Row(
                    Modifier.fillMaxWidth().background(p.accentSoft, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(u.label, color = p.ink, modifier = Modifier.weight(1f))
                    Text("Undo", color = p.accent, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable {
                        actions.launch { try { Front.undo(chorus); error = null } catch (e: Exception) { error = e.message } }
                    }.padding(4.dp))
                }
            } ?: Spacer(Modifier.height(0.dp))
        }
        error?.let { message -> item(span = { GridItemSpan(maxLineSpan) }) { Text(message, color = p.danger) } }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    mode.label,
                    color = if (mode == Mode.Replace) p.ink2 else p.accent,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    modifier = Modifier.clip(CircleShape).background(if (mode == Mode.Replace) p.surface2 else p.accentSoft)
                        .clickable { mode = mode.next(); modeUsed = System.currentTimeMillis() }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
                SearchField(query, { query = it; folder = null }, "Search ${model.active.size} members", Modifier.weight(1f))
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Text(if (editingPins) "Done arranging pins" else "Arrange widget pins", color = p.accent,
                    modifier = Modifier.clickable { editingPins = !editingPins }.padding(vertical = 6.dp))
                if (editingPins) {
                    Text("Tap a member tile to pin or unpin. Pins appear first on this device's widget.",
                        color = p.ink2, fontSize = 12.sp)
                    val visiblePins = pins.filter { id -> ownMembers.any { it.id == id } }
                    visiblePins.forEachIndexed { i, id ->
                        val member = ownMembers.first { it.id == id }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("★ ${member.shownName}", color = p.ink, modifier = Modifier.weight(1f))
                            Text("↑", color = if (i > 0) p.accent else p.ink3,
                                modifier = Modifier.clickable(enabled = i > 0) {
                                    val next = pins.toMutableList(); val previous = visiblePins[i - 1]
                                    val pos = next.indexOf(id); val previousPos = next.indexOf(previous)
                                    next[pos] = previous; next[previousPos] = id; setPins(next)
                                }.padding(8.dp))
                            Text("↓", color = if (i < visiblePins.lastIndex) p.accent else p.ink3,
                                modifier = Modifier.clickable(enabled = i < visiblePins.lastIndex) {
                                    val next = pins.toMutableList(); val following = visiblePins[i + 1]
                                    val pos = next.indexOf(id); val followingPos = next.indexOf(following)
                                    next[pos] = following; next[followingPos] = id; setPins(next)
                                }.padding(8.dp))
                            Text("Remove", color = p.accent,
                                modifier = Modifier.clickable { setPins(pins - id) }.padding(8.dp))
                        }
                    }
                }
            }
        }
        if (folder != null && query.isBlank()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                val g = model.group(folder!!)
                Text(
                    "‹  ${g?.let { model.groupPath(it) } ?: ""}",
                    color = p.accent, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { folder = g?.parentId }.padding(vertical = 4.dp),
                )
            }
        }
        if (tiles.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    if (query.isBlank()) "No members yet. Add them in Members, or import from PluralKit on the web." else "No one matches “$query”.",
                    color = p.ink3, modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
        items(tiles, key = { t -> when (t) { is Tile.One -> "${t.s.type}:${t.s.id}:${t.whole}"; is Tile.Folder -> "f:${t.id}" } }) { t ->
            when (t) {
                is Tile.One -> SubjectTile(t.s, (t.s.type to t.s.id) in here,
                    if (editingPins && t.s.type == "member") (if (t.s.id in pins) "★ Pinned" else "Tap to pin")
                    else if (t.whole) "Whole subsystem" else null) {
                    if (editingPins && t.s.type == "member") {
                        setPins(if (t.s.id in pins) pins - t.s.id else pins + t.s.id)
                        return@SubjectTile
                    }
                    actions.launch {
                        try {
                            Front.tap(chorus, t.s, mode, if (named != null) frontId else null)
                            modeUsed = System.currentTimeMillis()
                            query = ""
                            error = null
                        } catch (e: Exception) { error = e.message }
                    }
                }
                is Tile.Folder -> FolderTile(t) { folder = t.id; query = "" }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(24.dp)) }
    }
}

private fun tiles(model: Model, query: String, folder: String?, pins: List<String> = emptyList(), accountId: String? = null): List<Tile> {
    val members = model.active.filter { it.createdByAccountId == null || it.createdByAccountId == accountId }
    if (query.isNotBlank()) {
        val ms = members.mapNotNull { m ->
            fuzzy(query, listOfNotNull(m.name, m.displayName, m.pronouns, *m.sigils.toTypedArray()).joinToString(" "))?.let { it to Tile.One(Subject("member", m.id, m.shownName, m.color, m.glyph, m.avatarBlob)) }
        }
        val gs = model.groups.filter { it.isSubsystem }.mapNotNull { g ->
            fuzzy(query, g.name)?.let { (it + 1) to Tile.One(Subject("group", g.id, g.name, g.color ?: "#A09184", "◌"), whole = true) }
        }
        return (ms + gs).sortedBy { it.first }.map { it.second }
    }
    val subsystems = model.groups.filter { it.isSubsystem }
    if (folder != null) {
        val g = model.group(folder) ?: return emptyList()
        val inside = model.membership[folder].orEmpty()
        val recent = model.recents(200)
        val order = { id: String -> recent.indexOf(id).let { if (it < 0) Int.MAX_VALUE else it } }
        return listOf<Tile>(Tile.One(Subject("group", g.id, g.name, g.color ?: "#A09184", "◌"), whole = true)) +
            subsystems.filter { it.parentId == folder }.map { Tile.Folder(it.id, it.name, it.color, model.membership[it.id]?.size ?: 0) } +
            members.filter { it.id in inside }.sortedBy { order(it.id) }.map { Tile.One(Subject("member", it.id, it.shownName, it.color, it.glyph, it.avatarBlob)) }
    }
    val recents = model.recents(8)
    val byId = members.associateBy { it.id }
    val pinned = pins.distinct().mapNotNull { byId[it] }
    val pinIds = pinned.map { it.id }.toSet()
    val first = recents.filter { it !in pinIds }.mapNotNull { byId[it] }
    val rest = members.filter { it.id !in recents && it.id !in pinIds }
    return (pinned + first).map { Tile.One(Subject("member", it.id, it.shownName, it.color, it.glyph, it.avatarBlob)) } +
        subsystems.filter { it.parentId == null }.map { Tile.Folder(it.id, it.name, it.color, model.membership[it.id]?.size ?: 0) } +
        rest.map { Tile.One(Subject("member", it.id, it.shownName, it.color, it.glyph, it.avatarBlob)) }
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

@Composable
private fun SubjectTile(s: Subject, here: Boolean, caption: String?, onTap: () -> Unit) {
    val p = LocalChorusPalette.current
    val t = tonesOf(s.color)
    Column(
        Modifier.clip(RoundedCornerShape(14.dp)).background(if (here) t.tint else p.surface)
            .border(1.dp, if (here) t.ring else p.line, RoundedCornerShape(14.dp))
            .clickable(onClick = onTap).padding(vertical = 12.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Avatar(s.glyph, s.color, 44.dp, avatarBlob = s.avatarBlob)
        Text(s.name, color = t.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        if (caption != null) Text(caption, color = p.ink3, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun FolderTile(f: Tile.Folder, onTap: () -> Unit) {
    val p = LocalChorusPalette.current
    val t = tonesOf(f.color ?: "#A09184")
    Column(
        Modifier.clip(RoundedCornerShape(14.dp)).background(p.surface2)
            .border(1.dp, p.line, RoundedCornerShape(14.dp))
            .clickable(onClick = onTap).padding(vertical = 12.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(44.dp).background(t.tint, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Text("${f.count}", color = t.name, fontWeight = FontWeight.SemiBold)
        }
        Text("${f.name} ›", color = p.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The three original layers, inner to outer, for an unconfigured account. */
private val LEGACY_LAYERS = listOf("front" to "Fronting", "cocon" to "Co-conscious", "present" to "Present")

/** Who is here now (DESIGN.md §4.1). The innermost layer lists people; outer layers nest around it
 * in display order (D-080). */
@Composable
fun FrontCard(
    model: Model,
    entries: List<Entry> = model.current,
    title: String = "Current front",
    layers: List<Pair<String, String>> = model.namedFronts?.liveLayers?.map { it.id to it.name } ?: LEGACY_LAYERS,
    sinceAt: Long? = model.since,
) {
    val p = LocalChorusPalette.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    val since = sinceAt?.let { ago(it, now) }
    val names = { layer: String -> entries.filter { it.level == layer }.mapNotNull { model.subject(it.subjectType, it.subjectId)?.name } }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).background(p.surface, RoundedCornerShape(22.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            ChorusIcon(AppIcon.Front)
            Spacer(Modifier.width(8.dp))
            Text(title, color = p.ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("${entries.size} here", color = p.ink3, fontSize = 12.sp)
        }
        LayerRing(model, entries, layers, layers.lastIndex, since, names)
        val unlisted = entries.filter { e -> layers.none { it.first == e.level } }
            .mapNotNull { model.subject(it.subjectType, it.subjectId)?.name }
        if (unlisted.isNotEmpty()) PresenceLine("Other", unlisted)
    }
}

@Composable
private fun LayerRing(
    model: Model, entries: List<Entry>, layers: List<Pair<String, String>>, i: Int, since: String?,
    names: (String) -> List<String>,
) {
    val p = LocalChorusPalette.current
    if (i <= 0) {
        val (id, label) = layers.firstOrNull() ?: ("front" to "Fronting")
        Column(Modifier.fillMaxWidth().background(p.accentSoft, RoundedCornerShape(16.dp))
            .border(1.dp, p.accent, RoundedCornerShape(16.dp)).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, color = p.accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                if (since != null) Text(since, color = p.ink3, fontSize = 12.sp)
            }
            val inner = entries.filter { it.level == id }
            if (inner.isEmpty()) Text("No one in $label", color = p.ink2, modifier = Modifier.padding(8.dp))
            inner.forEach { entry -> model.subject(entry.subjectType, entry.subjectId)?.let { member ->
                Row(Modifier.fillMaxWidth().background(p.surface, RoundedCornerShape(12.dp)).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Avatar(member.glyph, member.color, 40.dp, avatarBlob = member.avatarBlob)
                    Column(Modifier.weight(1f)) {
                        Text(member.name, color = p.ink, fontWeight = FontWeight.SemiBold)
                        if (entry.isPrimary) Text("Primary", color = p.ink3, fontSize = 12.sp)
                    }
                    if (since != null) Text(since, color = p.ink3, fontSize = 12.sp)
                }
            } }
        }
        return
    }
    val edge = if (i == 1) p.accent.copy(alpha = .5f) else p.line
    Column(Modifier.fillMaxWidth().border(1.dp, edge, RoundedCornerShape(18.dp + (2 * i).dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LayerRing(model, entries, layers, i - 1, since, names)
        PresenceLine(layers[i].second, names(layers[i].first))
    }
}

@Composable
private fun PresenceLine(label: String, names: List<String>) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = p.ink2, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(names.joinToString(" & ").ifEmpty { "No one" }, color = if (names.isEmpty()) p.ink3 else p.ink,
            fontSize = 12.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
}
