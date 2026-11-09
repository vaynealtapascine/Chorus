package garden.vayne.chorus.ui

import android.app.Activity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import garden.vayne.chorus.data.Blobs
import garden.vayne.chorus.data.ChatAttachment
import garden.vayne.chorus.data.ChatChannel
import garden.vayne.chorus.data.ChatMessage
import garden.vayne.chorus.data.ChatPresentation
import garden.vayne.chorus.data.ChatReaction
import garden.vayne.chorus.data.ChatThreads
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.ForeignAuthor
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.SavedStage
import garden.vayne.chorus.data.StagePlan
import garden.vayne.chorus.designsystem.LocalChorusDark
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Hides the status bar while capturing, and the capture pills' two-second fade. */
@Composable
internal fun rememberCapturePills(capturing: Boolean): Pair<Boolean, () -> Unit> {
    val activity = LocalContext.current as? Activity
    DisposableEffect(activity, capturing) {
        val bars = activity?.let { WindowInsetsControllerCompat(it.window, it.window.decorView) }
        if (capturing) bars?.hide(WindowInsetsCompat.Type.statusBars())
        onDispose { if (capturing) bars?.show(WindowInsetsCompat.Type.statusBars()) }
    }
    var shown by remember { mutableIntStateOf(0) }
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(capturing, shown) {
        if (capturing) { visible = true; delay(2_000); visible = false }
    }
    return visible to { shown++ }
}

/** Screenshot view over already visible channel messages (F8.2–F8.9). All changes here are render-only. */
@Composable
internal fun ChatStage(chorus: Chorus, channel: ChatChannel, messages: List<ChatMessage>, model: Model,
    foreignAuthors: Map<String, ForeignAuthor>, capturing: Boolean,
    hasOlder: Boolean, loadingOlder: Boolean, onLoadOlder: () -> Unit, onCapture: (Boolean) -> Unit,
    onClose: () -> Unit, initialSelected: List<String> = emptyList()) {
    val host = LocalChorusPalette.current
    val hostDark = LocalChorusDark.current
    var s by rememberSaveable(channel.id, stateSaver = StageSaver) {
        mutableStateOf(StagePlan.Settings(selected = initialSelected.toSet()))
    }
    var tab by rememberSaveable(channel.id) { mutableStateOf(StageTab.Select) }
    var saving by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val actions = rememberCoroutineScope()
    val (pillsVisible, showPills) = rememberCapturePills(capturing)

    // picking shows every message, the unpicked dimmed; the other tools show what Capture will
    val picking = !capturing && tab == StageTab.Select
    val planned = if (picking && s.selected.isNotEmpty()) s.copy(unselected = "visible") else s
    val plan = remember(messages, planned) { StagePlan.forMessages(messages, planned) }
    val (p, dark) = stagePalette(s, plan.palette)
    val threads = remember(model) { ChatThreads.summaries(model) }
    var timing by remember { mutableStateOf<Pair<String, Long>?>(null) }
    val byId = remember(messages) { messages.associateBy { it.id } }
    val missingSelected = remember(messages, s.selected) { StagePlan.missingSelected(s.selected, messages) }
    val authors = remember(messages, model, foreignAuthors) {
        messages.flatMap { it.authors }.distinct().map { stageAuthor(it, model, foreignAuthors) }
    }
    val authorById = remember(authors) { authors.associateBy { it.id } }
    val account = chorus.device?.accountId.orEmpty()
    val aligned = remember(model) {
        ChatPresentation.aligned(model, model.current.filter { it.subjectType == "member" }.map { it.subjectId })
    }
    val saved = model.savedStages.filter { it.channelId == channel.id }
    val shown = plan.rows.count { it.id != null }
    val picked = s.selected.count { it in byId }
    val subtitle = "#${channel.name} · " + when (tab) {
        StageTab.Select, StageTab.Filters ->
            if (s.selected.isEmpty()) countLabel(messages.size, "message") else "$picked of ${messages.size} picked"
        StageTab.Style -> "${stageStyleLabel(s.style)} · ${s.width} width"
        StageTab.Redact -> if (stageRedacted(s)) "redacted" else countLabel(shown, "message")
        StageTab.Names -> if (s.fakeNames.isNotEmpty()) countLabel(s.fakeNames.size, "name") + " changed" else countLabel(shown, "message")
    }
    val list = rememberLazyListState()
    // open at the message Stage started from, else at the newest
    LaunchedEffect(channel.id, messages.isNotEmpty()) {
        if (messages.isEmpty()) return@LaunchedEffect
        val at = initialSelected.firstOrNull()?.let { id -> plan.rows.indexOfFirst { it.id == id } }?.takeIf { it >= 0 }
        list.scrollToItem((at ?: plan.rows.lastIndex).coerceAtLeast(0) + (if (s.hideHeader) 0 else 1) + (if (hasOlder) 1 else 0))
    }

    Column(Modifier.fillMaxSize().background(p.bg)) {
        if (!capturing) StageBar(subtitle, onClose, { error = null; saving = true }, { onCapture(true) }, missingSelected.isEmpty())
        Box(Modifier.weight(1f).fillMaxWidth().then(if (capturing) Modifier.statusBarsPadding() else Modifier),
            contentAlignment = Alignment.TopCenter) {
            CompositionLocalProvider(LocalChorusPalette provides p, LocalChorusDark provides dark) {
                if (messages.isEmpty()) EmptyState(R.drawable.ic_chat, "No messages")
                LazyColumn(Modifier.fillMaxSize().stageWidth(s.width).padding(horizontal = 12.dp), state = list,
                    verticalArrangement = Arrangement.spacedBy(if (s.style == "discord") 2.dp else if (s.style == "card") 12.dp else 8.dp)) {
                    if (!capturing && hasOlder) item(key = "older") {
                        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                            StageChip(if (loadingOlder) "Loading…" else if (missingSelected.isEmpty()) "Load older"
                                else "Load older (${missingSelected.size})") { if (!loadingOlder) onLoadOlder() }
                        }
                    }
                    if (!s.hideHeader) item(key = "channel-header") {
                        val firstAt = plan.rows.firstOrNull { it.id != null }?.at
                        Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Glyph(R.drawable.ic_hash, p.ink3, 20.dp)
                            Text(channel.name, color = p.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            if (firstAt != null && !s.hideDates) Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(firstAt)), color = p.ink3, fontSize = 12.sp)
                        }
                    }
                    itemsIndexed(plan.rows, key = { index, row -> row.id ?: "context:$index" }) { _, row ->
                        if (row.id == null) {
                            StageContextRow(row.contextIds.flatMap { byId[it]?.authors.orEmpty() }.map { id ->
                                plan.names[id] ?: authorById[id]?.name ?: "Someone"
                            }, row.contextCount)
                            return@itemsIndexed
                        }
                        val message = byId[row.id] ?: return@itemsIndexed
                        val on = message.id in s.selected
                        Row(Modifier.fillMaxWidth().then(if (picking) Modifier.toggleable(on, role = Role.Checkbox) {
                                s = s.copy(selected = if (on) s.selected - message.id else s.selected + message.id)
                            } else Modifier).alpha(if (!capturing && !row.selected) .45f else 1f),
                            verticalAlignment = Alignment.CenterVertically) {
                            if (picking) { StageCheck(on); Spacer(Modifier.width(8.dp)) }
                            Box(Modifier.weight(1f)) {
                                StageMessage(chorus, message, row, plan, s, authorById,
                                    ChatPresentation.own(message, account, aligned), byId[message.replyTo],
                                    model.messageReactions[message.id].orEmpty(), threads[message.id]?.replies ?: 0,
                                    editingText = !capturing && tab == StageTab.Redact,
                                    onBlackout = { ranges -> s = s.copy(blackout = if (ranges.isEmpty()) s.blackout - message.id else s.blackout + (message.id to ranges)) },
                                    onTime = if (!capturing && s.timeMode == "manual") { at -> timing = message.id to at } else null)
                            }
                        }
                    }
                    item(key = "end") { Spacer(Modifier.height(16.dp)) }
                }
                if (capturing) StageCapturePills(pillsVisible, showPills) { onCapture(false) }
            }
        }
        if (!capturing) {
            StagePanel {
                when (tab) {
                    StageTab.Select -> {
                        StageLabel("Messages you didn’t pick")
                        val modes = listOf("hidden", "context", "visible")
                        Segmented(listOf("Hidden", "Context only", "Shown"), modes.indexOf(s.unselected), accent = true) {
                            s = s.copy(unselected = modes[it])
                        }
                        val only = authors.firstOrNull { it.id in aligned } ?: authors.firstOrNull()
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StageChip("Pick all") { s = s.copy(selected = messages.mapTo(LinkedHashSet()) { it.id }) }
                            StageChip("Pick none") { s = s.copy(selected = emptySet()) }
                            if (only != null && authors.size > 1) StageChip("Only ${only.name}") {
                                s = s.copy(selected = messages.filter { only.id in it.authors }.mapTo(LinkedHashSet()) { it.id })
                            }
                        }
                        if (missingSelected.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (hasOlder) StageChip(if (loadingOlder) "Loading…" else "Load older (${missingSelected.size})") {
                                if (!loadingOlder) onLoadOlder()
                            }
                            StageChip("Clear ${missingSelected.size}") { s = s.copy(selected = s.selected - missingSelected) }
                        }
                    }
                    StageTab.Filters -> {
                        if (authors.size > 1) {
                            StageLabel("Only these members")
                            StageMemberChips(authors, s.onlyMembers) { s = s.copy(onlyMembers = it) }
                        }
                        Column {
                            ToggleRow("Hide reactions", s.hideReactions, inset = 0.dp) { s = s.copy(hideReactions = it) }
                            ToggleRow("Hide reply bars", s.hideReplyBars, inset = 0.dp) { s = s.copy(hideReplyBars = it) }
                            ToggleRow("Hide thread previews", s.hideThreadPreviews, inset = 0.dp) { s = s.copy(hideThreadPreviews = it) }
                        }
                        StageRow("Crop reply chains") {
                            val depth = s.replyDepth
                            StageStepper(depth?.toString() ?: "All", "Reply levels",
                                minus = { s = s.copy(replyDepth = if (depth == null) 2 else (depth - 1).coerceAtLeast(0)) }.takeIf { depth != 0 },
                                plus = { s = s.copy(replyDepth = depth?.let { if (it >= 9) null else it + 1 }) }.takeIf { depth != null })
                        }
                    }
                    StageTab.Style -> StageStylePanel(s, hostDark) { s = it }
                    StageTab.Redact -> StageRedactPanel(s, { s = it })
                    StageTab.Names -> StageNamesPanel(s, authors, plan.rows.firstOrNull { it.id != null }?.id?.let(byId::get)?.occurredAt) { s = it }
                }
                if (error != null && !saving) Text(error.orEmpty(), color = host.danger, fontSize = 13.sp)
            }
            StageTabs(tab) { tab = it }
        }
    }
    timing?.let { (id, at) ->
        DateTimeDrawer(at, { picked -> s = s.copy(manualAt = s.manualAt + (id to picked)); timing = null }, { timing = null }, "Shown time")
    }
    if (saving) {
        fun summary(stage: SavedStage): String {
            val loaded = StagePlan.supported(stage.definition) ?: return "Made on the web"
            return listOfNotNull("#${channel.name}",
                countLabel(loaded.selected.size.takeIf { it > 0 } ?: messages.size, "message"),
                stageStyleLabel(loaded.style),
                "names changed".takeIf { loaded.fakeNames.isNotEmpty() }, "redacted".takeIf { stageRedacted(loaded) }).joinToString(" · ")
        }
        StageSaveDrawer(saved.firstOrNull { StagePlan.supported(it.definition) == s }?.name.orEmpty(),
            saved.map { StageSavedRow(it, R.drawable.ic_message, summary(it), StagePlan.supported(it.definition) != null) },
            busy, error, onSave = { name ->
                val id = saved.firstOrNull { it.name.equals(name, ignoreCase = true) }?.id ?: chorus.newId()
                busy = true; error = null
                actions.launch {
                    try {
                        chorus.create("stage.save", id, JSONObject().put("name", name).put("definition", StagePlan.definition(channel.id, s)))
                        saving = false
                    } catch (e: Exception) { error = e.message ?: "Could not save this stage." }
                    finally { busy = false }
                }
            }, onOpen = { stage ->
                StagePlan.supported(stage.definition)?.let { s = it }
                saving = false
            }, onDelete = { stage ->
                busy = true; error = null
                actions.launch {
                    try { chorus.create("stage.delete", stage.id, JSONObject()) }
                    catch (e: Exception) { error = e.message ?: "Could not delete this stage." }
                    finally { busy = false }
                }
            }, onDismiss = { saving = false })
    }
}

/** One message as the chosen preset draws it. */
@Composable
private fun StageMessage(chorus: Chorus, message: ChatMessage, row: StagePlan.Row, plan: StagePlan.Result,
    s: StagePlan.Settings, authors: Map<String, StageAuthor>, mine: Boolean, parent: ChatMessage?,
    reactions: List<ChatReaction>, replies: Int, editingText: Boolean, onBlackout: (List<IntRange>) -> Unit,
    onTime: ((Long) -> Unit)?) {
    val p = LocalChorusPalette.current
    var revealed by rememberSaveable(message.id) { mutableStateOf(false) }
    val lead = message.authors.firstOrNull()
    val names = message.authors.map { plan.names[it] ?: authors[it]?.name ?: "Someone" }.joinToString(" & ").ifBlank { "Someone" }
    val concealed = s.blurAvatars || (lead != null && lead in plan.names)
    val author = lead?.let(authors::get)
    // a fake name's colour (D-050) or a placeholder's grey stands in for the member's own
    val fakeColor = lead?.let(plan.nameColors::get)
    val nameColor = when {
        fakeColor != null -> tonesOf(fakeColor).name
        lead != null && lead in plan.names -> p.ink2
        else -> author?.let { tonesOf(it.color).name } ?: p.ink
    }
    val time = row.at?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) }

    @Composable fun Face(size: Dp) = Avatar(if (concealed) names.removePrefix("Member ").take(1).uppercase() else author?.glyph ?: "?",
        if (concealed) fakeColor?.takeIf { !s.blurAvatars } ?: "#A09184" else author?.color ?: "#A09184", size,
        avatarBlob = if (concealed) null else author?.avatarBlob, ring = false)

    @Composable fun NameLine() = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(names, color = nameColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        if (time != null) Text(time, color = if (onTime != null) p.accent else p.ink3, fontSize = 11.sp,
            modifier = if (onTime != null) Modifier.clickable(onClickLabel = "Change this time") { row.at?.let(onTime) } else Modifier)
    }

    @Composable fun Extras() {
        if (replies > 0 && !s.hideThreadPreviews) Text(ChatThreads.repliesLabel(replies), color = p.accent, fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold)
        if (reactions.isNotEmpty() && !s.hideReactions) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (r in reactions) Text("${r.emoji} ${r.memberIds.size}", color = p.ink2, fontSize = 12.sp,
                modifier = Modifier.background(p.surface3, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
        }
    }

    @Composable fun Body(prefix: String = "") {
        if (row.replyShown && !s.hideReplyBars && parent != null)
            Text(StagePlan.replyPreview(parent, plan.names) { id -> authors[id]?.name ?: "Someone" },
                color = p.ink3, fontSize = 11.sp, maxLines = 1)
        if (message.cw != null) Text("Content warning: ${message.cw} · ${if (revealed) "Hide" else "Show"}",
            color = p.accent, fontSize = 14.sp, modifier = Modifier.clickable { revealed = !revealed })
        if (message.cw == null || revealed) {
            if (prefix.isNotEmpty() || message.text.isNotBlank()) StageText(message.text, row.blackout, s.blackout[message.id].orEmpty(),
                editingText, androidx.compose.ui.text.TextStyle(color = p.ink, fontSize = 15.sp, lineHeight = 21.sp), onBlackout, prefix)
            for (attachment in message.attachments) StageAttachment(chorus, attachment, s.blurAttachments)
        }
        Extras()
    }

    when (s.style) {
        "chorus", "bubbles" -> {
            val bubble = RoundedCornerShape(16.dp)
            if (mine) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                Column(Modifier.widthIn(max = 300.dp).background(if (s.style == "bubbles") p.accent.copy(alpha = .9f) else p.bubbleOut, bubble)
                    .padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (s.style == "chorus") NameLine()
                    CompositionLocalProvider(LocalChorusPalette provides if (s.style == "bubbles") p.copy(ink = p.onAccent, ink3 = p.onAccent.copy(alpha = .7f)) else p) { Body() }
                }
            } else Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s.style == "chorus") Face(32.dp)
                Column(Modifier.widthIn(max = 300.dp).background(p.surface2, bubble).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (s.style == "chorus") NameLine()
                    Body()
                }
            }
        }
        "discord" -> Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Face(36.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) { NameLine(); Body() }
        }
        "card" -> Column(Modifier.fillMaxWidth().background(p.surface, RoundedCornerShape(14.dp))
            .border(1.dp, p.line, RoundedCornerShape(14.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { Face(44.dp); NameLine() }
            Body()
        }
        "transcript" -> Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Body(if (message.cw == null) "$names: " else "")
            if (message.cw != null) Text("$names:", color = p.ink, fontWeight = FontWeight.SemiBold)
        }
        else -> Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Box(Modifier.width(3.dp).height(40.dp).background(nameColor))
            Column(Modifier.weight(1f).padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) { NameLine(); Body() }
        }
    }
}

/** Stage never reveals a spoiler; conceal mode uses a solid card on Android 10 too. */
@Composable
internal fun StageAttachment(chorus: Chorus, attachment: ChatAttachment, conceal: Boolean) {
    val p = LocalChorusPalette.current
    if (attachment.spoiler || conceal) {
        Text(if (attachment.spoiler) "Spoiler attachment" else "Attachment concealed", color = p.ink2,
            fontSize = 12.sp, modifier = Modifier.fillMaxWidth().background(p.surface3, RoundedCornerShape(8.dp)).padding(12.dp))
        return
    }
    if (!attachment.mime.startsWith("image/")) { Text("📎 ${attachment.filename}", color = p.ink2, fontSize = 12.sp); return }
    val ctx = LocalContext.current
    val hash = attachment.thumbHash ?: attachment.blobHash
    val device = chorus.device
    val bitmap by produceState<android.graphics.Bitmap?>(null, hash, device?.session) {
        value = withContext(Dispatchers.IO) { Blobs.image(ctx.applicationContext, hash, device) }
    }
    if (bitmap == null) Box(Modifier.fillMaxWidth().height(120.dp).background(p.surface3, RoundedCornerShape(8.dp)))
    else Image(bitmap!!.asImageBitmap(), contentDescription = attachment.altText.ifBlank { attachment.filename },
        contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().height(200.dp))
}
