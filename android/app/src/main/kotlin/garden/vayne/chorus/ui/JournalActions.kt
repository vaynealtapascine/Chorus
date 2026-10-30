package garden.vayne.chorus.ui

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
 * share, evenly spaced, each with a muted count. A heart is one tap; the contextual identity
 * disclosure appears only the first time on this device, and only when other accounts can read
 * the post. Long-press the heart to see who reacted. Callbacks left null hide that action. */
@Composable
internal fun JournalActions(reactions: List<PostReaction>, member: Member?, enabled: Boolean = true,
    onReply: (() -> Unit)? = null, onReact: (() -> Unit)? = null, replies: Int = 0,
    reposts: Int = 0, reposted: Boolean = false, onRepost: (() -> Unit)? = null,
    onUndoRepost: (() -> Unit)? = null, onQuote: (() -> Unit)? = null,
    saved: Boolean = false, onBookmark: (() -> Unit)? = null, onShare: (() -> Unit)? = null,
    readersSeeName: Boolean = true, large: Boolean = false) {
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
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        if (onReply != null) ActionButton(shown(replies), p.ink3, "Reply", large, onClick = onReply) {
            Icon(painterResource(R.drawable.ic_reply), null, Modifier.size(if (large) 24.dp else 20.dp), tint = p.ink3)
        }
        if (onRepost != null) Box {
            val tint = if (reposted) p.accent else p.ink3
            ActionButton(shown(reposts), tint, if (reposted) "Reposted; open repost options" else "Repost", large,
                onClick = { repostMenu = true }) {
                PopIcon(R.drawable.ic_repost, reposted, tint, if (large) 24.dp else 20.dp)
            }
            DropdownMenu(repostMenu, { repostMenu = false }) {
                DropdownMenuItem(text = { Text(if (reposted) "Undo repost" else "Repost") }, enabled = enabled, onClick = {
                    repostMenu = false
                    if (reposted) onUndoRepost?.invoke() else onRepost()
                })
                if (onQuote != null) DropdownMenuItem(text = { Text("Quote") }, onClick = { repostMenu = false; onQuote() })
            }
        }
        if (canReact || hearts > 0) {
            val tint = if (selected) p.danger else p.ink3
            ActionButton(shown(hearts), tint,
                if (canReact) "${if (selected) "Remove heart" else "Heart"} as ${member!!.shownName}; visible to post readers"
                else "$hearts ${if (hearts == 1) "heart" else "hearts"}", large, enabled = enabled || !canReact,
                onClick = { if (canReact) heart() else who = true },
                onLongClick = { who = true }.takeIf { hearts > 0 }, longClickLabel = "Show who reacted") {
                PopIcon(if (selected) R.drawable.ic_heart_filled else R.drawable.ic_heart, selected, tint,
                    if (large) 24.dp else 20.dp, burst = true)
            }
        }
        if (onBookmark != null) {
            val tint = if (saved) p.accent else p.ink3
            ActionButton(null, tint, if (saved) "Remove from saved" else "Save", large, onClick = onBookmark) {
                PopIcon(if (saved) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark, saved, tint, if (large) 24.dp else 20.dp)
            }
        }
        if (onShare != null) ActionButton(null, p.ink3, "Share", large, onClick = onShare) {
            Icon(painterResource(R.drawable.ic_share), null, Modifier.size(if (large) 24.dp else 20.dp), tint = p.ink3)
        }
    }
    if (disclose && member != null) AlertDialog(onDismissRequest = { disclose = false },
        title = { Text("React as ${member.shownName}?") },
        text = { Text("Post readers can see this member’s name with the reaction. You’ll only be asked once on this device.") },
        confirmButton = { TextButton(enabled = enabled, onClick = {
            notice.edit().putBoolean(NAME_NOTICE, true).apply()
            disclose = false; onReact?.invoke()
        }) { Text("Heart") } },
        dismissButton = { TextButton(onClick = { disclose = false }) { Text("Cancel") } })
    if (who) AlertDialog(onDismissRequest = { who = false }, title = { Text("Reactions") },
        text = { Text(reactions.joinToString(" · ") { "${it.emoji} ${it.memberName}" }) },
        confirmButton = { TextButton(onClick = { who = false }) { Text("Done") } })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActionButton(count: Int?, tint: Color, description: String, large: Boolean, enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null, longClickLabel: String? = null, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Row(Modifier.heightIn(min = if (large) 52.dp else 48.dp).widthIn(min = 48.dp).clip(RoundedCornerShape(24.dp))
        .combinedClickable(enabled = enabled, role = Role.Button, onClick = onClick, onLongClick = onLongClick,
            onLongClickLabel = longClickLabel)
        .semantics { contentDescription = description }.padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
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
        if (n > 0) Text(n.toString(), color = tint, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 4.dp))
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
    Box(Modifier.size(size + 12.dp), contentAlignment = Alignment.Center) {
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
    onQuote: ((JournalPost) -> Unit)? = null, large: Boolean = false) {
    val p = LocalChorusPalette.current
    val actions = rememberCoroutineScope()
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
    val own = member?.let { JournalSocial.repostBy(model.posts, post.id, it.id) }
    JournalActions(post.reactions, member, !busy, onReply = onReply, replies = JournalSocial.replyCount(model.posts, post.id),
        reposts = JournalSocial.reposts(model.posts, post.id).size, reposted = own != null,
        onRepost = member?.let { who -> {
            write("Could not repost. Try again.") {
                chorus.create("post.create", chorus.newId(), PostCompose.repostPayload(model, account.orEmpty(), who.id, post.id))
            }
        } },
        // undoing deletes (tombstones) the repost, never the original
        onUndoRepost = own?.let { repost -> { write("Could not undo the repost. Try again.") {
            chorus.create("post.delete", repost.id, org.json.JSONObject())
        } } },
        onQuote = onQuote?.let { { it(post) } },
        onReact = member?.let { who -> {
            val selected = post.reactions.any { it.emoji == PostReactions.HEART && it.memberId == who.id }
            write("Could not save the reaction. Try again.") {
                chorus.create(if (selected) "post.unreact" else "post.react", post.id, PostReactions.payload(post.id, who.id))
            }
        } }, readersSeeName = post.visibility != "private", large = large)
    if (error != null) Text(error.orEmpty(), color = p.danger, style = MaterialTheme.typography.bodySmall)
}
