package garden.vayne.chorus.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.Blobs
import garden.vayne.chorus.data.ChatAttachment
import garden.vayne.chorus.data.FrontSpan
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.JournalPresentation
import garden.vayne.chorus.data.JournalPost
import garden.vayne.chorus.data.Member
import garden.vayne.chorus.data.MemberCustomization
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.ProfileApi
import garden.vayne.chorus.data.ProfileBundle
import garden.vayne.chorus.data.ProfileHighlights
import garden.vayne.chorus.data.ProfileMetrics
import garden.vayne.chorus.data.ProfilePresentation
import garden.vayne.chorus.data.Subsystems
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import garden.vayne.chorus.data.Reply
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private val ProfileTabs = listOf("Posts", "Replies", "Journal", "Media", "Highlights", "About")

/** Own member profile (Figma F6.1): banner with the avatar overlapping it, Post / Edit profile,
 * name, pronouns and sigils, where they are in the front right now, counts, bio, groups and the
 * profile's audience; then sticky tabs. Pinning and highlighting live in each post's `⋯`. Local
 * details stay visible offline; the server adds readable outside highlights. A post opens as its
 * thread ([onOpenPost]), as in the timeline. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MemberProfile(chorus: Chorus, model: Model, memberId: String,
    onClose: () -> Unit, onWrite: () -> Unit, onReply: (String) -> Unit,
    closeLabel: String = "Journal", writeLabel: String? = null, onOpenProfile: ((String) -> Unit)? = null,
    onOpenPost: ((String) -> Unit)? = null,
    /** Opens the chat (space, channel) with this member: the Message pill (F6.1). */
    onMessage: ((String, String) -> Unit)? = null) {
    val p = LocalChorusPalette.current
    val dev = chorus.device
    val actions = rememberCoroutineScope()
    val member = model.member(memberId)?.takeIf { it.createdByAccountId == null || it.createdByAccountId == dev?.accountId }
    var tab by rememberSaveable(memberId) { mutableStateOf("Posts") }
    var editing by rememberSaveable(memberId) { mutableStateOf(false) }
    if (editing && member != null) {
        MemberEditor(chorus, model, member) { editing = false }
        return
    }
    var bundle by remember(memberId, dev?.accountId) { mutableStateOf<ProfileBundle?>(null) }
    var error by remember(memberId, dev?.accountId) { mutableStateOf<String?>(null) }
    var busy by remember(memberId) { mutableStateOf(false) }
    var removedHighlights by remember(memberId) { mutableStateOf<Set<String>>(emptySet()) }
    var subsystemOpen by remember(memberId) { mutableStateOf(false) }

    LaunchedEffect(dev?.session, memberId) {
        bundle = null
        if (dev != null && member != null) {
            try { bundle = ProfileApi.load(dev, memberId); error = null }
            // offline: the connection banner says so; the profile shows what this device has
            catch (e: Exception) { error = null }
        }
    }

    if (member == null) {
        Column(Modifier.fillMaxSize().background(p.bg).padding(16.dp)) {
            TextButton(onClick = onClose) { Text("‹ $closeLabel") }
            Text("Member unavailable", color = p.ink2)
        }
        return
    }

    val ownPosts = model.posts.filter { memberId in it.authors && it.repostOf == null }
    val pinned = model.posts.find { it.id == member.pinnedPostId }
    val highlightedIds = model.highlights[memberId].orEmpty()
    fun write(failure: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        actions.launch {
            try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: failure }
            finally { busy = false }
        }
    }
    fun setPinned(postId: String?) = write(if (postId == null) "Could not unpin this post." else "Could not pin this post.") {
        chorus.create("member.set", memberId, org.json.JSONObject().put("pinned_post_id", postId ?: org.json.JSONObject.NULL))
    }
    fun setHighlight(postId: String, add: Boolean) = write("Could not update this highlight.") {
        chorus.create(if (add) "highlight.add" else "highlight.remove", memberId, ProfileHighlights.payload(memberId, postId))
        removedHighlights = if (add) removedHighlights - postId else removedHighlights + postId
    }
    // pinning and highlighting are this profile's own extras on each card's ⋯
    fun menu(post: JournalPost): List<Pair<String, () -> Unit>> = listOfNotNull(
        if (post.id == member.pinnedPostId) "Unpin from profile" to { setPinned(null) }
        else ("Pin to profile" to { setPinned(post.id) }).takeIf { post.replyTo == null },
        if (post.id in highlightedIds) "Remove highlight" to { setHighlight(post.id, false) }
        else "Highlight on profile" to { setHighlight(post.id, true) })

    // Message: the same conversation each time, as whoever is speaking (never as the member themself)
    val canMessage = onMessage != null && !model.isPerson && member.archived.not() && model.active.any { it.id != memberId }
    fun message() = write("Could not start the message.") {
        val latest = chorus.model.value
        val home = latest.spaces.firstOrNull { it.kind == "internal" } ?: throw IllegalStateException("Your internal space is not available yet.")
        val speaker = Reply.speaker(latest)?.takeIf { it.id != memberId } ?: latest.active.first { it.id != memberId }
        val participants = garden.vayne.chorus.data.MemberDms.selected(latest.active, listOf(speaker.id, memberId), dev?.accountId.orEmpty())
        val target = garden.vayne.chorus.data.MemberDms.existing(latest.channels, home.id, participants.map { it.id })?.id
            ?: chorus.newId().also { id ->
                chorus.create("channel.create", id, garden.vayne.chorus.data.MemberDms.payload(home.id, participants), scope = "space:${home.id}")
            }
        kotlinx.coroutines.withTimeout(15_000) { chorus.model.first { next -> next.channels.any { it.id == target && it.spaceId == home.id } } }
        onMessage?.invoke(home.id, target)
    }
    val openThread = onOpenPost?.let { open -> { post: JournalPost -> open(post.id) } }
    // front time and "last fronted" from Body's named-front history when the account has one (D-080)
    val historyRevision by chorus.frontHistoryRevision.collectAsState()
    val spans by produceState(model.frontSpans, model.namedFronts != null, historyRevision) {
        value = if (model.namedFronts == null) model.frontSpans
            else runCatching { chorus.frontHistory() }.getOrNull()?.let(ProfileMetrics::spans) ?: model.frontSpans
    }
    val list = rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize().background(p.bg), state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "hero") {
            ProfileHero(member, chorus, closeLabel, onClose, writeLabel, onWrite, onMessage = if (canMessage) ({ message() }) else null, onEdit = { editing = true },
                subsystemLabel = if (model.isPerson) null else if (Subsystems.groupOf(model, memberId) != null) "Who’s inside"
                    else "Make a subsystem", onSubsystem = { subsystemOpen = true })
        }
        item(key = "details") {
            ProfileDetails(member, model, spans, error, onTab = { tab = it }, onInside = { subsystemOpen = true }, onOpenProfile = onOpenProfile)
        }
        stickyHeader(key = "tabs") { ProfileTabRow(tab) { tab = it } }
        fun posts(shown: List<JournalPost>, empty: String, icon: Int) {
            if (shown.isEmpty()) item(key = "empty:$tab") {
                ProfileEmpty(icon, empty, Modifier.animateItem())
            }
            for (post in shown) item(key = "$tab:${post.id}") {
                Column(Modifier.animateItem()) {
                    JournalPostCard(post, model, chorus, onReply = { onReply(it.id) }, onThread = openThread, onOpenPost = onOpenPost, extraMenu = menu(post))
                }
            }
        }
        when (tab) {
            "Posts" -> {
                if (pinned != null) item(key = "pinned:${pinned.id}") {
                    Column(Modifier.animateItem()) {
                        PinnedHeader()
                        JournalPostCard(pinned, model, chorus, onReply = { onReply(it.id) }, onThread = openThread, onOpenPost = onOpenPost,
                            extraMenu = menu(pinned))
                    }
                }
                posts(ownPosts.filter { it.replyTo == null && it.kind != "entry" && it.id != pinned?.id }
                    .sortedByDescending { it.occurredAt }, "No posts yet", R.drawable.ic_journal)
            }
            "Replies" -> posts(ownPosts.filter { it.replyTo != null }.sortedByDescending { it.occurredAt }, "No replies yet", R.drawable.ic_reply)
            "Journal" -> posts(ownPosts.filter { it.kind == "entry" && it.replyTo == null }.sortedByDescending { it.occurredAt },
                "No entries yet", R.drawable.ic_book)
            "Media" -> {
                val media = ownPosts.sortedByDescending { it.occurredAt }.flatMap { post ->
                    post.attachments.filter { it.mime.startsWith("image/") }.map { post to it }
                }
                if (media.isEmpty()) item(key = "empty:media") {
                    ProfileEmpty(R.drawable.ic_image, "No photos", Modifier.animateItem())
                }
                else for ((row, cells) in media.chunked(3).withIndex()) item(key = "media:$row") {
                    MediaRow(cells, chorus, member.shownName, Modifier.animateItem())
                }
            }
            "Highlights" -> {
                val local = model.posts.filter { it.id in highlightedIds }
                val foreign = bundle?.highlights.orEmpty().filter { post ->
                    model.posts.none { it.id == post.id } && post.id !in removedHighlights
                }
                if (local.isEmpty() && foreign.isEmpty()) item(key = "empty:highlights") {
                    ProfileEmpty(R.drawable.ic_bookmark, "No highlights", Modifier.animateItem())
                }
                for (post in local) item(key = "highlight:${post.id}") {
                    Column(Modifier.animateItem()) {
                        JournalPostCard(post, model, chorus, onReply = { onReply(it.id) }, onThread = openThread, onOpenPost = onOpenPost, extraMenu = menu(post))
                    }
                }
                for (post in foreign) item(key = "remote-highlight:${post.id}") {
                    Column(Modifier.animateItem()) {
                        SharedPostPreview(post, member.shownName, chorus, model, onThread = onOpenPost?.let { open -> { open(it.id) } })
                        TextButton(enabled = !busy, onClick = { setHighlight(post.id, false) },
                            modifier = Modifier.padding(start = 60.dp)) { Text("Remove highlight") }
                        HorizontalDivider(color = p.line, thickness = .5.dp)
                    }
                }
            }
            else -> item(key = "about") {
                ProfileAbout(chorus, model, member, bundle, spans, Modifier.animateItem())
            }
        }
    }
    if (subsystemOpen) SubsystemSheet(chorus, model, member) { subsystemOpen = false }
}

/** Our member's hero: the shared [ProfileHeroFrame] with Post / Edit profile and the `⋯` menu. */
@Composable
private fun ProfileHero(member: Member, chorus: Chorus, closeLabel: String, onClose: () -> Unit,
    writeLabel: String?, onWrite: () -> Unit, onMessage: (() -> Unit)?, onEdit: () -> Unit, subsystemLabel: String?, onSubsystem: () -> Unit) {
    val p = LocalChorusPalette.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    ProfileHeroFrame(chorus, member.bannerBlob, member.color, member.glyph, member.avatarBlob, closeLabel, onClose,
        menuTitle = member.shownName, menu = { close ->
            MenuRow("Edit profile", R.drawable.ic_edit) { close(); onEdit() }
            // Message takes the hero's first pill; the way into writing stays one tap away
            if (onMessage != null) MenuRow(writeLabel ?: "Post as ${member.shownName}", if (writeLabel == null) R.drawable.ic_edit else R.drawable.ic_journal) { close(); onWrite() }
            if (subsystemLabel != null) MenuRow(subsystemLabel, R.drawable.ic_layers) { close(); onSubsystem() }
            MenuRow("Copy name", R.drawable.ic_copy) { close(); clipboard.setText(androidx.compose.ui.text.AnnotatedString(member.shownName)) }
        }) {
        if (onMessage != null) ProfilePill("Message", R.drawable.ic_message, filled = true, description = "Message ${member.shownName}", onClick = onMessage)
        else ProfilePill(writeLabel ?: "Post", if (writeLabel == null) R.drawable.ic_edit else R.drawable.ic_journal,
            filled = true, description = if (writeLabel == null) "Post as ${member.shownName}" else "Open ${member.shownName}’s journal",
            onClick = onWrite)
        ProfilePill("Edit profile", null, filled = false, onClick = onEdit)
    }
}

/** Every profile's hero, ours or a friend's (Figma F6.1; owner, 2026-11-06): the banner (or a soft
 * wash in the member's colour) with back and an optional `⋯` floating on it, the avatar overlapping
 * its lower edge on a ring of the page colour, and the profile's actions beside it. */
@Composable
internal fun ProfileHeroFrame(chorus: Chorus, bannerBlob: String?, color: String, glyph: String, avatarBlob: String?,
    closeLabel: String, onClose: () -> Unit, menuTitle: String? = null, menu: (@Composable ColumnScope.(close: () -> Unit) -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit) {
    val p = LocalChorusPalette.current
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().height(184.dp)) {
        ProfileBanner(bannerBlob, color, chorus, Modifier.fillMaxWidth().height(128.dp))
        HeroButton(R.drawable.ic_back, "Back to $closeLabel", onClose, Modifier.align(Alignment.TopStart).padding(12.dp))
        if (menu != null) Box(Modifier.align(Alignment.TopEnd).padding(12.dp)) {
            HeroButton(R.drawable.ic_more, "More profile options", { open = true })
            MenuDrawer(open, { open = false }, menuTitle, leading = if (menuTitle != null) { { Avatar(glyph, color, 40.dp, avatarBlob = avatarBlob, ring = false) } } else null) {
                menu { open = false }
            }
        }
        Box(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 10.dp).size(92.dp)
            .background(p.bg, CircleShape), contentAlignment = Alignment.Center) {
            Avatar(glyph, color, 84.dp, avatarBlob = avatarBlob, ring = false)
        }
        Row(Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = actions)
    }
}

@Composable
private fun ProfileBanner(bannerBlob: String?, color: String, chorus: Chorus, modifier: Modifier) {
    val p = LocalChorusPalette.current
    val ctx = LocalContext.current
    val dev = chorus.device
    val tone = tonesOf(color).ring
    val image by produceState<android.graphics.Bitmap?>(null, bannerBlob, dev?.session) {
        value = null
        value = bannerBlob?.let { hash -> withContext(Dispatchers.IO) {
            runCatching { Blobs.image(ctx, hash, dev, kind = "profile", maxPx = 1500, maxBytes = 10L * 1024 * 1024) }.getOrNull()
        } }
    }
    Box(modifier.background(p.surface2)) {
        val shown = image
        if (shown != null) Image(shown.asImageBitmap(), "Profile banner", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        // no banner yet: the member's colour washed over the page, with a few soft discs
        else Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.linearGradient(listOf(tone.copy(alpha = .55f), tone.copy(alpha = .22f)),
                Offset.Zero, Offset(size.width, size.height)))
            val unit = size.width / 390f
            drawCircle(tone.copy(alpha = .45f), 75 * unit, Offset(325 * unit, 35 * unit))
            drawCircle(p.accent.copy(alpha = .28f), 55 * unit, Offset(25 * unit, 115 * unit))
            drawCircle(Color.White.copy(alpha = .10f), 45 * unit, Offset(345 * unit, 95 * unit))
        }
    }
}

@Composable
private fun HeroButton(icon: Int, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.size(36.dp).clip(CircleShape).background(Color.Black.copy(alpha = .42f)).clickable(onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), description, Modifier.size(20.dp), tint = Color.White)
    }
}

@Composable
internal fun ProfilePill(label: String, icon: Int?, filled: Boolean, description: String = label, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    val ink = if (filled) p.onAccent else p.ink
    Row(Modifier.height(34.dp).clip(CircleShape).background(if (filled) p.accent else p.surface2)
        .clickable(onClickLabel = description, onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (icon != null) Icon(painterResource(icon), null, Modifier.size(16.dp), tint = ink)
        Text(label, color = ink, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, lineHeight = 18.sp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileDetails(member: Member, model: Model, spans: List<FrontSpan>, error: String?, onTab: (String) -> Unit, onInside: () -> Unit,
    onOpenProfile: ((String) -> Unit)?) {
    val p = LocalChorusPalette.current
    val counts = ProfilePresentation.counts(model, member.id)
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(member.shownName, color = p.ink, fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.headlineSmall.copy(fontSize = 26.sp, lineHeight = 32.sp))
        val secondary = member.name.takeIf { member.displayName != null && it != member.displayName }
        val sigils = member.sigils.filter(String::isNotBlank)
        val subsystem = Subsystems.groupOf(model, member.id)
        // the handle leads the line under the name, as Figma F6.1 (D-126)
        val handle = model.handle(member.id)
        if (handle != null || secondary != null || !member.pronouns.isNullOrBlank() || sigils.isNotEmpty() || subsystem != null)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (handle != null) Text("@$handle", color = p.ink2, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    modifier = Modifier.align(Alignment.CenterVertically))
                if (secondary != null) Text(secondary, color = p.ink3, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    modifier = Modifier.align(Alignment.CenterVertically))
                if (!member.pronouns.isNullOrBlank()) ProfileChip(member.pronouns)
                if (sigils.isNotEmpty()) ProfileChip(sigils.joinToString(" "))
                if (subsystem != null) ProfileChip("Subsystem", R.drawable.ic_layers)
            }
        if (!model.isPerson) FrontLine(model, member.id, spans)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ProfileStat(counts.posts, "post", "posts") { onTab("Posts") }
            ProfileStat(counts.entries, "entry", "entries") { onTab("Journal") }
            ProfileStat(counts.relationships, "relationship", "relationships") { onTab("About") }
        }
        if (member.description != null) RichBody(member.description, member.descriptionEntities,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp))
        if (subsystem != null) InsideRow(model, member, onInside)
        val groups = model.membership.filter { member.id in it.value }.keys.mapNotNull(model::group).sortedBy { it.name.lowercase() }
        val audience = model.profileDetails.visibility[member.id] ?: "private"
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (group in groups) {
                // a subsystem that is a member reads as being inside them, and opens their profile
                val holder = Subsystems.memberOf(model, group.id)?.takeIf { it.id != member.id }
                if (holder != null) ProfileChip("Part of ${holder.shownName}", R.drawable.ic_layers,
                    onClick = onOpenProfile?.let { open -> { open(holder.id) } })
                else ProfileChip(group.name, if (group.isSubsystem) R.drawable.ic_layers else R.drawable.ic_members)
            }
            ProfileChip(ProfilePresentation.audienceLine(audience, model.isPerson),
                if (audience == "server") R.drawable.ic_globe else R.drawable.ic_lock)
        }
        if (error != null) Text(error, color = p.ink3, style = MaterialTheme.typography.bodySmall)
    }
}

/** "● Steering · 2h 04m" while they're in the front; otherwise when they last were. */
@Composable
private fun FrontLine(model: Model, memberId: String, spans: List<FrontSpan>) {
    val p = LocalChorusPalette.current
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(30_000); value = System.currentTimeMillis() }
    }
    val status = ProfilePresentation.frontStatus(model, memberId)
    if (status != null) Row(Modifier.height(24.dp).clip(CircleShape).background(p.accent.copy(alpha = .16f))
        .padding(start = 8.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).background(p.accent, CircleShape))
        Text(listOfNotNull(status.layer + status.with?.let { " with $it" }.orEmpty(),
            status.since?.let { ProfilePresentation.duration(now - it) }).joinToString(" · "),
            color = p.accent, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, lineHeight = 16.sp))
    } else ProfileMetrics.compute(model, memberId, now, spans).lastFrontAt?.let { last ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(painterResource(R.drawable.ic_history), null, Modifier.size(14.dp), tint = p.ink3)
            Text("Last fronted " + JournalPresentation.shortAgo(last, now) { "on " + DateFormat.getDateInstance().format(Date(it)) }
                .let { if (it.startsWith("on ")) it else if (it == "now") "just now" else "$it ago" },
                color = p.ink3, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp))
        }
    }
}

@Composable
private fun ProfileStat(count: Int, one: String, many: String, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    Text(buildAnnotatedString {
        withStyle(SpanStyle(color = p.ink, fontWeight = FontWeight.Bold)) { append(count.toString()) }
        append(" ")
        append(if (count == 1) one else many)
    }, color = p.ink3, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(vertical = 2.dp))
}

@Composable
private fun ProfileChip(label: String, icon: Int? = null, onClick: (() -> Unit)? = null) {
    val p = LocalChorusPalette.current
    Row(Modifier.height(24.dp).clip(CircleShape).background(p.surface2)
        .then(if (onClick != null) Modifier.clickable(onClickLabel = label, onClick = onClick) else Modifier).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        if (icon != null) Icon(painterResource(icon), null, Modifier.size(13.dp), tint = p.ink2)
        Text(label, color = p.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp))
    }
}

/** Sticky under-lined tabs; the line under the chosen tab grows in and the old one shrinks away. */
@Composable
private fun ProfileTabRow(selected: String, onSelect: (String) -> Unit) {
    val p = LocalChorusPalette.current
    val motion = rememberChorusMotion()
    Column(Modifier.fillMaxWidth().background(p.bg)) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
            for (tab in ProfileTabs) {
                val chosen = tab == selected
                val line by animateFloatAsState(if (chosen) 1f else 0f, ChorusMotion.tween(motion), label = "profile tab")
                Column(Modifier.clickable(onClickLabel = "Show $tab") { onSelect(tab) }.padding(horizontal = 11.dp).width(IntrinsicSize.Max),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(tab, color = if (chosen) p.ink else p.ink3, fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 19.sp),
                        modifier = Modifier.padding(top = 10.dp, bottom = 8.dp))
                    Box(Modifier.fillMaxWidth().height(3.dp).graphicsLayer { scaleX = line; alpha = line }
                        .background(p.accent, RoundedCornerShape(1.5.dp)))
                }
            }
        }
        HorizontalDivider(color = p.line, thickness = .5.dp)
    }
}

@Composable
private fun PinnedHeader() {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.width(40.dp), contentAlignment = Alignment.CenterEnd) {
            Icon(painterResource(R.drawable.ic_pin), null, Modifier.size(14.dp), tint = p.accent)
        }
        Text("Pinned", color = p.ink3, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ProfileEmpty(icon: Int, text: String, modifier: Modifier = Modifier) = EmptyState(icon, text, modifier)

/** Three square photos per row, 2 dp apart. A post's content warning covers its photos until tapped. */
@Composable
private fun MediaRow(cells: List<Pair<JournalPost, ChatAttachment>>, chorus: Chorus, who: String, modifier: Modifier) {
    val p = LocalChorusPalette.current
    Row(modifier.fillMaxWidth().padding(bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 0 until 3) {
            val cell = cells.getOrNull(i)
            if (cell == null) { Spacer(Modifier.weight(1f)); continue }
            val (post, attachment) = cell
            var revealed by rememberSaveable(post.id) { mutableStateOf(post.cw == null) }
            val tile = Modifier.weight(1f).aspectRatio(1f)
            if (revealed) MediaThumb(attachment, chorus, tile, who, post.occurredAt)
            else Box(tile.background(p.surface3).clickable(onClickLabel = "Show photo") { revealed = true }.padding(8.dp),
                contentAlignment = Alignment.Center) {
                Text("Content warning: ${post.cw}", color = p.ink2, style = MaterialTheme.typography.labelSmall,
                    maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

/** Everything that doesn't fit the header: fields, front time, counts and relationships. */
@Composable
private fun ProfileAbout(chorus: Chorus, model: Model, member: Member, bundle: ProfileBundle?, spans: List<FrontSpan>, modifier: Modifier) {
    val p = LocalChorusPalette.current
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    var linkError by remember { mutableStateOf<String?>(null) }
    val metrics = ProfileMetrics.compute(model, member.id, spans = spans)
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        @Composable fun AboutRow(label: String, value: String, onClick: (() -> Unit)? = null) {
            Column(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, color = p.ink3, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp))
                Text(value, color = if (onClick != null) p.accent else p.ink, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp))
            }
            HorizontalDivider(color = p.line, thickness = .5.dp)
        }
        if (member.displayName != null && member.name != member.displayName) AboutRow("Full name", member.name)
        if (!member.pronouns.isNullOrBlank()) AboutRow("Pronouns", member.pronouns)
        if (member.birthday != null) AboutRow("Birthday", ProfilePresentation.birthday(member.birthday))
        for (field in model.profileFields[member.id].orEmpty()) {
            if (MemberCustomization.validWebsite(field.value)) AboutRow(field.name, field.value) {
                runCatching { uri.openUri(field.value) }.onFailure { linkError = "No app could open this website." }
            } else AboutRow(field.name, field.value)
        }
        if (!model.isPerson) {
            AboutRow("Front time", "${"%.1f".format(metrics.weekHours)} h in 7 days · ${"%.1f".format(metrics.monthHours)} h in 28 days")
            AboutRow("Last fronted", if (ProfilePresentation.frontStatus(model, member.id) != null) "Now"
                else metrics.lastFrontAt?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) }
                    ?: "No front recorded")
        }
        if (linkError != null) Text(linkError.orEmpty(), color = p.danger, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp))
        Text("Relationships", color = p.ink, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
        ProfileRelationships(chorus, model, member.id)
    }
}
