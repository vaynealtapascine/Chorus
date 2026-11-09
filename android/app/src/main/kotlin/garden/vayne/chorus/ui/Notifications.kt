package garden.vayne.chorus.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.*
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Where a notification leads: a post, a chat message, Friends, front history or settings. */
internal class InboxRoutes(val onPost: (String) -> Unit, val onChat: (String, String?) -> Unit,
    /** Friends, opened at a page: its route stack (e.g. "friend:<account>", then "notify:<follow>"). */
    val onFriends: (List<String>) -> Unit, val onHistory: () -> Unit, val onReview: () -> Unit, val onSettings: () -> Unit)

/** The member whose read marks the screen shows: whoever is fronting (a person: their own member). */
internal fun inboxReader(model: Model, chosen: String?): Member? =
    model.members.find { it.id == chosen } ?: model.members.firstOrNull { it.isSelf }.takeIf { model.isPerson }
        ?: Reply.speaker(model) ?: model.active.firstOrNull()

/**
 * Notifications (D-127, Figma F11.1–F11.4): chips (All · Mentions · Friends · Our system · App),
 * then *Needs you*, *Today* and *Earlier*. Unread rows are tinted with a dot; a tap marks the row
 * read and opens it; holding it offers more. Read marks belong to the member reading ("Read as");
 * the sliders choose what shows here. Everything listed already reached this account elsewhere.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Notifications(chorus: Chorus, model: Model, routes: InboxRoutes) {
    val p = LocalChorusPalette.current
    val scope = rememberCoroutineScope()
    val account = chorus.device?.accountId
    val all by Inbox.state.collectAsState()
    val items = all?.takeIf { it.first == account }?.second.orEmpty()
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(chorus.device?.session, refresh) {
        val dev = chorus.device ?: return@LaunchedEffect
        loading = true
        try { Inbox.refresh(dev, chorus.model.value); error = null }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { error = "Offline · showing saved" }
        finally { loading = false }
    }
    var chosenReader by rememberSaveable { mutableStateOf<String?>(null) }
    val reader = inboxReader(model, chosenReader)
    val read = reader?.let { model.accountPrefs.inboxRead[it.id] } ?: InboxRead()
    val shows = model.accountPrefs.inboxShows
    var chip by rememberSaveable { mutableStateOf("all") }
    val chips = Inbox.chips(model.isPerson)
    if (chips.none { it.first == chip }) chip = "all"
    val shown = Inbox.visible(items, chip, shows)
    var readAs by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf<InboxItem?>(null) }
    var accepting by remember { mutableStateOf<InboxItem?>(null) }
    // who just got accepted, while we may still follow them back (D-129)
    var offerBack by remember { mutableStateOf<InboxItem?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    fun save(next: InboxRead) {
        val who = reader ?: return
        scope.launch {
            try { chorus.create("pref.set", null, Inbox.readPayload(who.id, next)) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { notice = "Couldn’t save that. Try again." }
        }
    }
    fun act(failure: String, block: suspend () -> Unit) {
        scope.launch {
            try { block(); refresh++ }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { notice = failure }
        }
    }
    suspend fun followsAlready(item: InboxItem): Boolean = runCatching {
        PeopleApi.list(checkNotNull(chorus.device)).following.any { it.account.id == item.accountId }
    }.getOrDefault(true)
    fun open(item: InboxItem) {
        if (!item.needsYou) save(read.mark(item))
        when {
            item.kind == "review" -> routes.onReview()
            item.postId != null -> routes.onPost(item.postId)
            item.channelId != null -> routes.onChat(item.channelId, item.messageId)
            item.kind == "own_switch" -> routes.onHistory()
            item.kind == "export_ready" -> routes.onSettings()
            item.accountId != null -> routes.onFriends(listOf("friend:${item.accountId}"))
        }
    }

    Column(Modifier.fillMaxSize().background(p.bg)) {
        // F11.1 header: the reader's avatar opens Read as; mark all read; what shows here
        Row(Modifier.fillMaxWidth().background(p.surface).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            reader?.let { r -> Box(Modifier.clip(CircleShape).clickable(enabled = !model.isPerson, onClickLabel = "Read as another member") { readAs = true }) { ChatAvatar(r, 36.dp) } }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text("Notifications", style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp, lineHeight = 26.sp), color = p.ink)
                Text(if (model.isPerson) reader?.let { "${it.shownName} · just me" } ?: "Just me" else "Reading as ${reader?.shownName ?: "nobody"}",
                    color = p.ink3, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = if (model.isPerson) Modifier else Modifier.clickable(onClickLabel = "Read as another member") { readAs = true })
            }
            IconButton(onClick = { save(InboxRead(maxOf(read.until, items.filter { !it.needsYou }.maxOfOrNull { it.at } ?: 0))) },
                enabled = Inbox.unread(items, read, shows) > 0) { ChatIcon(R.drawable.ic_check, "Mark all read") }
            ChatIconButton(R.drawable.ic_sliders, "What shows here") { settings = true }
        }
        Row(Modifier.fillMaxWidth().background(p.surface).horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((id, label) in chips) InboxChip(label, Inbox.unread(items, read, shows, id), chip == id) { chip = id }
        }
        HorizontalDivider(color = p.line)
        if (loading && items.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
        val today = remember(items) { java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            error?.let { item(key = "error") { Text(it, color = p.ink2, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp)) } }
            notice?.let { item(key = "notice") { Text(it, color = p.danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp)) } }
            if (shown.isEmpty() && !loading) item(key = "empty") {
                EmptyState(R.drawable.ic_bell, if (chip == "all") "All caught up" else "Nothing yet")
            }
            var last: String? = null
            for (item in shown) {
                val section = Inbox.section(item, today)
                if (section != last) {
                    last = section
                    item(key = "section:$section") {
                        Text(section, color = p.accent, style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.fillMaxWidth().background(p.bg).padding(start = 16.dp, top = 14.dp, bottom = 6.dp))
                    }
                }
                item(key = item.id) {
                    InboxRow(item, unread = item.needsYou || !read.read(item), model, onOpen = { open(item) }, onHold = { held = item },
                        onAccept = when (item.kind) {
                            "follow_request" -> ({ if (model.isPerson) act("Couldn’t accept. Try again.") {
                                chorus.create("follow.accept", item.followId!!, JSONObject())
                                offerBack = item.takeUnless { followsAlready(item) } } else accepting = item })
                            "alert_ask" -> ({ act("Couldn’t allow. Try again.") {
                                PeopleApi.decidePostAlert(checkNotNull(chorus.device), item.memberId.orEmpty(), item.accountId.orEmpty(), true) } })
                            else -> null
                        },
                        onDecline = when (item.kind) {
                            "follow_request" -> ({ act("Couldn’t decline. Try again.") { chorus.create("follow.end", item.followId!!, JSONObject()) } })
                            "alert_ask" -> ({ act("Couldn’t decline. Try again.") {
                                PeopleApi.decidePostAlert(checkNotNull(chorus.device), item.memberId.orEmpty(), item.accountId.orEmpty(), false) } })
                            else -> null
                        },
                        onReview = if (item.kind == "review") routes.onReview else null)
                }
            }
        }
    }

    // F11.3: hold one for options
    held?.let { item ->
        val friend = item.accountId?.takeIf { item.category == "friends" && item.kind != "alert_ask" }
        val follow = friend?.let { id -> remember(id) { mutableStateOf<FollowInfo?>(null) } }
        if (follow != null) LaunchedEffect(friend) {
            follow.value = runCatching { PeopleApi.list(checkNotNull(chorus.device)).following.find { it.account.id == friend && it.status == "active" } }.getOrNull()
        }
        MenuDrawer(true, { held = null }, item.title, listOfNotNull(categoryLabel(item), compactAgo(item.at)).joinToString(" · ")) {
            if (!item.needsYou) {
                val isRead = read.read(item)
                MenuRow(if (isRead) "Mark as unread" else "Mark as read", R.drawable.ic_check) {
                    held = null; save(if (isRead) read.unmark(item, items) else read.mark(item))
                }
            }
            MenuRow(if (item.postId != null) "Open post" else if (item.channelId != null) "Open chat"
                else if (item.accountId != null && item.actor != null) "Open ${item.actor}" else "Open", R.drawable.ic_chevron_right) { held = null; open(item) }
            follow?.value?.let { f ->
                val until = System.currentTimeMillis() + 60 * 60 * 1000
                MenuRow("Quiet ${f.account.shownName} for an hour", R.drawable.ic_bell_off,
                    "Their switches wait until ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(until))}") {
                    held = null
                    act("Couldn’t quiet them. Try again.") {
                        PeopleApi.setPrefs(checkNotNull(chorus.device), f.id, JSONObject(f.prefs.toString()).put("mute_until", until))
                    }
                }
                MenuRow("Notifications from ${f.account.shownName}", R.drawable.ic_bell) {
                    held = null; routes.onFriends(listOf("friend:${f.account.id}", "notify:${f.id}"))
                }
            }
            item.shows?.let { key ->
                val label = Inbox.SHOWS.first { it.first == key }.second
                MenuRow("Hide ${label.lowercase()} here", R.drawable.ic_eye_off) {
                    held = null
                    scope.launch { runCatching { chorus.create("pref.set", null, Inbox.showsPayload(shows, key, false)) } }
                }
            }
        }
    }

    // F11.4: each member keeps their own unread marks; they never leave the account
    MenuDrawer(readAs, { readAs = false }, "Read as") {
        for (m in model.active.filter { it.createdByAccountId == null || it.createdByAccountId == account }) {
            val n = Inbox.unread(items, model.accountPrefs.inboxRead[m.id] ?: InboxRead(), shows)
            MenuRow(m.shownName, subtitle = if (n > 0) "$n unread" else "caught up", selected = m.id == reader?.id,
                leading = { ChatAvatar(m, 32.dp) }) { readAs = false; chosenReader = m.id }
        }
    }

    // F11.5's side panel as a drawer: what shows here; devices still follow notification settings
    MenuDrawer(settings, { settings = false }, "What shows here") {
        // how this phone delivers them lives in settings (F7.13)
        MenuRow("This device", R.drawable.ic_monitor) { settings = false; routes.onSettings() }
        MenuDivider()
        for ((key, label) in Inbox.SHOWS.filter { !model.isPerson || it.first != "own_switches" }) {
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = p.ink, modifier = Modifier.weight(1f))
                ChorusSwitch(Inbox.shows(shows, key), { on -> scope.launch { runCatching { chorus.create("pref.set", null, Inbox.showsPayload(shows, key, on)) } } })
            }
        }
        MenuRow("Notification settings", R.drawable.ic_sliders) { settings = false; routes.onSettings() }
    }

    // the same drawer as Friends › Requests (F7.7): how much they hear and their bucket
    accepting?.let { item ->
        val follow = FollowInfo(item.followId!!, garden.vayne.chorus.data.SpaceAccount(item.accountId.orEmpty(), null, item.actor, null), "requested")
        AcceptDrawer(model, follow, onDismiss = { accepting = null },
            onDecline = { accepting = null; act("Couldn’t decline. Try again.") { chorus.create("follow.end", follow.id, JSONObject()) } },
            onAccept = { preset, bucket -> accepting = null; act("Couldn’t accept. Try again.") {
                acceptFollow(chorus, model, follow, preset, bucket)
                offerBack = item.takeUnless { followsAlready(item) }
            } })
    }
    offerBack?.let { item -> FollowBackDrawer(item.actor ?: "them", onDismiss = { offerBack = null }) {
        offerBack = null
        act("Couldn’t send the request.") { PeopleApi.request(checkNotNull(chorus.device), item.accountId.orEmpty()) }
    } }
}

private fun categoryLabel(item: InboxItem): String? = when (item.category) {
    "mentions" -> "Mentions"; "friends" -> "Friends"; "system" -> "Our system"; "app" -> "App"; else -> null
}

@Composable
private fun InboxChip(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    Row(Modifier.clip(CircleShape).background(if (selected) p.accent else p.surface2).clickable(onClick = onClick)
        .heightIn(min = 32.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = if (selected) p.onAccent else p.ink, style = MaterialTheme.typography.labelLarge)
        Text(count.toString(), color = if (selected) p.onAccent.copy(alpha = .8f) else p.ink3, style = MaterialTheme.typography.labelMedium)
    }
}

/** The kind's icon and its tint, in a 40 dp disc beside the row. */
@Composable
private fun KindBadge(item: InboxItem) {
    val p = LocalChorusPalette.current
    val (icon, glyph, tint) = when (item.kind) {
        "mention" -> Triple(null, "@", p.accent)
        "reply", "post_reply" -> Triple(R.drawable.ic_reply, null, p.accent)
        "poll_nudge" -> Triple(R.drawable.ic_poll, null, p.accent)
        "dm", "message", "member_dm" -> Triple(R.drawable.ic_message, null, p.accent)
        "switch", "own_switch" -> Triple(R.drawable.ic_switch_arrows, null, p.accent)
        "reaction" -> Triple(R.drawable.ic_heart, null, p.danger)
        "repost" -> Triple(R.drawable.ic_repost, null, p.ok)
        "quote" -> Triple(R.drawable.ic_format_quote, null, p.ok)
        "post" -> Triple(R.drawable.ic_book, null, p.ink2)
        "follow_request" -> Triple(R.drawable.ic_members, null, p.warn)
        "alert_ask", "alert_request", "alert_accepted" -> Triple(R.drawable.ic_bell, null, p.warn)
        "review" -> Triple(null, "!", p.warn)
        "export_ready" -> Triple(R.drawable.ic_file, null, p.ink2)
        else -> Triple(R.drawable.ic_bell, null, p.ink2)
    }
    Box(Modifier.size(40.dp).clip(CircleShape).background(tint.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
        if (icon != null) ChatIcon(icon, null, tint) else Text(glyph.orEmpty(), color = tint, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InboxRow(item: InboxItem, unread: Boolean, model: Model, onOpen: () -> Unit, onHold: () -> Unit,
    onAccept: (() -> Unit)?, onDecline: (() -> Unit)?, onReview: (() -> Unit)?) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth().background(if (unread) p.accentSoft.copy(alpha = .45f) else Color.Transparent)
        .combinedClickable(onClick = onOpen, onLongClick = onHold, onClickLabel = "Open", onLongClickLabel = "More options")
        .padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        KindBadge(item)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item.actor?.let { who ->
                Avatar(who.take(1).uppercase(), item.color ?: "#A09184", 24.dp, ring = false, avatarBlob = item.avatarBlob)
            }
            Text(buildAnnotatedString {
                val who = item.actor
                if (who != null && item.title.startsWith(who)) {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = p.ink)) { append(who) }
                    append(item.title.removePrefix(who))
                } else withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = p.ink)) { append(item.title) }
            }, color = p.ink2, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp))
            item.detail?.let { Text(it, color = p.ink3, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            item.quote?.let {
                Text(it, color = p.ink2, style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp, lineHeight = 20.sp), maxLines = 3,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(p.surface2)
                        .padding(horizontal = 12.dp, vertical = 8.dp))
            }
            if (onAccept != null || onDecline != null || onReview != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                onAccept?.let { SmallButton(if (item.kind == "follow_request" && !model.isPerson) "Accept…" else if (item.kind == "alert_ask") "Allow" else "Accept",
                    primary = true, onClick = it) }
                onDecline?.let { SmallButton("Decline", onClick = it) }
                onReview?.let { SmallButton("Review", primary = true, onClick = it) }
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!item.untimed) Text(compactAgo(item.at), color = p.ink3, style = MaterialTheme.typography.bodySmall)
            if (unread) Box(Modifier.size(8.dp).clip(CircleShape).background(p.accent))
        }
    }
}
