package garden.vayne.chorus.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.ChatOrganize
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.Trash
import garden.vayne.chorus.data.TrashItem
import garden.vayne.chorus.data.TrashKind
import garden.vayne.chorus.designsystem.ChorusAccent
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * F9.5 Trash: what this account deleted or made, newest first, *This week* then *Earlier*, each with
 * Restore. No Empty trash: the apps never purge (D-053).
 */
@Composable
internal fun TrashScreen(chorus: Chorus, model: Model, onBack: () -> Unit) {
    val p = LocalChorusPalette.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val busy = remember { mutableStateMapOf<String, Boolean>() }
    var error by remember { mutableStateOf<String?>(null) }
    BackHandler(searching) { searching = false; query = "" }
    // a private chat that isn't ours to see right now keeps its deleted messages out too (D-097, D-098)
    val shown = remember(model) { ChatOrganize.shown(model) }
    val all = remember(model) { model.trash.filter { it.channelId == null || shown(it.channelId) } }
    val needle = query.trim().lowercase()
    val rows = all.filter { (tab == 0 || Trash.tab(it.kind) == tab) && (needle.isEmpty() || needle in it.title.lowercase() ||
        it.deletedBy?.lowercase()?.contains(needle) == true) }
    val now = remember(model) { System.currentTimeMillis() }
    Column(Modifier.fillMaxSize().background(p.bg)) {
        SubpageBar("Trash", if (all.isEmpty()) null else "${all.size} item${if (all.size == 1) "" else "s"}", onBack) {
            BarIcon(if (searching) R.drawable.ic_close else R.drawable.ic_search, if (searching) "Close search" else "Search", p.ink) {
                searching = !searching; if (!searching) query = ""
            }
        }
        UnderlineTabs(listOf("All" to 0, "Messages" to 0, "Posts" to 0, "Members" to 0), tab, onPage = true) { tab = it }
        if (searching) Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            ChorusField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Search Trash") })
        }
        error?.let { Text(it, color = p.danger, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Glyph(R.drawable.ic_delete, p.ink3, 32.dp)
                    Text(if (all.isEmpty()) "Trash is empty" else "Nothing here", color = p.ink3, fontSize = 15.sp)
                }
            }
        } else {
        val (week, earlier) = rows.partition { Trash.thisWeek(it, now) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            for ((label, part) in listOf("This week" to week, "Earlier" to earlier)) {
                if (part.isEmpty()) continue
                item(key = "label:$label") { SectionLabel(label, Modifier.padding(horizontal = 12.dp)) }
                items(part, key = { "${it.kind}:${it.id}" }) { item ->
                    TrashRow(item, now, busy[item.id] == true) {
                        busy[item.id] = true; error = null
                        scope.launch {
                            try { chorus.create(item.kind.op, item.id, JSONObject(), scope = item.scope) }
                            catch (e: Exception) { error = e.message ?: "Could not restore this." }
                            finally { busy.remove(item.id) }
                        }
                    }
                }
            }
            item(key = "note") {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(p.surface2).padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Glyph(R.drawable.ic_restore, p.ink3, 16.dp, modifier = Modifier.padding(top = 1.dp))
                    Text("Restoring puts it back where it was, with its replies and reactions.", color = p.ink3, fontSize = 13.sp, lineHeight = 17.sp)
                }
            }
        }
        }
    }
}

/** A 60 dp row: tinted 40 dp tile, title, "Deleted by … · when", and the Restore pill (F9.5). */
@Composable
private fun TrashRow(item: TrashItem, now: Long, busy: Boolean, onRestore: () -> Unit) {
    val p = LocalChorusPalette.current
    val (icon, tone) = tile(item)
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(tone.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
            Glyph(icon, tone, 20.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(item.title, color = p.ink, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text(Trash.detail(item, now), color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val enabled = !busy && item.scope.isNotEmpty()
        Row(Modifier.heightIn(min = 33.dp).clip(CircleShape).background(p.surface2)
            .clickable(enabled = enabled, role = Role.Button, onClick = onRestore).padding(start = 12.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Glyph(R.drawable.ic_restore, if (enabled) p.ink else p.ink3, 16.dp)
            Text("Restore", color = if (enabled) p.ink else p.ink3, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** Message blue, post coral, member violet, photos amber (F9.5); groups and channels take their kin's. */
@Composable
private fun tile(item: TrashItem): Pair<Int, Color> {
    val p = LocalChorusPalette.current
    val dark = p.bg.luminance() < .5f
    val sky = if (dark) ChorusAccent.Sky.dark.accent else ChorusAccent.Sky.light.accent
    val violet = if (dark) ChorusAccent.Lavender.dark.accent else ChorusAccent.Lavender.light.accent
    return when (item.kind) {
        TrashKind.Message -> if (item.photos > 0) R.drawable.ic_image to p.sun else R.drawable.ic_message to sky
        TrashKind.Channel -> R.drawable.ic_hash to sky
        TrashKind.Post -> R.drawable.ic_book to p.danger
        TrashKind.Member -> R.drawable.ic_user to violet
        TrashKind.Group -> R.drawable.ic_users to violet
    }
}
