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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.delay

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
fun Home(chorus: Chorus, model: Model) {
    val p = LocalChorusPalette.current
    var mode by remember { mutableStateOf(Mode.Replace) }
    var modeUsed by remember { mutableLongStateOf(0L) }
    var query by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf<String?>(null) }
    val undo by Front.undo.collectAsState()

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

    val here = model.current.map { it.subjectType to it.subjectId }.toSet()
    val tiles: List<Tile> = remember(model, query, folder) { tiles(model, query, folder) }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(84.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(horizontal = 16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { FrontCard(model) }
        item(span = { GridItemSpan(maxLineSpan) }) {
            undo?.let { u ->
                Row(
                    Modifier.fillMaxWidth().background(p.accentSoft, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(u.label, color = p.ink, modifier = Modifier.weight(1f))
                    Text("Undo", color = p.accent, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { Front.undo(chorus) }.padding(4.dp))
                }
            } ?: Spacer(Modifier.height(0.dp))
        }
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
                is Tile.One -> SubjectTile(t.s, (t.s.type to t.s.id) in here, if (t.whole) "Whole subsystem" else null) {
                    Front.tap(chorus, t.s, mode)
                    modeUsed = System.currentTimeMillis()
                    query = ""
                }
                is Tile.Folder -> FolderTile(t) { folder = t.id; query = "" }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(24.dp)) }
    }
}

private fun tiles(model: Model, query: String, folder: String?): List<Tile> {
    val members = model.active
    if (query.isNotBlank()) {
        val ms = members.mapNotNull { m ->
            fuzzy(query, listOfNotNull(m.name, m.displayName, m.pronouns, *m.sigils.toTypedArray()).joinToString(" "))?.let { it to Tile.One(Subject("member", m.id, m.shownName, m.color, m.glyph)) }
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
            members.filter { it.id in inside }.sortedBy { order(it.id) }.map { Tile.One(Subject("member", it.id, it.shownName, it.color, it.glyph)) }
    }
    val recents = model.recents(8)
    val byId = members.associateBy { it.id }
    val first = recents.mapNotNull { byId[it] }
    val rest = members.filter { it.id !in recents }
    return first.map { Tile.One(Subject("member", it.id, it.shownName, it.color, it.glyph)) } +
        subsystems.filter { it.parentId == null }.map { Tile.Folder(it.id, it.name, it.color, model.membership[it.id]?.size ?: 0) } +
        rest.map { Tile.One(Subject("member", it.id, it.shownName, it.color, it.glyph)) }
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val p = LocalChorusPalette.current
    Box(
        modifier.background(p.surface, CircleShape).border(1.dp, p.line, CircleShape).padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        if (value.isEmpty()) Text(placeholder, color = p.ink3, fontSize = 15.sp)
        BasicTextField(
            value, onChange, singleLine = true,
            textStyle = TextStyle(color = p.ink, fontSize = 15.sp),
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
        Avatar(s.glyph, s.color, 44.dp)
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

/** Who is here now (DESIGN.md §4.1). */
@Composable
fun FrontCard(model: Model) {
    val p = LocalChorusPalette.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    val fronting = model.current.filter { it.level == "front" }.mapNotNull { model.subject(it.subjectType, it.subjectId) }
    val others = model.current.filter { it.level != "front" }
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).background(p.surface, RoundedCornerShape(20.dp))
            .border(1.dp, p.line, RoundedCornerShape(20.dp)).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width((52 + 22 * (fronting.size.coerceAtMost(4) - 1).coerceAtLeast(0)).dp)) {
            if (fronting.isEmpty()) Avatar("·", "#A09184", 52.dp)
            fronting.take(4).forEachIndexed { i, s -> Avatar(s.glyph, s.color, 52.dp, Modifier.offset(x = (22 * i).dp)) }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            val first = fronting.firstOrNull()
            Text(
                model.frontLabel(),
                fontSize = 22.sp, fontWeight = FontWeight.Medium,
                color = if (fronting.size == 1 && first != null) tonesOf(first.color).name else p.ink,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            val since = model.since?.let { "for ${ago(it, now)}".replace("for just now", "just now") }
            Text(
                listOfNotNull(if (fronting.isEmpty()) null else "here", since).joinToString(" · ").ifEmpty { "Tap someone below to switch" },
                fontSize = 13.sp, color = p.ink2,
            )
            if (others.isNotEmpty()) {
                val names = others.mapNotNull { e -> model.subject(e.subjectType, e.subjectId)?.let { "${it.name} (${if (e.level == "cocon") "co-con" else e.level})" } }
                Text(names.joinToString(", "), fontSize = 12.sp, color = p.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
