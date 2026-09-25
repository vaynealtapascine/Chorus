package garden.vayne.chorus.ui

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import garden.vayne.chorus.data.ChatChannel
import garden.vayne.chorus.data.ChatMessage
import garden.vayne.chorus.data.ChatAttachment
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.Blobs
import garden.vayne.chorus.data.ForeignAuthor
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.StagePlan
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.Tokens
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Screenshot view over already visible channel messages. All changes here are render-only. */
@Composable
internal fun ChatStage(chorus: Chorus, channel: ChatChannel, messages: List<ChatMessage>, model: Model,
    foreignAuthors: Map<String, ForeignAuthor>, capturing: Boolean,
    hasOlder: Boolean, loadingOlder: Boolean, onLoadOlder: () -> Unit, onCapture: (Boolean) -> Unit,
    onClose: () -> Unit) {
    val hostPalette = LocalChorusPalette.current
    var selected by rememberSaveable(channel.id) { mutableStateOf<List<String>>(emptyList()) }
    var unselected by rememberSaveable(channel.id) { mutableStateOf("context") }
    var advanced by rememberSaveable(channel.id) { mutableStateOf(false) }
    var redactNames by rememberSaveable(channel.id) { mutableStateOf(false) }
    var timeMode by rememberSaveable(channel.id) { mutableStateOf("real") }
    var shiftText by rememberSaveable(channel.id) { mutableStateOf("") }
    var startAt by rememberSaveable(channel.id) { mutableStateOf(System.currentTimeMillis()) }
    var fakeNames by rememberSaveable(channel.id) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var onlyMembers by rememberSaveable(channel.id) { mutableStateOf<List<String>>(emptyList()) }
    var replyDepth by rememberSaveable(channel.id) { mutableStateOf<Int?>(null) }
    var blurAttachments by rememberSaveable(channel.id) { mutableStateOf(false) }
    var hideHeader by rememberSaveable(channel.id) { mutableStateOf(false) }
    var hideReplyBars by rememberSaveable(channel.id) { mutableStateOf(false) }
    var style by rememberSaveable(channel.id) { mutableStateOf("chorus") }
    var theme by rememberSaveable(channel.id) { mutableStateOf("auto") }
    var blurAvatars by rememberSaveable(channel.id) { mutableStateOf(false) }
    var saveName by rememberSaveable(channel.id) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val actions = rememberCoroutineScope()
    var pillVisible by remember { mutableStateOf(true) }
    val p = when (theme) { "light" -> Tokens.Light; "dark" -> Tokens.Dark; else -> hostPalette }
    val activity = LocalContext.current as? Activity
    DisposableEffect(activity, capturing) {
        val bars = activity?.let { WindowInsetsControllerCompat(it.window, it.window.decorView) }
        if (capturing) bars?.hide(WindowInsetsCompat.Type.statusBars())
        onDispose { if (capturing) bars?.show(WindowInsetsCompat.Type.statusBars()) }
    }
    LaunchedEffect(capturing) {
        if (capturing) {
            pillVisible = true
            delay(2_000)
            pillVisible = false
        }
    }
    val settings = StagePlan.Settings(selected.toSet(), if (capturing) unselected else "visible",
        redactNames, fakeNames, timeMode,
        shiftText.toIntOrNull() ?: 0, onlyMembers.toSet(), replyDepth, blurAttachments, hideHeader, hideReplyBars,
        style, blurAvatars, startAt, theme)
    val plan = remember(messages, settings) { StagePlan.forMessages(messages, settings) }
    val byId = remember(messages) { messages.associateBy { it.id } }
    val missingSelected = remember(messages, selected) { StagePlan.missingSelected(selected.toSet(), messages) }
    val authorIds = remember(messages) { messages.flatMap { it.authors }.distinct() }
    val saved = model.savedStages.filter { it.channelId == channel.id }

    CompositionLocalProvider(LocalChorusPalette provides p) {
    Column(Modifier.fillMaxSize().background(p.bg).then(if (capturing) Modifier.statusBarsPadding() else Modifier)) {
        if (!capturing) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("Back") }
                Text("Stage · #${channel.name}", color = p.ink, fontWeight = FontWeight.SemiBold)
                TextButton(enabled = missingSelected.isEmpty(), onClick = { onCapture(true) }) { Text("Capture") }
            }
            Text(if (selected.isEmpty()) "Tap messages to pick them. With nothing picked, all show."
                else "${selected.size} picked · tap to add or remove.", color = p.ink2, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp))
            if (missingSelected.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("${missingSelected.size} picked ${if (missingSelected.size == 1) "message is" else "messages are"} not loaded. Load older messages or clear those picks.",
                    color = p.warn, fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { selected = selected.filterNot { it in missingSelected } }) { Text("Clear") }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                JournalChoice("Fold", unselected == "context") { unselected = "context" }
                JournalChoice("Hide", unselected == "hidden") { unselected = "hidden" }
                JournalChoice("Show", unselected == "visible") { unselected = "visible" }
                TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Less" else "Advanced") }
            }
            if (advanced) Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LazyRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(listOf("chorus" to "Chorus", "discord" to "Discord-ish", "bubbles" to "Bubbles", "card" to "Card",
                        "transcript" to "Transcript", "minimal" to "Minimal")) { (value, label) ->
                        JournalChoice(label, style == value) { style = value }
                    }
                }
                LazyRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(listOf("auto" to "Auto colors", "light" to "Light", "dark" to "Dark")) { (value, label) ->
                        JournalChoice(label, theme == value) { theme = value }
                    }
                }
                JournalChoice("Hide real names", redactNames) { redactNames = !redactNames }
                LazyRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(listOf("real" to "Real times", "hide" to "Hide times", "shift" to "Shift", "start" to "Start at")) { (value, label) ->
                        JournalChoice(label, timeMode == value) { timeMode = value }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    JournalChoice("Conceal avatars", blurAvatars) { blurAvatars = !blurAvatars }
                    JournalChoice("Conceal attachments", blurAttachments) { blurAttachments = !blurAttachments }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    JournalChoice("Hide channel name", hideHeader) { hideHeader = !hideHeader }
                    JournalChoice("Hide reply bars", hideReplyBars) { hideReplyBars = !hideReplyBars }
                }
                if (timeMode == "shift") OutlinedTextField(shiftText,
                    { shiftText = it.filter { c -> c.isDigit() || c == '-' }.take(7) },
                    label = { Text("Shift times by minutes") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                if (timeMode == "start") {
                    val ctx = LocalContext.current
                    TextButton(onClick = {
                        val day = Calendar.getInstance().apply { timeInMillis = startAt }
                        DatePickerDialog(ctx, { _, year, month, date ->
                            val chosen = Calendar.getInstance().apply {
                                timeInMillis = startAt
                                set(Calendar.YEAR, year); set(Calendar.MONTH, month); set(Calendar.DAY_OF_MONTH, date)
                            }
                            TimePickerDialog(ctx, { _, hour, minute ->
                                chosen.set(Calendar.HOUR_OF_DAY, hour); chosen.set(Calendar.MINUTE, minute)
                                chosen.set(Calendar.SECOND, 0); chosen.set(Calendar.MILLISECOND, 0)
                                startAt = chosen.timeInMillis
                            }, day.get(Calendar.HOUR_OF_DAY), day.get(Calendar.MINUTE), false).show()
                        }, day.get(Calendar.YEAR), day.get(Calendar.MONTH), day.get(Calendar.DAY_OF_MONTH)).show()
                    }) { Text("First shown time: ${DateFormat.getDateTimeInstance().format(Date(startAt))}") }
                }
                for (id in authorIds) {
                    val realName = model.member(id)?.shownName ?: foreignAuthors[id]?.name ?: "Someone"
                    JournalChoice("Only $realName", id in onlyMembers) {
                        onlyMembers = if (id in onlyMembers) onlyMembers - id else onlyMembers + id
                    }
                    OutlinedTextField(fakeNames[id].orEmpty(), { value ->
                        fakeNames = fakeNames.toMutableMap().apply {
                            if (value.isBlank()) remove(id) else put(id, value)
                        }
                    }, label = { Text("Show $realName as (optional)") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                }
                Text(if (onlyMembers.isEmpty()) "All authors shown" else "Only selected authors shown",
                    color = p.ink2, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
                LazyRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(listOf(null to "All replies", 0 to "No replies", 1 to "Direct", 2 to "2 deep")) { (depth, label) ->
                        JournalChoice(label, replyDepth == depth) { replyDepth = depth }
                    }
                }
            }
            if (saved.isNotEmpty()) LazyRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(saved, key = { it.id }) { stage ->
                    val supported = StagePlan.supported(stage.definition)
                    TextButton(enabled = !busy && supported != null, onClick = {
                        val loaded = supported ?: return@TextButton
                        selected = loaded.selected.toList(); unselected = loaded.unselected
                        redactNames = loaded.redactNames; fakeNames = loaded.fakeNames
                        timeMode = loaded.timeMode
                        shiftText = if (loaded.timeMode == "shift") loaded.shiftMinutes.toString() else ""
                        startAt = if (loaded.timeMode == "start") loaded.startAt else System.currentTimeMillis()
                        onlyMembers = loaded.onlyMembers.toList(); replyDepth = loaded.replyDepth
                        blurAttachments = loaded.blurAttachments
                        hideHeader = loaded.hideHeader; hideReplyBars = loaded.hideReplyBars
                        style = loaded.style
                        theme = loaded.theme
                        blurAvatars = loaded.blurAvatars
                        error = null
                    }) { Text(if (supported == null) "${stage.name} · web view" else stage.name) }
                    if (advanced) TextButton(enabled = !busy, onClick = {
                        busy = true; error = null
                        actions.launch {
                            try { chorus.create("stage.delete", stage.id, JSONObject()) }
                            catch (e: Exception) { error = e.message ?: "Could not delete this stage." }
                            finally { busy = false }
                        }
                    }) { Text("Remove") }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(saveName, { saveName = it }, label = { Text("Name this stage") },
                    singleLine = true, modifier = Modifier.weight(1f), enabled = !busy)
                TextButton(enabled = !busy && saveName.isNotBlank(), onClick = {
                    val name = saveName.trim()
                    val definition = StagePlan.definition(channel.id, settings.copy(unselected = unselected))
                    busy = true; error = null
                    actions.launch {
                        try {
                            chorus.create("stage.save", chorus.newId(), JSONObject().put("name", name).put("definition", definition))
                            saveName = ""
                        } catch (e: Exception) { error = e.message ?: "Could not save this stage." }
                        finally { busy = false }
                    }
                }) { Text("Save") }
            }
            if (error != null) Text(error.orEmpty(), color = p.danger, modifier = Modifier.padding(horizontal = 16.dp))
        }
        if (messages.isEmpty()) Text("No messages to stage in this channel.", color = p.ink2,
            modifier = Modifier.padding(16.dp))
        if (!capturing && hasOlder) TextButton(enabled = !loadingOlder, onClick = onLoadOlder,
            modifier = Modifier.padding(horizontal = 12.dp)) {
            Text(if (loadingOlder) "Loading…" else "Load 100 older messages")
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 390.dp).fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(if (style == "discord") 0.dp else if (style == "card") 12.dp else 8.dp)) {
                if (!hideHeader) item(key = "channel-header") {
                    val firstAt = plan.rows.firstOrNull { it.id != null }?.at
                    val date = firstAt?.let { " · ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it))}" }.orEmpty()
                    Text("#${channel.name}$date", color = p.ink2, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                }
                itemsIndexed(plan.rows, key = { index, row -> row.id ?: "context:$index" }) { _, row ->
                    if (row.id == null) {
                        Text("${row.contextCount} ${if (row.contextCount == 1) "message" else "messages"}", color = p.ink3, fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth().background(p.surface).padding(12.dp))
                    } else {
                        val message = byId[row.id] ?: return@itemsIndexed
                        var revealed by rememberSaveable(message.id) { mutableStateOf(false) }
                        val names = message.authors.map { id -> plan.names[id] ?: model.member(id)?.shownName
                            ?: foreignAuthors[id]?.name ?: "Someone" }.joinToString(" & ")
                        val pickMark = if (!capturing && message.id in selected) "✓ " else ""
                        val mine = message.accountId == chorus.device?.accountId
                        val pad = when (style) {
                            "transcript" -> 4.dp
                            "discord" -> 6.dp
                            "minimal" -> 8.dp
                            "card" -> 16.dp
                            else -> 12.dp
                        }
                        Box(Modifier.fillMaxWidth(), contentAlignment = if (style == "bubbles" && mine)
                            Alignment.CenterEnd else Alignment.CenterStart) {
                        Column((if (style == "bubbles") Modifier.widthIn(max = 320.dp) else Modifier.fillMaxWidth())
                            .then(if (style == "card") Modifier.border(1.dp, p.line, RoundedCornerShape(14.dp)) else Modifier)
                            .then(if (style == "bubbles") Modifier.background(if (mine) p.accentSoft else p.surface2,
                                RoundedCornerShape(18.dp))
                                else if (style == "card") Modifier.background(p.surface, RoundedCornerShape(14.dp))
                                else Modifier.background(if (style == "transcript" || style == "discord") p.bg else p.surface))
                            .clickable(enabled = !capturing) {
                            selected = if (message.id in selected) selected - message.id else selected + message.id
                        }.padding(pad),
                            verticalArrangement = Arrangement.spacedBy(if (style == "transcript" || style == "discord") 1.dp else 4.dp)) {
                            if (style in setOf("chorus", "discord", "bubbles", "card")) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                val lead = message.authors.firstOrNull()
                                val member = lead?.let(model::member)
                                val concealed = blurAvatars || (lead != null && lead in plan.names)
                                Avatar(if (concealed) "•" else member?.glyph ?: "?",
                                    if (concealed) "#A09184" else member?.color ?: "#A09184",
                                    if (style == "card") 44.dp else 32.dp,
                                    avatarBlob = if (concealed) null else member?.avatarBlob, ring = style != "discord")
                                Text("$pickMark$names", color = p.ink, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f).padding(start = 8.dp))
                                if (row.at != null) Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(row.at)),
                                    color = p.ink3, fontSize = 11.sp)
                            }
                            if (style == "transcript" && message.cw != null)
                                Text("$pickMark$names:", color = p.ink, fontWeight = FontWeight.SemiBold)
                            val parent = message.replyTo?.let(byId::get)
                            if (row.replyShown && !hideReplyBars && parent != null)
                                Text(StagePlan.replyPreview(parent, plan.names) { id ->
                                    model.member(id)?.shownName ?: foreignAuthors[id]?.name ?: "Someone"
                                }, color = p.ink3, fontSize = 11.sp, maxLines = 1)
                            if (message.cw != null) Text("Content warning: ${message.cw} · ${if (revealed) "Hide" else "Show"}",
                                color = p.accent, modifier = Modifier.clickable { revealed = !revealed })
                            if (message.cw == null || revealed) {
                                Text(when (style) {
                                    "transcript" -> "${if (message.cw == null) "$pickMark$names: " else ""}${message.text}"
                                    "minimal" -> "$pickMark${message.text}"
                                    else -> message.text
                                }, color = p.ink)
                                for (attachment in message.attachments) StageAttachment(chorus, attachment, blurAttachments)
                            }
                        }
                        }
                    }
                }
            }
            if (capturing && !pillVisible) Box(Modifier.fillMaxSize().clickable { pillVisible = true })
            if (capturing && pillVisible) Text("Done", color = p.accent,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).background(p.surface)
                    .clickable { onCapture(false) }.padding(horizontal = 16.dp, vertical = 8.dp))
        }
    }
    }
}

/** Stage never reveals a spoiler; conceal mode uses a solid card on Android 10 too. */
@Composable
private fun StageAttachment(chorus: Chorus, attachment: ChatAttachment, conceal: Boolean) {
    val p = LocalChorusPalette.current
    if (attachment.spoiler || conceal) {
        Text(if (attachment.spoiler) "Spoiler attachment" else "Attachment concealed", color = p.ink2,
            fontSize = 12.sp, modifier = Modifier.fillMaxWidth().background(p.surface2).padding(12.dp))
        return
    }
    Text("📎 ${attachment.filename}", color = p.ink2, fontSize = 12.sp)
    if (!attachment.mime.startsWith("image/")) return
    val ctx = LocalContext.current
    val hash = attachment.thumbHash ?: attachment.blobHash
    val device = chorus.device
    val bitmap by produceState<android.graphics.Bitmap?>(null, hash, device?.session) {
        value = withContext(Dispatchers.IO) { Blobs.image(ctx.applicationContext, hash, device) }
    }
    if (bitmap == null) Text("Image unavailable offline or still loading.", color = p.ink3, fontSize = 12.sp)
    else Image(bitmap!!.asImageBitmap(), contentDescription = attachment.altText.ifBlank { attachment.filename },
        contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().height(200.dp))
}
