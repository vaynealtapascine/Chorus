package garden.vayne.chorus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.ChatChannel
import garden.vayne.chorus.data.ChatMessage
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.ForeignAuthor
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.StagePlan
import garden.vayne.chorus.designsystem.LocalChorusPalette
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Screenshot view over already visible channel messages. All changes here are render-only. */
@Composable
internal fun ChatStage(chorus: Chorus, channel: ChatChannel, messages: List<ChatMessage>, model: Model,
    foreignAuthors: Map<String, ForeignAuthor>, capturing: Boolean, onCapture: (Boolean) -> Unit,
    onClose: () -> Unit) {
    val p = LocalChorusPalette.current
    var selected by rememberSaveable(channel.id) { mutableStateOf<List<String>>(emptyList()) }
    var unselected by rememberSaveable(channel.id) { mutableStateOf("context") }
    var advanced by rememberSaveable(channel.id) { mutableStateOf(false) }
    var redactNames by rememberSaveable(channel.id) { mutableStateOf(false) }
    var hideTimes by rememberSaveable(channel.id) { mutableStateOf(false) }
    var shiftText by rememberSaveable(channel.id) { mutableStateOf("") }
    var fakeNames by rememberSaveable(channel.id) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var onlyMembers by rememberSaveable(channel.id) { mutableStateOf<List<String>>(emptyList()) }
    var replyDepth by rememberSaveable(channel.id) { mutableStateOf<Int?>(null) }
    var saveName by rememberSaveable(channel.id) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val actions = rememberCoroutineScope()
    var pillVisible by remember { mutableStateOf(true) }
    LaunchedEffect(capturing) {
        if (capturing) {
            pillVisible = true
            delay(2_000)
            pillVisible = false
        }
    }
    val settings = StagePlan.Settings(selected.toSet(), if (capturing) unselected else "visible",
        redactNames, fakeNames, if (hideTimes) "hide" else if (shiftText.toIntOrNull() != null) "shift" else "real",
        shiftText.toIntOrNull() ?: 0, onlyMembers.toSet(), replyDepth)
    val plan = remember(messages, settings) { StagePlan.forMessages(messages, settings) }
    val byId = remember(messages) { messages.associateBy { it.id } }
    val authorIds = remember(messages) { messages.flatMap { it.authors }.distinct() }
    val saved = model.savedStages.filter { it.channelId == channel.id }

    Column(Modifier.fillMaxSize().background(p.bg).then(if (capturing) Modifier.statusBarsPadding() else Modifier)) {
        if (!capturing) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("Back") }
                Text("Stage · #${channel.name}", color = p.ink, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { onCapture(true) }) { Text("Capture") }
            }
            Text(if (selected.isEmpty()) "Tap messages to pick them. With nothing picked, all show."
                else "${selected.size} picked · tap to add or remove.", color = p.ink2, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                JournalChoice("Fold", unselected == "context") { unselected = "context" }
                JournalChoice("Hide", unselected == "hidden") { unselected = "hidden" }
                JournalChoice("Show", unselected == "visible") { unselected = "visible" }
                TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Less" else "Advanced") }
            }
            if (advanced) Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    JournalChoice("Hide real names", redactNames) { redactNames = !redactNames }
                    JournalChoice("Hide times", hideTimes) { hideTimes = !hideTimes }
                }
                OutlinedTextField(shiftText, { shiftText = it.filter { c -> c.isDigit() || c == '-' }.take(7) },
                    label = { Text("Shift times by minutes (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
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
                        hideTimes = loaded.timeMode == "hide"
                        shiftText = if (loaded.timeMode == "shift") loaded.shiftMinutes.toString() else ""
                        onlyMembers = loaded.onlyMembers.toList(); replyDepth = loaded.replyDepth
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
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 390.dp).fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(plan.rows, key = { it.id ?: "context:${it.hashCode()}" }) { row ->
                    if (row.id == null) {
                        Text("${row.contextCount} messages", color = p.ink3, fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth().background(p.surface).padding(12.dp))
                    } else {
                        val message = byId[row.id] ?: return@items
                        var revealed by rememberSaveable(message.id) { mutableStateOf(false) }
                        val names = message.authors.map { id -> plan.names[id] ?: model.member(id)?.shownName
                            ?: foreignAuthors[id]?.name ?: "Someone" }.joinToString(" & ")
                        Column(Modifier.fillMaxWidth().background(p.surface).clickable(enabled = !capturing) {
                            selected = if (message.id in selected) selected - message.id else selected + message.id
                        }.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${if (!capturing && message.id in selected) "✓ " else ""}$names", color = p.ink,
                                    fontWeight = FontWeight.SemiBold)
                                if (row.at != null) Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(row.at)),
                                    color = p.ink3, fontSize = 11.sp)
                            }
                            if (row.replyShown) Text("↪ reply", color = p.ink3, fontSize = 11.sp)
                            if (message.cw != null) Text("Content warning: ${message.cw} · ${if (revealed) "Hide" else "Show"}",
                                color = p.accent, modifier = Modifier.clickable { revealed = !revealed })
                            if (message.cw == null || revealed) {
                                Text(message.text, color = p.ink)
                                if (message.attachments.isNotEmpty()) Text("${message.attachments.size} attachment(s)",
                                    color = p.ink3, fontSize = 12.sp)
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
