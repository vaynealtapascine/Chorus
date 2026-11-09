package garden.vayne.chorus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.JournalPost
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.PostThreads
import garden.vayne.chorus.data.SavedStage
import garden.vayne.chorus.data.StagePlan
import garden.vayne.chorus.data.ThreadReply
import garden.vayne.chorus.designsystem.LocalChorusDark
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/** One reply on a post's stage, and the post or reply it answers. */
private data class StageReply(val parent: String, val reply: ThreadReply)

/**
 * A post as a screenshot card (F8.5): the post with its replies nested under it, as many levels as
 * chosen (deeper ones fold into one line), only some members' replies, and the like and repost
 * counts hidden or not. Saved with `post_id`, as the web's post stage is. Render-only.
 */
@Composable
internal fun PostStage(chorus: Chorus, model: Model, post: JournalPost, capturing: Boolean, onCapture: (Boolean) -> Unit,
    onClose: () -> Unit) {
    val hostDark = LocalChorusDark.current
    val host = LocalChorusPalette.current
    var s by rememberSaveable(post.id, stateSaver = StageSaver) { mutableStateOf(StagePlan.Settings(style = "card", replyDepth = 2)) }
    var tab by rememberSaveable(post.id) { mutableStateOf(StageTab.Filters) }
    var saving by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val actions = rememberCoroutineScope()
    val (pillsVisible, showPills) = rememberCapturePills(capturing)
    // the server nests replies three levels deep; offline, our own direct replies still show
    val replies by produceState<List<StageReply>?>(null, post.id, chorus.device?.session) {
        val dev = chorus.device
        value = try {
            if (dev == null) null else PostThreads.loadDeep(dev, post.id).map { (parent, reply) -> StageReply(parent, reply) }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            ?: PostThreads.ownReplies(model, post.id).map { StageReply(post.id, it) }
    }
    val loaded = replies.orEmpty()
    val byId = remember(loaded) { loaded.associateBy { it.reply.id } }
    val items = remember(post, loaded) {
        listOf(StagePlan.Item(post.id, post.authors, post.occurredAt, null)) +
            loaded.map { StagePlan.Item(it.reply.id, it.reply.authorIds, it.reply.occurredAt, it.parent) }
    }
    val picking = !capturing && tab == StageTab.Select
    val planned = if (picking && s.selected.isNotEmpty()) s.copy(unselected = "visible") else s
    val plan = remember(items, planned) { StagePlan.forItems(items, planned, post.id) }
    val (p, dark) = stagePalette(s, plan.palette)
    val authors = remember(post, loaded, model) {
        (post.authors.map { stageAuthor(it, model, emptyMap()) } + loaded.flatMap { r ->
            r.reply.authorIds.mapIndexed { i, id ->
                model.member(id)?.let { stageAuthor(id, model, emptyMap()) }
                    ?: StageAuthor(id, r.reply.authorNames.getOrNull(i) ?: "Someone", (r.reply.authorNames.getOrNull(i) ?: "?").take(1).uppercase(),
                        r.reply.color ?: "#A09184", r.reply.avatarBlob.takeIf { i == 0 })
            }
        }).distinctBy { it.id }
    }
    val authorById = remember(authors) { authors.associateBy { it.id } }
    val replyAuthors = remember(loaded, authorById) { loaded.flatMap { it.reply.authorIds }.distinct().mapNotNull(authorById::get) }
    val depthOf = remember(loaded) {
        val parents = loaded.associate { it.reply.id to it.parent }
        loaded.associate { r -> r.reply.id to generateSequence(r.parent) { parents[it] }.takeWhile { it != post.id }.count() }
    }
    val kind = if (post.kind == "entry") "Journal entry" else "Journal post"
    val levels = when (val d = s.replyDepth) { null -> "all replies"; 0 -> "no replies"; else -> countLabel(d, "reply level") }
    val subtitle = "$kind · ${stageStyleLabel(s.style)} · $levels"
    val saved = model.savedStages.filter { it.postId == post.id }

    Column(Modifier.fillMaxSize().background(p.bg)) {
        if (!capturing) StageBar(subtitle, onClose, { error = null; saving = true }, { onCapture(true) }, true)
        Box(Modifier.weight(1f).fillMaxWidth().then(if (capturing) Modifier.statusBarsPadding() else Modifier),
            contentAlignment = Alignment.TopCenter) {
            CompositionLocalProvider(LocalChorusPalette provides p, LocalChorusDark provides dark) {
                Column(Modifier.fillMaxSize().stageWidth(s.width).verticalScroll(rememberScrollState()).padding(16.dp)) {
                    Spacer(Modifier.height(24.dp))
                    val framed = s.style !in setOf("minimal", "transcript")
                    val shape = RoundedCornerShape(16.dp)
                    Column(Modifier.fillMaxWidth().then(if (framed) Modifier.background(p.surface, shape).border(1.dp, p.line, shape) else Modifier)) {
                        val root = plan.rows.firstOrNull { it.id == post.id }
                        if (root != null) PostCardHead(chorus, post, root, plan, s, authorById, model, kind,
                            !capturing && tab == StageTab.Redact) { ranges -> s = s.withBlackout(post.id, ranges) }
                        val rows = plan.rows.filter { it.id != post.id }
                        if (rows.isNotEmpty() || plan.cropped > 0) Column(Modifier.fillMaxWidth()
                            .background(if (framed) p.surface2 else p.bg).padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            for (row in rows) {
                                if (row.id == null) {
                                    StageContextRow(row.contextIds.flatMap { byId[it]?.reply?.authorIds.orEmpty() }.map { id ->
                                        plan.names[id] ?: authorById[id]?.name ?: "Someone" }, row.contextCount, "reply", "replies")
                                    continue
                                }
                                val r = byId[row.id] ?: continue
                                val on = r.reply.id in s.selected
                                Row(Modifier.fillMaxWidth().padding(start = (16 * (depthOf[r.reply.id] ?: 0)).dp)
                                    .then(if (picking) Modifier.toggleable(on, role = Role.Checkbox) {
                                        s = s.copy(selected = if (on) s.selected - r.reply.id else s.selected + r.reply.id)
                                    } else Modifier).alpha(if (!capturing && !row.selected) .45f else 1f),
                                    verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (picking) StageCheck(on)
                                    StageReplyRow(r.reply, row, plan, s, authorById, !capturing && tab == StageTab.Redact) { ranges ->
                                        s = s.withBlackout(r.reply.id, ranges)
                                    }
                                }
                            }
                            if (plan.cropped > 0) Text("${countLabel(plan.cropped, "deeper reply", "deeper replies")} hidden",
                                color = p.ink3, fontSize = 12.sp, fontStyle = FontStyle.Italic, modifier = Modifier.padding(start = 16.dp))
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
                if (capturing) StageCapturePills(pillsVisible, showPills) { onCapture(false) }
            }
        }
        if (!capturing) {
            StagePanel {
                when (tab) {
                    StageTab.Select -> {
                        if (loaded.isEmpty()) Text(if (replies == null) "Loading…" else "No replies", color = host.ink3, fontSize = 14.sp)
                        else {
                            StageLabel("Replies you didn’t pick")
                            val modes = listOf("hidden", "context", "visible")
                            Segmented(listOf("Hidden", "Context only", "Shown"), modes.indexOf(s.unselected), accent = true) {
                                s = s.copy(unselected = modes[it])
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StageChip("Pick all") { s = s.copy(selected = loaded.mapTo(LinkedHashSet()) { it.reply.id }) }
                                StageChip("Pick none") { s = s.copy(selected = emptySet()) }
                            }
                        }
                    }
                    StageTab.Filters -> {
                        StageLabel("Replies")
                        StageRow("Reply levels") {
                            val depth = s.replyDepth
                            StageStepper(depth?.toString() ?: "All", "Reply levels",
                                minus = { s = s.copy(replyDepth = if (depth == null) 2 else (depth - 1).coerceAtLeast(0)) }.takeIf { depth != 0 },
                                plus = { s = s.copy(replyDepth = depth?.let { if (it >= 2) null else it + 1 }) }.takeIf { depth != null })
                        }
                        if (replyAuthors.size > 1) {
                            Text("Only replies from", color = host.ink3, fontSize = 13.sp)
                            StageMemberChips(replyAuthors, s.onlyMembers) { s = s.copy(onlyMembers = it) }
                        }
                        Column { ToggleRow("Hide like and repost counts", s.hideCounts, inset = 0.dp) { s = s.copy(hideCounts = it) } }
                    }
                    StageTab.Style -> StageStylePanel(s, hostDark) { s = it }
                    StageTab.Redact -> StageRedactPanel(s, { s = it }, chatName = false)
                    StageTab.Names -> StageNamesPanel(s, authors, post.occurredAt) { s = it }
                }
                if (error != null && !saving) Text(error.orEmpty(), color = host.danger, fontSize = 13.sp)
            }
            StageTabs(tab) { tab = it }
        }
    }
    if (saving) {
        fun summary(stage: SavedStage): String {
            val loadedStage = StagePlan.supported(stage.definition) ?: return "Made on the web"
            return listOfNotNull(kind, stageStyleLabel(loadedStage.style), "replies cropped".takeIf { loadedStage.replyDepth != null },
                "names changed".takeIf { loadedStage.fakeNames.isNotEmpty() }, "redacted".takeIf { stageRedacted(loadedStage) }).joinToString(" · ")
        }
        StageSaveDrawer(saved.firstOrNull { StagePlan.supported(it.definition) == s }?.name.orEmpty(),
            saved.map { StageSavedRow(it, R.drawable.ic_journal, summary(it), StagePlan.supported(it.definition) != null) },
            busy, error, onSave = { name ->
                val id = saved.firstOrNull { it.name.equals(name, ignoreCase = true) }?.id ?: chorus.newId()
                busy = true; error = null
                actions.launch {
                    try {
                        chorus.create("stage.save", id, JSONObject().put("name", name).put("definition", StagePlan.definition("", s, post.id)))
                        saving = false
                    } catch (e: Exception) { error = e.message ?: "Could not save this stage." }
                    finally { busy = false }
                }
            }, onOpen = { stage -> StagePlan.supported(stage.definition)?.let { s = it }; saving = false },
            onDelete = { stage ->
                busy = true; error = null
                actions.launch {
                    try { chorus.create("stage.delete", stage.id, JSONObject()) }
                    catch (e: Exception) { error = e.message ?: "Could not delete this stage." }
                    finally { busy = false }
                }
            }, onDismiss = { saving = false })
    }
}

private fun StagePlan.Settings.withBlackout(id: String, ranges: List<IntRange>) =
    copy(blackout = if (ranges.isEmpty()) blackout - id else blackout + (id to ranges))

/** The post at the top of its card: face, names, time, title, text and its counts. */
@Composable
private fun PostCardHead(chorus: Chorus, post: JournalPost, row: StagePlan.Row, plan: StagePlan.Result, s: StagePlan.Settings,
    authors: Map<String, StageAuthor>, model: Model, kind: String, editing: Boolean, onBlackout: (List<IntRange>) -> Unit) {
    val p = LocalChorusPalette.current
    var revealed by rememberSaveable(post.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StageFace(post.authors.firstOrNull(), plan, s, authors, 44)
            val names = post.authors.map { plan.names[it] ?: authors[it]?.name ?: "Someone" }.joinToString(" & ").ifBlank { "Someone" }
            val handle = post.authors.singleOrNull()?.takeIf { it !in plan.names }?.let(model::handle)
            Text(names, color = p.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(listOfNotNull(handle?.let { "@$it" }, row.at?.let { compactAgo(it) }).joinToString(" · "), color = p.ink3, fontSize = 13.sp,
                maxLines = 1, modifier = Modifier.weight(1f))
        }
        if (post.cw != null) Text("Content warning: ${post.cw} · ${if (revealed) "Hide" else "Show"}", color = p.accent, fontSize = 14.sp,
            modifier = Modifier.toggleable(revealed, role = Role.Button) { revealed = it })
        if (post.cw == null || revealed) {
            post.title?.let { Text(it, color = p.ink, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold) }
            if (post.text.isNotBlank()) StageText(post.text, row.blackout, s.blackout[post.id].orEmpty(), editing,
                TextStyle(color = p.ink, fontSize = 16.sp, lineHeight = 23.sp), onBlackout)
            for (a in post.attachments) StageAttachment(chorus, a, s.blurAttachments)
        }
        val meta = listOfNotNull(kind.takeIf { post.kind == "entry" },
            row.at?.takeIf { !s.hideDates }?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) })
        if (meta.isNotEmpty()) Text(meta.joinToString(" · "), color = p.ink3, fontSize = 12.sp)
        if (!s.hideCounts) {
            val hearts = model.postReactions[post.id].orEmpty().size
            val reposts = model.posts.count { it.repostOf == post.id }
            val counts = listOfNotNull(hearts.takeIf { it > 0 }?.let { "♥ $it" }, reposts.takeIf { it > 0 }?.let { countLabel(it, "repost") })
            if (counts.isNotEmpty()) Text(counts.joinToString(" · "), color = p.ink2, fontSize = 13.sp)
        }
    }
}

@Composable
private fun StageReplyRow(reply: ThreadReply, row: StagePlan.Row, plan: StagePlan.Result, s: StagePlan.Settings,
    authors: Map<String, StageAuthor>, editing: Boolean, onBlackout: (List<IntRange>) -> Unit) {
    val p = LocalChorusPalette.current
    val lead = reply.authorIds.firstOrNull()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StageFace(lead, plan, s, authors, 24)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val names = reply.authorIds.map { plan.names[it] ?: authors[it]?.name ?: "Someone" }.joinToString(" & ")
                .ifBlank { reply.authorNames.joinToString(" & ").ifBlank { "Someone" } }
            val color = lead?.let(plan.nameColors::get) ?: lead?.takeIf { it !in plan.names }?.let(authors::get)?.color
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(names, color = color?.let { tonesOf(it).name } ?: p.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                row.at?.let { Text(compactAgo(it), color = p.ink3, fontSize = 11.sp) }
            }
            if (reply.cw != null) Text("Content warning: ${reply.cw}", color = p.ink3, fontSize = 13.sp)
            else StageText(reply.text, row.blackout, s.blackout[reply.id].orEmpty(), editing,
                TextStyle(color = p.ink, fontSize = 14.sp, lineHeight = 20.sp), onBlackout)
        }
    }
}

/** A face on a post's stage: grey (or a fake name's colour) when the name is hidden or changed. */
@Composable
private fun StageFace(id: String?, plan: StagePlan.Result, s: StagePlan.Settings, authors: Map<String, StageAuthor>, size: Int) {
    val author = id?.let(authors::get)
    val label = id?.let(plan.names::get)
    val concealed = s.blurAvatars || label != null
    Avatar(if (concealed) (label ?: author?.name ?: "?").removePrefix("Member ").take(1).uppercase() else author?.glyph ?: "?",
        if (concealed) id?.let(plan.nameColors::get)?.takeIf { !s.blurAvatars } ?: "#A09184" else author?.color ?: "#A09184", size.dp,
        avatarBlob = if (concealed) null else author?.avatarBlob, ring = false)
}
