package garden.vayne.chorus.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import garden.vayne.chorus.data.ChatFolders
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R

/** Figma F5.25: put folders in order, hide the built-in ones, make folders of our own (or start from
 * a suggestion), and choose how the chat list looks. Each change is saved as it is made; a folder of
 * our own is edited on its own page and saved there. */
@Composable
internal fun ChatFoldersScreen(model: Model, rows: List<ChatListRow>, onSave: (ChatFolders.Settings) -> Unit,
    onReadPerMember: (Boolean) -> Unit, newId: () -> String, onClose: () -> Unit, modifier: Modifier = Modifier,
    startWith: String? = null) {
    val p = LocalChorusPalette.current
    val settings = model.accountPrefs.chatFolders
    // the folder being edited: an existing id, "new", or "new:<chat id>" to start with one chat
    var editing by rememberSaveable { mutableStateOf(startWith) }
    val open = editing
    val freshId = remember(open) { newId() }
    if (open != null) {
        val existing = settings.custom(open)
        val first = if (open.startsWith("new:")) open.removePrefix("new:") else null
        // opened straight onto a folder (a chat's "New folder…"): done goes back to the chats
        fun done() { if (open == startWith) onClose() else editing = null }
        ChatFolderEditor(existing ?: ChatFolders.Custom(freshId, "", chats = listOfNotNull(first)),
            existing == null, rows, onSave = { folder ->
                val saved = ChatFolders.withCustom(model.accountPrefs.chatFolders, folder)
                onSave(saved); done()
            }, onDelete = { onSave(ChatFolders.without(model.accountPrefs.chatFolders, open)); done() },
            onCancel = { done() }, modifier = modifier)
        return
    }
    BackHandler { onClose() }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }
    // the order while dragging, saved when the finger lifts
    var draft by remember(settings) { mutableStateOf(settings.ordered) }
    val rowHeight = 62.dp
    val rowPx = with(LocalDensity.current) { rowHeight.toPx() }
    fun move(id: String, by: Int) {
        val at = draft.indexOf(id)
        if (at < 0 || at + by !in draft.indices) return
        draft = draft.toMutableList().apply { add(at + by, removeAt(at)) }
    }
    Column(modifier.fillMaxHeight().background(p.bg)) {
        Row(Modifier.fillMaxWidth().background(p.surface).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ChatIconButton(R.drawable.ic_back, "Back to chats", onClose)
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text("Chat folders", color = p.ink, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
                Text("Sort chats and hide what you don’t need", color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { FolderSection("My folders") }
            item {
                Column {
                    for ((index, id) in draft.withIndex()) key(id) {
                        val active = dragging == id
                        val custom = settings.custom(id)
                        val shown = id !in settings.hidden
                        Row(Modifier.fillMaxWidth().height(rowHeight).zIndex(if (active) 1f else 0f)
                            .graphicsLayer { translationY = if (active) dragY else 0f }
                            .background(if (active) p.surface3 else p.bg)
                            .clickable(onClickLabel = if (custom != null) "Edit ${custom.name}" else if (shown) "Hide" else "Show") {
                                if (custom != null) editing = id
                                else onSave(settings.copy(hidden = if (shown) settings.hidden + id else settings.hidden - id))
                            }.padding(start = 16.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            ChatIcon(folderIcon(id), tint = if (shown) p.accent else p.ink3)
                            Column(Modifier.weight(1f)) {
                                Text(ChatFolders.label(settings, id).let { if (id == "all") "All chats" else it }, color = if (shown) p.ink else p.ink3,
                                    fontSize = 15.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(folderSummary(settings, id, rows) + if (shown) "" else " · hidden", color = p.ink3, fontSize = 12.sp,
                                    lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Switch(shown, { on -> onSave(settings.copy(hidden = if (on) settings.hidden - id else settings.hidden + id)) },
                                modifier = Modifier.semantics { contentDescription = "Show ${ChatFolders.label(settings, id)}" })
                            Box(Modifier.size(44.dp).semantics {
                                contentDescription = "Drag ${ChatFolders.label(settings, id)}"
                                customActions = buildList {
                                    if (index > 0) add(CustomAccessibilityAction("Move earlier") { move(id, -1); onSave(settings.copy(order = draft)); true })
                                    if (index < draft.lastIndex) add(CustomAccessibilityAction("Move later") { move(id, 1); onSave(settings.copy(order = draft)); true })
                                }
                            }.pointerInput(id) {
                                detectDragGestures(onDragStart = { dragging = id; dragY = 0f },
                                    onDragEnd = { dragging = null; dragY = 0f; onSave(settings.copy(order = draft)) },
                                    onDragCancel = { dragging = null; dragY = 0f; draft = settings.ordered }) { change, amount ->
                                    change.consume()
                                    dragY += amount.y
                                    while (dragY > rowPx / 2 && draft.indexOf(id) < draft.lastIndex) { move(id, 1); dragY -= rowPx }
                                    while (dragY < -rowPx / 2 && draft.indexOf(id) > 0) { move(id, -1); dragY += rowPx }
                                }
                            }.drawBehind {
                                for (y in listOf(.38f, .5f, .62f)) drawLine(p.ink3, Offset(size.width * .3f, size.height * y),
                                    Offset(size.width * .7f, size.height * y), strokeWidth = 2.dp.toPx())
                            })
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { editing = "new" }.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ChatIcon(R.drawable.ic_plus, tint = p.accent)
                    Text("Create a folder", color = p.accent, fontSize = 15.sp, lineHeight = 20.sp)
                }
            }
            val used = settings.custom.mapNotNull { it.preset }.toSet()
            val suggestions = ChatFolders.SUGGESTIONS.filter { it.preset !in used }
            if (suggestions.isNotEmpty()) {
                item { FolderSection("Suggested") }
                items(suggestions, key = { it.preset }) { s ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 62.dp).padding(start = 16.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        ChatIcon(R.drawable.ic_folder, tint = p.ink3)
                        Column(Modifier.weight(1f)) {
                            Text(s.title, color = p.ink, fontSize = 15.sp, lineHeight = 20.sp)
                            Text(s.subtitle, color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp)
                        }
                        TextButton(onClick = {
                            val ours = rows.filter { it.space.kind == "internal" }.map { it.channel.id to it.title }
                            onSave(ChatFolders.withCustom(settings, ChatFolders.fromSuggestion(newId(), s, ours)))
                        }) { Text("Add") }
                    }
                }
            }
            item { FolderSection("Chat list display") }
            if (!model.isPerson) {
                item { DisplaySwitch("Show who’s here strip", "Who is fronting, above your chats", settings.showHere) {
                    onSave(settings.copy(showHere = it)) } }
                item { DisplaySwitch("Unread counts per member", "Each member keeps their own read position", model.accountPrefs.readPerMember,
                    onReadPerMember) }
            }
            item { DisplaySwitch("Compact rows", "Smaller pictures, more chats on screen", settings.compact) { onSave(settings.copy(compact = it)) } }
        }
    }
}

/** One of our own folders: its name, which kinds of chat it takes whole, its filters and its chats. */
@Composable
private fun ChatFolderEditor(initial: ChatFolders.Custom, isNew: Boolean, rows: List<ChatListRow>, onSave: (ChatFolders.Custom) -> Unit,
    onDelete: () -> Unit, onCancel: () -> Unit, modifier: Modifier) {
    val p = LocalChorusPalette.current
    var folder by remember(initial.id) { mutableStateOf(initial) }
    var query by rememberSaveable { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    BackHandler { onCancel() }
    val kindOf = remember(rows) { rows.associate { it.channel.id to ChatFolders.kindOf(it.channel, it.space) } }
    Column(modifier.fillMaxHeight().background(p.bg)) {
        Row(Modifier.fillMaxWidth().background(p.surface).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Text(if (isNew) "New folder" else "Edit folder", color = p.ink, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            Button(enabled = folder.name.isNotBlank(), onClick = { onSave(folder.copy(name = folder.name.trim())) }) { Text("Save") }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                OutlinedTextField(folder.name, { folder = folder.copy(name = it.take(40)) }, label = { Text("Folder name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp))
            }
            item { FolderSection("Every chat of a kind") }
            for ((kind, title, subtitle) in listOf(Triple("internal", "Internal chats", "Your system’s own channels and DMs"),
                Triple("shared", "Shared spaces", "Spaces with other systems"), Triple("direct", "Direct messages", "One-to-one chats"))) item(key = "kind:$kind") {
                DisplaySwitch(title, subtitle, kind in folder.kinds) { on -> folder = ChatFolders.setKind(folder, kind, on) { kindOf[it] } }
            }
            item { FolderSection("Only show") }
            item { DisplaySwitch("Unread chats", "Unread for whoever is reading", folder.unreadOnly) { folder = folder.copy(unreadOnly = it) } }
            item { DisplaySwitch("Chats that aren’t muted", "Leave muted chats out", folder.hideMuted) { folder = folder.copy(hideMuted = it) } }
            item { FolderSection("Chats (${rows.count { ChatFolders.holds(folder, it.channel.id, kindOf[it.channel.id].orEmpty()) }})") }
            item {
                OutlinedTextField(query, { query = it }, placeholder = { Text("Search chats") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            }
            val q = query.trim()
            val listed = rows.filter { q.isEmpty() || it.title.contains(q, ignoreCase = true) }
                // chosen chats first, then the rest by name
                .sortedWith(compareByDescending<ChatListRow> { ChatFolders.holds(folder, it.channel.id, kindOf[it.channel.id].orEmpty()) }.thenBy { it.title.lowercase() })
            items(listed, key = { "chat:" + it.channel.id }) { row ->
                val kind = kindOf[row.channel.id].orEmpty()
                val inside = ChatFolders.holds(folder, row.channel.id, kind)
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Checkbox) {
                    folder = ChatFolders.setChat(folder, row.channel.id, kind, !inside)
                }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ChatRowDisc(row, 36.dp)
                    Column(Modifier.weight(1f)) {
                        Text(row.title, color = p.ink, fontSize = 15.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(ChatFolders.label(ChatFolders.Settings(), kind) + if (inside && kind in folder.kinds) " · with every ${ChatFolders.label(ChatFolders.Settings(), kind).lowercase()} chat" else "",
                            color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1)
                    }
                    Checkbox(inside, null)
                }
            }
            if (!isNew) item {
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp)) {
                    Text("Delete folder", color = p.danger)
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete ${initial.name.ifBlank { "this folder" }}?") },
        text = { Text("Its chats stay where they are; only the folder goes.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete", color = p.danger) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } })
}

@Composable
private fun FolderSection(title: String) {
    Text(title, color = LocalChorusPalette.current.ink3, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp))
}

@Composable
private fun DisplaySwitch(title: String, subtitle: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth().heightIn(min = 62.dp).clickable(role = Role.Switch) { onChange(!on) }.padding(start = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, color = p.ink, fontSize = 15.sp, lineHeight = 20.sp)
            Text(subtitle, color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Switch(on, null)
    }
}

internal fun folderIcon(id: String): Int = when (id) {
    "all" -> R.drawable.ic_chat
    "internal" -> R.drawable.ic_lock
    "shared" -> R.drawable.ic_globe
    "direct" -> R.drawable.ic_members
    else -> R.drawable.ic_folder
}

/** "Every chat", or what one of our own folders takes: "3 chats · Shared spaces · unread". */
private fun folderSummary(settings: ChatFolders.Settings, id: String, rows: List<ChatListRow>): String = when (id) {
    "all" -> "Every chat"
    "internal" -> "Your system’s own channels and DMs"
    "shared" -> "Spaces with other systems"
    "direct" -> "One-to-one chats"
    else -> settings.custom(id)?.let { f ->
        val count = rows.count { ChatFolders.holds(f, it.channel.id, ChatFolders.kindOf(it.channel, it.space)) }
        listOfNotNull("$count ${if (count == 1) "chat" else "chats"}",
            f.kinds.takeIf { it.isNotEmpty() }?.let { kinds -> ChatFolders.KINDS.filter { it in kinds }.joinToString(", ") { ChatFolders.label(settings, it) } },
            "unread".takeIf { f.unreadOnly }, "not muted".takeIf { f.hideMuted }).joinToString(" · ")
    } ?: ""
}
