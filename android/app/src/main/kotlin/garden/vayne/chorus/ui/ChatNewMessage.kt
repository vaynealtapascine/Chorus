package garden.vayne.chorus.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.ChatSpeaker
import garden.vayne.chorus.data.FriendFronts
import garden.vayne.chorus.data.Member
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.NewChat
import garden.vayne.chorus.data.SpaceAccount
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R

/**
 * New message (Figma F5.18): a full page of things to start, our members and our friends. One tap
 * on a person opens that chat as the default speaker (the face at the top changes who); holding a
 * member picks several for a group, holding a friend offers a private chat (D-098).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NewMessagePage(chorus: Chorus, model: Model, connections: List<SpaceAccount>, online: Boolean, busy: Boolean,
    error: String?, onBack: () -> Unit, onInternalGroup: () -> Unit, onSharedSpace: () -> Unit, onFriends: () -> Unit,
    onMembers: (Member, List<Member>) -> Unit, onAccount: (Member, SpaceAccount, Boolean) -> Unit,
    modifier: Modifier = Modifier) {
    val p = LocalChorusPalette.current
    val accountId = chorus.device?.accountId.orEmpty()
    val person = model.isPerson
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var picked by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var chosenSpeaker by rememberSaveable { mutableStateOf<String?>(null) }
    var choosingSpeaker by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf<SpaceAccount?>(null) }
    // a person to write to while nobody speaks yet: asked who is speaking first, then opened
    var waiting by remember { mutableStateOf<((Member) -> Unit)?>(null) }
    BackHandler(picked.isNotEmpty() || searching) { if (picked.isNotEmpty()) picked = emptyList() else { searching = false; query = "" } }
    BackHandler(picked.isEmpty() && !searching, onBack)
    val own = remember(model, accountId) { model.active.filter { it.createdByAccountId == null || it.createdByAccountId == accountId } }
    val defaultSpeaker = remember(model, own) {
        if (person) own.firstOrNull { it.isSelf } ?: own.firstOrNull()
        else runCatching { ChatSpeaker.pick(model, "") }.getOrNull()?.let { id -> own.find { it.id == id } }
    }
    val speaker = own.find { it.id == chosenSpeaker } ?: defaultSpeaker
    val members = remember(model, accountId, speaker?.id, query) { if (person) emptyList() else NewChat.members(model, accountId, speaker?.id, query) }
    val friends = remember(connections, query) { NewChat.friends(connections, query) }
    val views by FriendFronts.views.collectAsState()
    LaunchedEffect(connections.map { it.id }, online) {
        val dev = chorus.device ?: return@LaunchedEffect
        if (online) for (a in connections) runCatching { FriendFronts.refresh(dev, a.id) }
    }
    fun asSpeaker(action: (Member) -> Unit) { speaker?.let(action) ?: run { waiting = action; choosingSpeaker = true } }
    Box(modifier.fillMaxHeight().background(p.bg)) {
        Column(Modifier.fillMaxSize()) {
            SubpageBar(if (picked.isEmpty()) "New message" else "New group", if (picked.isEmpty()) null else "${picked.size} selected",
                { if (picked.isNotEmpty()) picked = emptyList() else onBack() }) {
                if (!person && own.size > 1 && speaker != null) Box(Modifier.size(48.dp).clip(CircleShape)
                    .clickable(onClickLabel = "Change who is speaking") { choosingSpeaker = true }
                    .semantics { contentDescription = "Speaking as ${speaker.shownName}" }, contentAlignment = Alignment.Center) {
                    ChatAvatar(speaker, 32.dp)
                }
                BarIcon(if (searching) R.drawable.ic_close else R.drawable.ic_search, if (searching) "Close search" else "Search") {
                    searching = !searching; if (!searching) query = ""
                }
            }
            if (searching) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                ChorusField(query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    .focusRequester(focus), placeholder = { Text(if (person) "Search friends" else "Search members and friends") },
                    leadingIcon = { Glyph(R.drawable.ic_search, p.ink3, 20.dp) })
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 88.dp)) {
                if (picked.isEmpty() && query.isBlank()) {
                    if (!person) item("group") { NewChatAction(R.drawable.ic_users, "New internal group", onInternalGroup) }
                    item("shared") { NewChatAction(R.drawable.ic_globe, "New shared space", onSharedSpace) }
                    item("another") { NewChatAction(R.drawable.ic_user_plus, if (person) "Message someone new" else "Message another system", onFriends) }
                }
                if (!person) {
                    item("members") { SectionLabel(model.systemName?.takeIf { it.isNotBlank() } ?: "Members", Modifier.padding(horizontal = 12.dp)) }
                    items(members, key = { "m:${it.id}" }) { member ->
                        val line = NewChat.memberLine(model, member)
                        val here = model.current.any { it.subjectType == "member" && it.subjectId == member.id }
                        ContactRow(name = member.shownName, line = line, accentLine = here, enabled = !busy,
                            selected = if (picked.isEmpty()) null else member.id in picked,
                            onClick = {
                                if (picked.isNotEmpty()) picked = NewChat.toggle(picked, member.id)
                                else asSpeaker { s -> onMembers(s, listOf(member)) }
                            },
                            onLongClick = { picked = NewChat.toggle(picked, member.id) }) { ChatAvatar(member, 40.dp) }
                    }
                }
                if (picked.isEmpty()) {
                    item("friends") { SectionLabel(if (person) "Friends" else "Friends’ systems", Modifier.padding(horizontal = 12.dp)) }
                    items(friends, key = { "a:${it.id}" }) { account ->
                        val fronting = views[FriendFronts.key(accountId, account.id)]?.frontNames
                        ContactRow(name = account.shownName, line = NewChat.friendLine(account, fronting), enabled = !busy && online,
                            onClick = { asSpeaker { s -> onAccount(s, account, false) } },
                            onLongClick = if (person) null else ({ held = account })) { AccountAvatar(account, 40.dp) }
                    }
                    if (friends.isEmpty()) item("none") {
                        if (!online) EmptyState(R.drawable.ic_wifi_off, "Offline")
                        else if (query.isBlank()) EmptyState(R.drawable.ic_friends, "No friends yet", action = "Friends", onAction = onFriends)
                    }
                }
            }
            if (error != null) Box(Modifier.padding(16.dp)) { FormNote(error, warning = true) }
        }
        if (picked.isNotEmpty()) NextButton("Start group message", !busy, Modifier.align(Alignment.BottomEnd)) {
            asSpeaker { s -> onMembers(s, own.filter { it.id in picked && it.id != s.id }) }
        }
    }
    if (choosingSpeaker) SendAsSheet(model, "", own, speaker?.id, onChoose = { id ->
        chosenSpeaker = id; choosingSpeaker = false
        picked = picked - id
        val next = waiting; waiting = null
        own.find { it.id == id }?.let { next?.invoke(it) }
    }, onDismiss = { choosingSpeaker = false; waiting = null })
    held?.let { account ->
        MenuDrawer(true, { held = null }, account.shownName, leading = { AccountAvatar(account, 40.dp) }) {
            MenuRow("Message", R.drawable.ic_message, enabled = !busy) { held = null; asSpeaker { s -> onAccount(s, account, false) } }
            speaker?.let { s -> MenuRow("Private chat as ${s.shownName}", R.drawable.ic_lock, enabled = !busy) {
                held = null; onAccount(s, account, true)
            } }
        }
    }
}

/**
 * New shared space (Figma F5.10): pick the friends first (chips of the chosen, a search, round
 * checks), then the round button names it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NewSharedSpacePage(model: Model, connections: List<SpaceAccount>, online: Boolean, busy: Boolean, error: String?,
    onBack: () -> Unit, onFriends: () -> Unit, onCreate: (String, List<String>) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalChorusPalette.current
    var query by rememberSaveable { mutableStateOf("") }
    var chosenIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var naming by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    BackHandler(onBack = onBack)
    val chosen = chosenIds.mapNotNull { id -> connections.find { it.id == id } }
    val shown = remember(connections, query) { NewChat.friends(connections, query) }
    val person = model.isPerson
    Box(modifier.fillMaxHeight().background(p.bg)) {
        Column(Modifier.fillMaxSize()) {
            SubpageBar("New shared space", NewChat.selectedLabel(chosen), onBack)
            Column(Modifier.fillMaxWidth().background(p.surface).padding(horizontal = 16.dp).padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (chosen.isNotEmpty()) FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (account in chosen) Row(Modifier.clip(RoundedCornerShape(20.dp)).background(p.surface2)
                        .clickable(onClickLabel = "Remove ${account.shownName}") { chosenIds = chosenIds - account.id }
                        .heightIn(min = 36.dp).padding(start = 4.dp, end = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AccountAvatar(account, 26.dp)
                        Text(account.shownName, color = p.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Glyph(R.drawable.ic_close, p.ink2, 14.dp)
                    }
                }
                ChorusField(query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    placeholder = { Text(if (person) "Search friends" else "Search friends’ systems") },
                    leadingIcon = { Glyph(R.drawable.ic_search, p.ink3, 20.dp) })
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 88.dp)) {
                item("label") { SectionLabel(if (person) "Friends" else "Friends’ systems", Modifier.padding(horizontal = 12.dp)) }
                items(shown, key = { it.id }) { account ->
                    ContactRow(name = account.shownName, line = if (account.isSystem) "a system" else "one person", enabled = !busy,
                        selected = account.id in chosenIds, onClick = { chosenIds = NewChat.toggle(chosenIds, account.id) }) {
                        AccountAvatar(account, 40.dp)
                    }
                }
                if (shown.isEmpty()) item("none") {
                    if (!online) EmptyState(R.drawable.ic_wifi_off, "Offline")
                    else if (query.isBlank()) EmptyState(R.drawable.ic_friends, "No friends yet", action = "Friends", onAction = onFriends)
                }
            }
            if (error != null) Box(Modifier.padding(16.dp)) { FormNote(error, warning = true) }
        }
        if (chosen.isNotEmpty()) NextButton("Name the space", !busy, Modifier.align(Alignment.BottomEnd)) {
            if (name.isBlank()) name = NewChat.spaceName(model.systemName, chosen)
            naming = true
        }
    }
    if (naming) Drawer({ naming = false }, "Name", NewChat.selectedLabel(chosen), dismissible = !busy, footer = {
        DrawerButton("Create", { onCreate(name, chosenIds) }, enabled = name.isNotBlank() && chosen.isNotEmpty() && online, busy = busy)
    }) {
        ChorusField(name, { name = it.take(80) }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Name") })
    }
}

/** A thing to start (F5.18): a soft disc with its icon and a label in the accent. */
@Composable
private fun NewChatAction(icon: Int, label: String, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).heightIn(min = 60.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(40.dp).background(p.accentSoft, CircleShape), contentAlignment = Alignment.Center) { Glyph(icon, p.accent, 20.dp) }
        Text(label, color = p.accent, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

/** One person (F5.18/F5.10): a face, the name and a line; [selected] non-null adds the round check. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactRow(name: String, line: String?, enabled: Boolean, accentLine: Boolean = false, selected: Boolean? = null,
    onClick: () -> Unit, onLongClick: (() -> Unit)? = null, face: @Composable () -> Unit) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth().combinedClickable(enabled = enabled, role = if (selected != null) Role.Checkbox else Role.Button,
        onClick = onClick, onLongClick = onLongClick)
        .then(if (selected != null) Modifier.semantics { this.selected = selected } else Modifier)
        .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        face()
        Column(Modifier.weight(1f)) {
            Text(name, color = if (enabled) p.ink else p.ink3, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            line?.let { Text(it, color = if (accentLine) p.accent else p.ink3, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (selected != null) Box(Modifier.size(26.dp).then(if (selected) Modifier.background(p.accent, CircleShape) else Modifier.border(1.5.dp, p.ink3, CircleShape)),
            contentAlignment = Alignment.Center) { if (selected) Glyph(R.drawable.ic_check, p.onAccent, 16.dp) }
    }
}

/** The round next button (F5.10). */
@Composable
private fun NextButton(description: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    Box(modifier.padding(16.dp).size(56.dp).clip(CircleShape).background(if (enabled) p.accent else p.surface3)
        .clickable(enabled = enabled, onClickLabel = description, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Glyph(R.drawable.ic_chevron_right, if (enabled) p.onAccent else p.ink3, 24.dp)
    }
}
