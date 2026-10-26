package garden.vayne.chorus.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.Entry
import garden.vayne.chorus.data.Front
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.NamedHistory
import garden.vayne.chorus.designsystem.LocalChorusPalette
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import kotlinx.coroutines.launch

private data class HistoryItem(val id: String, val at: Long, val entries: List<Entry>, val note: String?,
    val retracted: Boolean, val editable: Boolean, val state: String? = null, val deleted: Boolean = false)

/** Effective history and separately editable saved entries, read offline through shared core. */
@Composable
fun History(chorus: Chorus, model: Model) {
    val p = LocalChorusPalette.current
    val ctx = LocalContext.current
    val actions = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var selected by rememberSaveable { mutableStateOf("") }
    var savedView by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var shown by remember(selected, savedView) { mutableIntStateOf(200) }
    var retry by remember { mutableIntStateOf(0) }
    var pending by remember { mutableStateOf<String?>(null) }
    val revision by chorus.frontHistoryRevision.collectAsState()
    val definitions = model.namedFronts?.fronts
    val account = chorus.device?.accountId.orEmpty()
    val front = selected.takeIf { definitions?.containsKey(it) == true } ?: account
    val configured = definitions != null
    val loaded by produceState<Result<NamedHistory?>?>(null, revision, front, retry, configured) {
        value = null
        value = if (configured) runCatching { chorus.frontHistory(front) } else Result.success(null)
    }
    val history = loaded?.getOrNull()?.takeIf { it.frontId == front }
    val loading = configured && loaded == null
    val readError = loaded?.exceptionOrNull()
    val rows = remember(model.switches, history, configured, savedView) {
        when {
            !configured -> model.switches.map { HistoryItem(it.id,it.occurredAt,
                if (it.retracted) it.entries else it.resultingFront,it.note,it.retracted,true) }
            savedView -> history?.saved.orEmpty().map { HistoryItem(it.id,it.occurredAt,
                if (it.retracted) it.entries else it.resultingFront,it.note,it.retracted,true) }
            else -> history?.changes.orEmpty().map { HistoryItem(it.id,it.occurredAt,
                it.entries.map { e -> e.displayEntry() },null,false,false,it.state,it.deleted) }
        }.asReversed()
    }
    val layerNames = model.namedFronts?.layers.orEmpty().associate { it.id to it.name }
    fun describe(row: HistoryItem): String = when {
        row.deleted -> "Deleted front"
        row.state == "unavailable" -> "Source unavailable"
        configured -> row.entries.joinToString(" & ") { e ->
            val name = model.subject(e.subjectType,e.subjectId)?.name ?: "Someone"
            "$name (${layerNames[e.level] ?: e.level})"
        }.ifEmpty { "No one recorded" }
        else -> model.frontLabel(row.entries)
    }
    fun editTime(row: HistoryItem) {
        val initial = Calendar.getInstance().apply { timeInMillis = row.at }
        if (initial.get(Calendar.YEAR) !in 1..9999) initial.timeInMillis = System.currentTimeMillis()
        DatePickerDialog(ctx, { _, year, month, date ->
            val chosen = initial.clone() as Calendar
            chosen.set(Calendar.YEAR,year); chosen.set(Calendar.MONTH,month); chosen.set(Calendar.DAY_OF_MONTH,date)
            TimePickerDialog(ctx, { _, hour, minute ->
                chosen.set(Calendar.HOUR_OF_DAY,hour); chosen.set(Calendar.MINUTE,minute)
                chosen.set(Calendar.SECOND,0); chosen.set(Calendar.MILLISECOND,0)
                actions.launch {
                    pending = row.id
                    try { Front.amendTime(chorus,row.id,chosen.timeInMillis,frontId=front.takeIf { configured }); error=null }
                    catch (e: Exception) { error=e.message ?: "Could not save the time. Try again." }
                    finally { pending=null }
                }
            },initial.get(Calendar.HOUR_OF_DAY),initial.get(Calendar.MINUTE),android.text.format.DateFormat.is24HourFormat(ctx)).show()
        },initial.get(Calendar.YEAR),initial.get(Calendar.MONTH),initial.get(Calendar.DAY_OF_MONTH)).show()
    }
    val day = DateFormat.getDateInstance(DateFormat.MEDIUM)
    val time = DateFormat.getTimeInstance(DateFormat.SHORT)
    LazyColumn(Modifier.padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        item { Spacer(Modifier.height(8.dp)) }
        if (configured) item {
            Column(Modifier.fillMaxWidth()) {
                Box {
                    TextButton(onClick={menu=true}) {
                        Text("Front: ${definitions?.get(front)?.name ?: "Body"}",maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                        definitions?.values?.sortedBy { it.id }?.forEach { definition ->
                            DropdownMenuItem(text={Text(definition.name + if (definition.deleted) " (deleted)" else "")},
                                onClick={selected=definition.id;menu=false;error=null})
                        }
                    }
                }
                Row {
                    TextButton(onClick={savedView=false},modifier=Modifier.weight(1f).semantics { this.selected = !savedView }) {
                        Text("Front history",color=if (!savedView) p.accent else p.ink2)
                    }
                    TextButton(onClick={savedView=true},modifier=Modifier.weight(1f).semantics { this.selected = savedView }) {
                        Text("Saved entries",color=if (savedView) p.accent else p.ink2)
                    }
                }
                Text(if (savedView) "Edit this front’s saved records here. Past edits keep following active."
                    else "Includes inherited changes. Open Saved entries to edit this front’s records.",
                    color=p.ink2,fontSize=13.sp,modifier=Modifier.padding(bottom=12.dp))
            }
        }
        error?.let { message -> item { Text(message,color=p.danger) } }
        if (readError != null) item {
            Text("Could not load saved history.",color=p.danger)
            TextButton(onClick={retry++}) { Text("Try again") }
        }
        if (loading) item { Text("Loading saved history…",color=p.ink2,modifier=Modifier.padding(vertical=24.dp)) }
        if (!loading && readError==null && rows.isEmpty()) item {
            Text(if (configured && savedView) "No saved entries for this front." else "No changes recorded yet.",
                color=p.ink3,modifier=Modifier.padding(vertical=24.dp))
        }
        var lastDay = ""
        rows.take(shown).forEach { row ->
            val date = day.format(Date(row.at))
            if (date != lastDay) {
                lastDay=date
                item(key="day:$date:${row.id}") { Text(date,color=p.ink2,fontSize=12.sp,
                    fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=10.dp)) }
            }
            item(key=row.id) {
                val lead = (row.entries.firstOrNull { it.isPrimary } ?: row.entries.firstOrNull())
                    ?.let { model.subject(it.subjectType,it.subjectId) }
                Column(Modifier.fillMaxWidth().background(p.surface,RoundedCornerShape(14.dp)).padding(12.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Avatar(lead?.glyph ?: "·",lead?.color ?: "#A09184",32.dp,avatarBlob=lead?.avatarBlob)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(describe(row),color=if (row.retracted) p.ink3 else p.ink,fontWeight=FontWeight.Medium,
                                textDecoration=if (row.retracted) TextDecoration.LineThrough else null)
                            row.note?.let { Text(it,color=p.ink2,fontSize=12.sp) }
                            Text(time.format(Date(row.at)) + if (row.retracted) " · undone" else "",color=p.ink3,fontSize=12.sp)
                        }
                    }
                    if (row.editable) Row(Modifier.align(Alignment.End)) {
                        if (!row.retracted) TextButton(onClick={editTime(row)},enabled=pending!=row.id) { Text("Edit time") }
                        TextButton(enabled=pending!=row.id,onClick={actions.launch {
                            pending=row.id
                            try { Front.retract(chorus,row.id,redo=row.retracted,frontId=front.takeIf { configured });error=null }
                            catch (e: Exception) { error=e.message ?: "Could not save the change. Try again." }
                            finally { pending=null }
                        }}) { Text(if (row.retracted) "Redo" else "Undo") }
                    } else if (row.state=="overridden") Text("Own override",color=p.ink2,fontSize=12.sp,modifier=Modifier.padding(top=6.dp))
                }
            }
        }
        if (rows.size>shown) item { TextButton(onClick={shown+=200},modifier=Modifier.fillMaxWidth()) { Text("Show older entries") } }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
