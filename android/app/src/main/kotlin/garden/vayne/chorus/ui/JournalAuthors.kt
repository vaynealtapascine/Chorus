package garden.vayne.chorus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import garden.vayne.chorus.data.Member
import garden.vayne.chorus.data.PostCompose
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R

/** Who is writing: the main author's avatar (a co-author peeking out under it) and a "Posting as"
 * chip; tapping the row opens [JournalAuthorsSheet], where co-authors are added and ordered. */
@Composable
internal fun JournalAuthorsPicker(ids: List<String>, mine: List<Member>, enabled: Boolean, onChoose: (List<String>) -> Unit) {
    val p = LocalChorusPalette.current
    var open by remember { mutableStateOf(false) }
    val chosen = ids.mapNotNull { id -> mine.find { it.id == id } }
    val names = ids.map { id -> mine.find { it.id == id }?.shownName ?: "Unavailable member" }
    // a singlet (or a system of one) has no one else to choose
    val choosable = mine.size > 1 || ids.any { id -> mine.none { it.id == id } }
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = enabled && choosable, onClickLabel = "Choose who is writing") { open = true },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(40.dp)) {
            chosen.firstOrNull()?.let { ChatAvatar(it, 40.dp) }
            chosen.getOrNull(1)?.let { second ->
                Box(Modifier.align(Alignment.BottomEnd).offset(x = 6.dp, y = 4.dp).size(22.dp).background(p.bg, CircleShape).padding(2.dp)) {
                    ChatAvatar(second, 18.dp)
                }
            }
        }
        Row(Modifier.weight(1f, fill = false).background(p.accentSoft, CircleShape).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (names.isEmpty()) "Choose who is writing" else "Posting as " + names.joinToString(" & "), color = p.accent,
                style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (choosable) Icon(painterResource(R.drawable.ic_chevron_down), null, Modifier.size(16.dp), tint = p.accent)
        }
    }
    if (open) JournalAuthorsSheet(ids, mine, onChoose) { open = false }
}

/** The chosen authors in order (the first is the main author) with drag handles, then everyone
 * else to add. Changes apply to the draft as they are made. */
@Composable
private fun JournalAuthorsSheet(ids: List<String>, mine: List<Member>, onChoose: (List<String>) -> Unit, onDismiss: () -> Unit) {
    val p = LocalChorusPalette.current
    val latest by rememberUpdatedState(ids)
    val choose by rememberUpdatedState(onChoose)
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var filter by remember { mutableStateOf("") }
    val rowHeight = 56.dp
    val rowPx = with(LocalDensity.current) { rowHeight.toPx() }
    fun move(id: String, by: Int) {
        val current = latest.indexOf(id)
        if (current >= 0 && current + by in latest.indices) choose(PostCompose.moveAuthor(latest, current, current + by))
    }
    ChorusSheet(onDismissRequest = onDismiss, containerColor = p.surface, skipPartiallyExpanded = true) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).padding(bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Who’s writing", color = p.ink, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (ids.size > 1) "The first is the main author. Drag to change the order." else "Add co-authors to write this together.",
                        color = p.ink3, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onDismiss) { Text("Done") }
            }
            Column(Modifier.padding(top = 8.dp)) {
                for ((index, id) in ids.withIndex()) key(id) {
                    val member = mine.find { it.id == id }
                    val active = dragging == id
                    Row(Modifier.fillMaxWidth().height(rowHeight).zIndex(if (active) 1f else 0f)
                        .graphicsLayer { translationY = if (active) dragY else 0f }
                        .background(if (active) p.surface3 else p.surface).padding(start = 20.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (member != null) ChatAvatar(member, 36.dp) else Box(Modifier.size(36.dp))
                        Column(Modifier.weight(1f)) {
                            Text(member?.shownName ?: "Unavailable member", color = member?.let { tonesOf(it.color).name } ?: p.ink3,
                                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (index == 0 && ids.size > 1) Text("Main author", color = p.ink3, style = MaterialTheme.typography.bodySmall)
                        }
                        // the last author stays: a post needs someone writing it
                        if (ids.size > 1 || member == null) IconButton(onClick = { choose(latest.filterNot { it == id }) }) {
                            Icon(painterResource(R.drawable.ic_close), "Remove ${member?.shownName ?: "unavailable member"}",
                                Modifier.size(18.dp), tint = p.ink3)
                        }
                        if (ids.size > 1) Box(Modifier.size(48.dp).semantics {
                            contentDescription = "Drag ${member?.shownName ?: "author"}"
                            customActions = buildList {
                                if (index > 0) add(CustomAccessibilityAction("Move earlier") { move(id, -1); true })
                                if (index < ids.lastIndex) add(CustomAccessibilityAction("Move later") { move(id, 1); true })
                            }
                        }.pointerInput(id) {
                            detectDragGestures(onDragStart = { dragging = id; dragY = 0f },
                                onDragEnd = { dragging = null; dragY = 0f }, onDragCancel = { dragging = null; dragY = 0f }) { change, amount ->
                                change.consume()
                                dragY += amount.y
                                // past half a row the author swaps places, keeping the row under the finger
                                while (dragY > rowPx / 2 && latest.indexOf(id) < latest.lastIndex) { move(id, 1); dragY -= rowPx }
                                while (dragY < -rowPx / 2 && latest.indexOf(id) > 0) { move(id, -1); dragY += rowPx }
                            }
                        }.drawBehind {
                            for (y in listOf(.38f, .5f, .62f)) drawLine(p.ink2, Offset(size.width * .3f, size.height * y),
                                Offset(size.width * .7f, size.height * y), strokeWidth = 2.dp.toPx())
                        })
                    }
                }
            }
            val others = mine.filter { it.id !in ids }
            if (others.isNotEmpty()) {
                HorizontalDivider(color = p.line, modifier = Modifier.padding(top = 8.dp))
                Text("Add a co-author", color = p.ink2, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp))
                if (others.size > 8) OutlinedTextField(filter, { filter = it }, placeholder = { Text("Search members") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp))
                val query = filter.trim()
                val shown = others.filter { query.isEmpty() || it.shownName.contains(query, ignoreCase = true) || it.name.contains(query, ignoreCase = true) }
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    items(shown, key = { it.id }) { member ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClickLabel = "Add as co-author") { choose(latest + member.id) }
                            .padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ChatAvatar(member, 36.dp)
                            Text(member.shownName, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Icon(painterResource(R.drawable.ic_plus), null, Modifier.size(18.dp), tint = p.accent)
                        }
                    }
                }
            }
        }
    }
}
