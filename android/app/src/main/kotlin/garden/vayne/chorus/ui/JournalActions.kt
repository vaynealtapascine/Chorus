package garden.vayne.chorus.ui

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import garden.vayne.chorus.data.*
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.launch

/** Device-local: the "readers see this member's name" notice is shown once, not on every heart. */
private const val NAME_NOTICE = "heart_name_notice"

/** One action row across own/shared posts and the post detail: reply, repost, heart, save and
 * share, each with a muted count. Icons sit at even steps from the text edge to the card's end
 * (Figma F6.3: 18 dp icons, 13 sp counts), so the columns line up from post to post whatever the
 * counts are; each target reaches 8 dp past its icon. A heart is one tap; the contextual identity
 * disclosure appears only the first time on this device, and only when other accounts can read
 * the post. Long-press the heart to see who reacted. The row is the same on every post (D-127):
 * an action whose callback is null is shown disabled, never hidden, so the columns never move. */
@Composable
internal fun JournalActions(reactions: List<PostReaction>, member: Member?, enabled: Boolean = true,
    onReply: (() -> Unit)? = null, onReact: (() -> Unit)? = null, replies: Int = 0,
    reposts: Int = 0, reposted: Boolean = false, onRepost: (() -> Unit)? = null,
    onUndoRepost: (() -> Unit)? = null, onQuote: (() -> Unit)? = null,
    saved: Boolean = false, onBookmark: (() -> Unit)? = null, onShare: (() -> Unit)? = null,
    readersSeeName: Boolean = true, large: Boolean = false, onCopyLink: (() -> Unit)? = null) {
    val p = LocalChorusPalette.current
    val notice = LocalContext.current.getSharedPreferences("chorus.journal", 0)
    var who by remember { mutableStateOf(false) }
    var disclose by remember { mutableStateOf(false) }
    var repostMenu by remember { mutableStateOf(false) }
    val hearts = reactions.count { it.emoji == PostReactions.HEART }
    val selected = member != null && reactions.any { it.emoji == PostReactions.HEART && it.memberId == member.id }
    val canReact = member != null && onReact != null
    fun heart() {
        if (!selected && readersSeeName && !notice.getBoolean(NAME_NOTICE, false)) disclose = true else onReact?.invoke()
    }
    // the large detail row repeats the counts in its stats line above, so it shows icons only
    fun shown(count: Int) = count.takeIf { !large }
    val icon = if (large) 22.dp else 18.dp
    // keyed, so a heart appearing between two others never hands its animation state to a neighbour
    val actions = ArrayList<Pair<String, @Composable () -> Unit>>()
    // an action this post can't take is shown faded and does nothing
    fun faded(tint: Color, on: Boolean) = if (on) tint else tint.copy(alpha = .38f)
    actions += "reply" to {
        ActionButton(shown(replies), faded(p.ink3, onReply != null), "Reply", large, enabled = onReply != null, onClick = { onReply?.invoke() }) {
            Icon(painterResource(R.drawable.ic_reply), null, Modifier.size(icon), tint = faded(p.ink3, onReply != null))
        }
    }
    // repost and quote share one button: it works if either does, and its menu fades the other
    val canRepost = onRepost != null || (reposted && onUndoRepost != null)
    actions += "repost" to {
        Box {
            val tint = faded(if (reposted) p.accent else p.ink3, canRepost || onQuote != null)
            ActionButton(shown(reposts), tint, if (reposted) "Reposted; open repost options" else "Repost or quote", large,
                enabled = canRepost || onQuote != null, onClick = { repostMenu = true }) {
                PopIcon(R.drawable.ic_repost, reposted, tint, icon)
            }
            MenuDrawer(repostMenu, { repostMenu = false }, if (reposted) "Reposted" else "Repost") {
                MenuRow(if (reposted) "Undo repost" else "Repost", R.drawable.ic_repost, enabled = enabled && canRepost,
                    subtitle = if (canRepost) null else "Only your own system’s posts can be reposted") {
                    repostMenu = false
                    if (reposted) onUndoRepost?.invoke() else onRepost?.invoke()
                }
                MenuRow("Quote", R.drawable.ic_format_quote, if (onQuote != null) "Repost with your own words" else "This post can’t be quoted",
                    enabled = onQuote != null) { repostMenu = false; onQuote?.invoke() }
            }
        }
    }
    actions += "heart" to {
        val tint = faded(if (selected) p.danger else p.ink3, canReact || hearts > 0)
        ActionButton(shown(hearts), tint,
            if (canReact) "${if (selected) "Remove heart" else "Heart"} as ${member!!.shownName}; visible to post readers"
            else "$hearts ${if (hearts == 1) "heart" else "hearts"}", large, enabled = (enabled && canReact) || hearts > 0,
            onClick = { if (canReact) heart() else who = true },
            onLongClick = { who = true }.takeIf { hearts > 0 }, longClickLabel = "Show who reacted") {
            PopIcon(if (selected) R.drawable.ic_heart_filled else R.drawable.ic_heart, selected, tint, icon, burst = true)
        }
    }
    actions += "save" to {
        val tint = faded(if (saved) p.accent else p.ink3, onBookmark != null)
        ActionButton(null, tint, if (onBookmark == null) "Save (not available for this post)" else if (saved) "Remove from saved" else "Save", large,
            enabled = onBookmark != null, onClick = { onBookmark?.invoke() }) {
            PopIcon(if (saved) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark, saved, tint, icon)
        }
    }
    // with a link to copy (D-109), Share opens a small menu: Copy link, then the system's Share
    actions += "share" to {
        var shareMenu by remember { mutableStateOf(false) }
        val canShare = onShare != null || onCopyLink != null
        Box {
            ActionButton(null, faded(p.ink3, canShare), if (canShare) "Share" else "Share (nothing to share)", large, enabled = canShare,
                onClick = { if (onCopyLink != null) shareMenu = true else onShare?.invoke() }) {
                Icon(painterResource(R.drawable.ic_share), null, Modifier.size(icon), tint = faded(p.ink3, canShare))
            }
            MenuDrawer(shareMenu, { shareMenu = false }, "Share") {
                MenuRow("Copy link", R.drawable.ic_link, "Opens for people who can already read it") { shareMenu = false; onCopyLink?.invoke() }
                if (onShare != null) MenuRow("Share…", R.drawable.ic_share, "Send the text to another app") { shareMenu = false; onShare() }
            }
        }
    }
    // every action but the last gets an equal cell and the last is only its own target wide, so
    // the icons fall at even steps and the last one ends flush with the text column
    Row(Modifier.fillMaxWidth().bleed(ActionPad), verticalAlignment = Alignment.CenterVertically) {
        actions.forEachIndexed { i, (name, action) ->
            key(name) { if (i < actions.lastIndex) Box(Modifier.weight(1f)) { action() } else action() }
        }
    }
    if (disclose && member != null) ConfirmDrawer("React as ${member.shownName}?",
        "Post readers can see this member’s name with the reaction. You’ll only be asked once on this device.", "Heart", {
            notice.edit().putBoolean(NAME_NOTICE, true).apply()
            disclose = false; onReact?.invoke()
        }, { disclose = false }, enabled = enabled, icon = R.drawable.ic_heart)
    MenuDrawer(who, { who = false }, "Reactions", "${reactions.size} ${if (reactions.size == 1) "reaction" else "reactions"}") {
        for (r in reactions) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(r.emoji, fontSize = 20.sp)
            Text(r.memberName, color = p.ink, fontSize = 16.sp)
        }
    }
}

/** How far each action's target reaches past its icon; the row bleeds as far on both sides. */
private val ActionPad = 8.dp

/** Widen by [by] on both sides without moving the content's own edges. */
private fun Modifier.bleed(by: Dp) = layout { measurable, constraints ->
    val extra = (by * 2).roundToPx()
    if (!constraints.hasBoundedWidth) return@layout measurable.measure(constraints).let { layout(it.width, it.height) { it.place(0, 0) } }
    val wide = constraints.maxWidth + extra
    val placeable = measurable.measure(constraints.copy(minWidth = wide, maxWidth = wide))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActionButton(count: Int?, tint: Color, description: String, large: Boolean, enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null, longClickLabel: String? = null, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Row(Modifier.height(if (large) 48.dp else 36.dp).clip(RoundedCornerShape(18.dp))
        .combinedClickable(enabled = enabled, role = Role.Button, onClick = onClick, onLongClick = onLongClick,
            onLongClickLabel = longClickLabel)
        .semantics { contentDescription = description }.padding(horizontal = ActionPad),
        verticalAlignment = Alignment.CenterVertically) {
        icon()
        if (count != null) RollingCount(count, tint)
    }
}

/** A count that rolls up or down instead of snapping; nothing is shown for zero. */
@Composable
private fun RollingCount(count: Int, tint: Color) {
    val motion = rememberChorusMotion()
    AnimatedContent(count, transitionSpec = {
        val up = targetState > initialState
        val spec = if (!motion) EnterTransition.None togetherWith ExitTransition.None
        else (slideInVertically(ChorusMotion.tween(true, ChorusMotion.Quick)) { if (up) it else -it } +
            fadeIn(ChorusMotion.tween(true, ChorusMotion.Quick))) togetherWith
            (slideOutVertically(ChorusMotion.tween(true, ChorusMotion.Quick)) { if (up) -it else it } +
                fadeOut(ChorusMotion.tween(true, ChorusMotion.Quick)))
        spec.using(SizeTransform(clip = false))
    }, label = "action count") { n ->
        if (n > 0) Text(n.toString(), color = tint, style = MaterialTheme.typography.bodySmall.copy(
            fontSize = 13.sp, lineHeight = 18.sp), modifier = Modifier.padding(start = 5.dp))
    }
}

/** An icon that pops when [active] turns on (and dips when it turns off). With [burst] it also
 * throws a ring and a few specks that fade within a third of a second. Nothing runs at first
 * composition, so a scrolled list never replays old likes, and nothing runs with reduced motion. */
@Composable
private fun PopIcon(icon: Int, active: Boolean, tint: Color, size: Dp, burst: Boolean = false) {
    val motion = rememberChorusMotion()
    val scale = remember { Animatable(1f) }
    val progress = remember { Animatable(0f) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(active) {
        if (first) { first = false; return@LaunchedEffect }
        if (!motion) return@LaunchedEffect
        if (active) {
            if (burst) launch { progress.snapTo(0f); progress.animateTo(1f, ChorusMotion.tween(true, ChorusMotion.Gentle)) }
            scale.snapTo(.5f)
            scale.animateTo(1f, ChorusMotion.pop())
        } else {
            scale.snapTo(.82f)
            scale.animateTo(1f, ChorusMotion.settle())
        }
    }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (burst) Canvas(Modifier.requiredSize(size + 28.dp)) {
            val t = progress.value
            if (t <= 0f || t >= 1f) return@Canvas
            val unit = size.toPx()
            drawCircle(tint.copy(alpha = (1f - t) * .55f), radius = lerp(unit * .4f, unit * 1.15f, t),
                center = center, style = Stroke(2.dp.toPx() * (1f - t)))
            for (i in 0 until 6) {
                val angle = (i * 60 + 20) * PI.toFloat() / 180f
                val reach = lerp(unit * .5f, unit * 1.05f, t)
                drawCircle(tint.copy(alpha = 1f - t), 2.dp.toPx() * (1f - t * .5f),
                    Offset(center.x + cos(angle) * reach, center.y + sin(angle) * reach))
            }
        }
        Icon(painterResource(icon), null, Modifier.size(size).graphicsLayer { scaleX = scale.value; scaleY = scale.value }, tint = tint)
    }
}

/** The post's own state for the action row: counts, who is acting and every write go through
 * `chorus.create` like any other op, so each works offline. Shown errors stay one quiet line. */
@Composable
internal fun JournalPostActions(post: JournalPost, model: Model, chorus: Chorus, onReply: () -> Unit,
    onQuote: ((JournalPost) -> Unit)? = null, large: Boolean = false, reactions: List<PostReaction> = post.reactions,
    foreign: Boolean = false, onReacted: ((Member) -> Unit)? = null, replies: Int? = null) {
    val p = LocalChorusPalette.current
    val actions = rememberCoroutineScope()
    val ctx = LocalContext.current
    var busy by remember(post.id) { mutableStateOf(false) }
    var error by remember(post.id) { mutableStateOf<String?>(null) }
    val account = chorus.device?.accountId
    val member = Reply.speaker(model)?.takeIf { it.createdByAccountId == null || it.createdByAccountId == account }
    fun write(failure: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        actions.launch {
            try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { error = failure }
            finally { busy = false }
        }
    }
    // a post from another account isn't in the local replica: it can be hearted, quoted and shared, not reposted or saved
    val own = member?.takeIf { !foreign }?.let { JournalSocial.repostBy(model.posts, post.id, it.id) }
    // our own posts also count other accounts' replies and show their hearts (GET /posts/activity)
    val activity by JournalActivityApi.state.collectAsState()
    val theirs = activity?.takeIf { !foreign && it.first == account }?.second
    val shownReactions = theirs?.reactions?.get(post.id)?.let { PostReactions.merge(reactions, it) } ?: reactions
    JournalActions(shownReactions, member, !busy, onReply = onReply,
        replies = replies ?: (JournalSocial.replyCount(model.posts, post.id) + (theirs?.replyCounts?.get(post.id) ?: 0)),
        reposts = JournalSocial.reposts(model.posts, post.id).size, reposted = own != null,
        onRepost = member?.takeIf { !foreign }?.let { who -> {
            write("Could not repost. Try again.") {
                chorus.create("post.create", chorus.newId(), PostCompose.repostPayload(model, account.orEmpty(), who.id, post.id))
            }
        } },
        // undoing deletes (tombstones) the repost, never the original
        onUndoRepost = own?.let { repost -> { write("Could not undo the repost. Try again.") {
            chorus.create("post.delete", repost.id, org.json.JSONObject())
        } } },
        // another account's post can be quoted too; the quote borrows its audience (D-127)
        onQuote = onQuote?.let { { it(post) } },
        onShare = JournalSocial.shareText(post.title, post.cw, post.text).takeIf { it.isNotBlank() }?.let { text ->
            { shareJournalText(ctx, post.title, text) }
        },
        onCopyLink = chorus.device?.base?.let { base -> { copyLink(ctx, garden.vayne.chorus.data.ChorusLinks.linkTo(base, "post", post.id)) } },
        // saving is a private account pref: it syncs to this account's devices and never reaches the author
        saved = model.accountPrefs.isSaved(post.id),
        onBookmark = if (foreign) null else ({ write("Could not update your saved posts. Try again.") {
            chorus.create("pref.set", null, AccountPrefs.bookmarkPayload(post.id, !model.accountPrefs.isSaved(post.id)))
        } }),
        onReact = member?.let { who -> {
            val selected = reactions.any { it.emoji == PostReactions.HEART && it.memberId == who.id }
            write("Could not save the reaction. Try again.") {
                chorus.create(if (selected) "post.unreact" else "post.react", post.id, PostReactions.payload(post.id, who.id))
                onReacted?.invoke(who)
            }
        } }, readersSeeName = foreign || post.visibility != "private", large = large)
    if (error != null) Text(error.orEmpty(), color = p.danger, style = MaterialTheme.typography.bodySmall)
}

/** Copy a link to a post or message (D-109): it opens the thing, and shows it as a preview in a chat. */
internal fun copyLink(ctx: Context, link: String) {
    val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Link", link))
}

/** Hand plain post text to the Android share sheet; nothing else leaves the app. The content
 * warning is the first line of [text], so a post is never shared without it. */
internal fun shareJournalText(ctx: Context, title: String?, text: String) {
    if (text.isBlank()) return
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        title?.takeIf { it.isNotBlank() }?.let { putExtra(Intent.EXTRA_TITLE, it); putExtra(Intent.EXTRA_SUBJECT, it) }
    }
    try { ctx.startActivity(Intent.createChooser(send, "Share post")) } catch (_: android.content.ActivityNotFoundException) {}
}
