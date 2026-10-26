package garden.vayne.chorus.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.NamedHistory
import garden.vayne.chorus.data.InsightsData
import garden.vayne.chorus.data.InsightsSnapshot
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.designsystem.LocalChorusPalette
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class InsightTable(val title: String, val filename: String, val headers: List<String>,
    val rows: List<List<String>>) {
    fun csv(): String = (listOf(headers) + rows).joinToString("\r\n", postfix = "\r\n") { row ->
        row.joinToString(",") { value -> "\"${value.replace("\"", "\"\"")}\"" }
    }
}
private data class InsightBar(val label: String, val value: String, val parts: List<Pair<String, Long>>) {
    val total: Long get() = parts.sumOf { it.second }
}
private data class InsightChart(val table: InsightTable, val bars: List<InsightBar>)

/** Offline, account-local chart data. No server request or new statistics copy. */
@Composable
internal fun InsightsScreen(chorus: Chorus, model: Model, onClose: () -> Unit) {
    val p = LocalChorusPalette.current
    val ctx = LocalContext.current
    val actions = rememberCoroutineScope()
    var days by rememberSaveable { mutableStateOf(7) }
    var selectedFront by rememberSaveable { mutableStateOf("") }
    var layerId by rememberSaveable { mutableStateOf("front") }
    var own by rememberSaveable { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val revision by chorus.frontHistoryRevision.collectAsState()
    val definitions = model.namedFronts?.fronts
    val configured = definitions != null
    val front = selectedFront.takeIf { definitions?.containsKey(it) == true } ?: chorus.device?.accountId.orEmpty()
    val frontName = definitions?.get(front)?.name ?: "Body"
    val layerName = model.namedFronts?.layers?.find { it.id==layerId }?.name ?: layerId
    val loadRequest = remember(revision,front,configured,retry) { Any() }
    // A new scope starts empty in the same composition. produceState keys alone retain the
    // previous value until their coroutine starts, briefly mislabelling old charts/exports.
    val loaded by key(loadRequest) { produceState<Result<NamedHistory?>?>(null) {
        value = if (configured) runCatching { chorus.frontHistory(front) } else Result.success(null)
    }
    }
    val history = loaded?.getOrNull()?.takeIf { it.frontId==front }
    val intervals = history?.let { if (own) it.ownIntervals else it.intervals }.orEmpty()
    val layers = (model.namedFronts?.layers.orEmpty().map { it.id } + intervals.map { it.presence.layerId } + layerId).toSet()
        .sortedWith(compareBy<String> { id -> model.namedFronts?.layers?.find { it.id==id }?.level ?: Int.MAX_VALUE }.thenBy { it })
        .map { id -> val definition=model.namedFronts?.layers?.find { it.id==id }
            id to ((definition?.name ?: id) + if (definition?.deleted==true) " (deleted)" else "") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var table by remember { mutableStateOf<InsightTable?>(null) }
    var csvPending by remember { mutableStateOf("") }
    var exportError by remember { mutableStateOf<String?>(null) }
    val saveCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) actions.launch {
            try {
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri)?.use { it.write(csvPending.toByteArray(Charsets.UTF_8)) }
                        ?: error("Could not open the chosen file.")
                }
                exportError = null
            } catch (e: Exception) { exportError = e.message ?: "Could not save CSV." }
        }
    }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = System.currentTimeMillis() } }
    val calculationRequest = remember(history,layerId,own,days,now,model.systemZone,
        if (configured) null else model.frontIntervals,if (configured) null else model.switches) { Any() }
    val calculation by key(calculationRequest) { produceState<Result<InsightsSnapshot>?>(null) {
        if (!configured || history != null) value = withContext(Dispatchers.Default) {
            runCatching { InsightsData.snapshot(model,days,now,history=history,layerId=layerId,own=own) }
        }
    }
    }
    BackHandler(table != null) { table = null }
    if (table != null) {
        val selected = table ?: return
        LazyColumn(Modifier.fillMaxSize().background(p.bg).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                TextButton(onClick = { table = null }) { Text("‹ Insights") }
                Text(selected.title, color = p.ink, fontWeight = FontWeight.SemiBold)
                Text(selected.headers.joinToString(" · "), color = p.ink2)
            }
            items(selected.rows) { row ->
                Text(row.joinToString(" · "), color = p.ink,
                    modifier = Modifier.fillMaxWidth().background(p.surface).padding(8.dp))
            }
        }
        return
    }
    val data = calculation?.getOrNull()
    val names = remember(model.members) { model.members.associate { it.id to it.shownName } }
    val colors = remember(model.members) { model.members.associate { member ->
        member.id to runCatching { Color(android.graphics.Color.parseColor(member.color)) }.getOrDefault(Color(0xFFA09184))
    } }
    val chartRequest = remember(data,names,days,model.systemZone,configured,front,layerId,own) { Any() }
    val charts by key(chartRequest) { produceState<List<InsightChart>>(emptyList()) {
        if (data != null) value = withContext(Dispatchers.Default) {
            val legacy = charts(data,names,days,model.systemZone)
            if (!configured) legacy else legacy.mapIndexed { index, chart ->
                val title=listOf("Time by day","Time by week","Time together","Changes by day")[index]
                val scopeHeaders=listOf("front_id","history") + if (index<3) listOf("layer_id") else emptyList()
                val scope=listOf(front,if (own) "own" else "effective") + if (index<3) listOf(layerId) else emptyList()
                val headers=chart.table.headers.map { if (index==3 && it=="switches") "changes" else it }
                chart.copy(table=chart.table.copy(title=title,headers=scopeHeaders+headers,
                    rows=chart.table.rows.map { row -> scope + row.mapIndexed { i, cell ->
                        if (headers[i]=="hours") cell.removeSuffix(" h") else cell
                    } }))
            }
        }
    }
    }
    LazyColumn(Modifier.fillMaxSize().background(p.bg).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Insights", color = p.ink, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 12.dp))
                TextButton(onClick = onClose) { Text("Close") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick={days=7},modifier=Modifier.semantics { selected=days==7 }) { Text("7 days",color=if (days==7) p.accent else p.ink2) }
                TextButton(onClick={days=28},modifier=Modifier.semantics { selected=days==28 }) { Text("28 days",color=if (days==28) p.accent else p.ink2) }
            }
            if (configured) {
                InsightChoice("Front",front,definitions?.values.orEmpty().sortedBy { it.id }
                    .map { it.id to (it.name + if (it.deleted) " (deleted)" else "") }) { selectedFront=it }
                InsightChoice("Time layer",layerId,layers) { layerId=it }
                InsightChoice("History",if (own) "own" else "effective",listOf("effective" to "Front history","own" to "Saved entries")) { own=it=="own" }
                Text("$frontName · $layerName time. Change counts cover all layers of this front. Day and week bars show member-hours; simultaneous members add together.",color=p.ink2)
            } else Text("Front history on this device. Simultaneous fronts add together.", color = p.ink2)
            if (exportError != null) Text(exportError.orEmpty(), color = p.danger)
        }
        if (loaded?.isFailure==true) item {
            Text("Could not load saved history.",color=p.danger)
            TextButton(onClick={retry++}) { Text("Try again") }
        } else if (calculation?.isFailure == true) item {
            Text("Could not calculate insights: ${calculation?.exceptionOrNull()?.message.orEmpty()}", color = p.danger)
        } else if (data == null) item { Text("Preparing insights…", color = p.ink2) }
        for (chart in charts) item(key = chart.table.title) {
            Column(Modifier.fillMaxWidth().background(p.surface).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(chart.table.title, color = p.ink, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { table = chart.table }) { Text("View data") }
                    TextButton(onClick = {
                        csvPending = chart.table.csv()
                        saveCsv.launch(chart.table.filename)
                    }) { Text("Export CSV") }
                }
                if (chart.bars.isEmpty()) Text("No data in this range.", color = p.ink2)
                val maximum = chart.bars.maxOfOrNull { it.total }?.coerceAtLeast(1) ?: 1
                for (bar in chart.bars) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(bar.label, color = p.ink2, modifier = Modifier.weight(1f), maxLines = 1)
                        Box(Modifier.weight(2f).height(14.dp).background(p.surface2)) {
                            if (bar.total > 0) Row(Modifier.fillMaxWidth(bar.total.toFloat() / maximum)
                                .fillMaxHeight()) {
                                for ((memberId, seconds) in bar.parts.filter { it.second > 0 })
                                    Box(Modifier.weight(seconds.toFloat()).fillMaxHeight()
                                        .background(colors[memberId] ?: p.accent))
                            }
                        }
                        Text(bar.value, color = p.ink2, modifier = Modifier.weight(1f), maxLines = 1)
                    }
                }
            }
        }
        item { Text("", modifier = Modifier.padding(bottom = 16.dp)) }
    }
}

@Composable
private fun InsightChoice(label: String, value: String, options: List<Pair<String,String>>, onValue: (String) -> Unit) {
    val p=LocalChorusPalette.current
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        TextButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth()) {
            Text("$label: ${options.find { it.first==value }?.second ?: value}",color=p.ink,maxLines=2)
        }
        DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
            options.forEach { (id,name) -> DropdownMenuItem(text={Text(name)},modifier=Modifier.semantics { selected=id==value },
                onClick={onValue(id);expanded=false}) }
        }
    }
}

private fun charts(data: InsightsSnapshot, names: Map<String, String>, days: Int, systemZone: String?): List<InsightChart> {
    fun name(id: String) = names[id] ?: "Someone"
    fun hours(seconds: Long) = "%.1f h".format(java.util.Locale.ROOT, seconds / 3600.0)
    val zone = runCatching { ZoneId.of(systemZone ?: ZoneId.systemDefault().id) }.getOrElse { ZoneId.systemDefault() }
    val first = Instant.ofEpochMilli(data.from).atZone(zone).toLocalDate()
    fun rowsFor(front: List<garden.vayne.chorus.data.FrontDay>, title: String, filename: String,
        fillDays: Boolean = false): InsightChart {
        val grouped = front.groupBy { it.day }.toSortedMap()
        val labels = if (fillDays) (0 until days).map { first.plusDays(it.toLong()).toString() } else grouped.keys.toList()
        return InsightChart(InsightTable(title, filename, listOf("day", "member_id", "member", "seconds", "hours"),
            front.map { listOf(it.day, it.memberId, name(it.memberId), it.seconds.toString(), hours(it.seconds)) }),
            labels.map { day -> val rows = grouped[day].orEmpty(); InsightBar(day, hours(rows.sumOf { it.seconds }),
                rows.sortedByDescending { it.seconds }.map { it.memberId to it.seconds }) })
    }
    val frontDay = rowsFor(data.daily, "Front time by day", "front-by-day.csv", fillDays = true)
    val frontWeek = rowsFor(data.weekly, "Front time by week", "front-by-week.csv")
    val pairs = InsightChart(InsightTable("Co-front pairs", "cofront-pairs.csv",
        listOf("member_a_id", "member_a", "member_b_id", "member_b", "seconds", "hours"),
        data.pairs.map { listOf(it.first, name(it.first), it.second, name(it.second), it.seconds.toString(), hours(it.seconds)) }),
        data.pairs.map { InsightBar("${name(it.first)} & ${name(it.second)}", hours(it.seconds), listOf("" to it.seconds)) })
    val counts = data.switches.associate { it.day to it.count }
    val switchDays = (0 until days).map { i ->
        val day = first.plusDays(i.toLong()).toString()
        day to (counts[day] ?: 0)
    }
    val switches = InsightChart(InsightTable("Switches per day", "switches-per-day.csv",
        listOf("day", "switches"), switchDays.map { listOf(it.first, it.second.toString()) }),
        switchDays.map { InsightBar(it.first, it.second.toString(), listOf("" to it.second.toLong())) })
    return listOf(frontDay, frontWeek, pairs, switches)
}
