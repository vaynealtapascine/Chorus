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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
internal fun InsightsScreen(model: Model, onClose: () -> Unit) {
    val p = LocalChorusPalette.current
    val ctx = LocalContext.current
    val actions = rememberCoroutineScope()
    var days by rememberSaveable { mutableStateOf(7) }
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
    val calculation by produceState<Result<InsightsSnapshot>?>(null, model, days, now) {
        value = null
        value = withContext(Dispatchers.Default) { runCatching { InsightsData.snapshot(model, days, now) } }
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
    val charts by produceState<List<InsightChart>>(emptyList(), data, names, days, model.systemZone) {
        value = emptyList()
        if (data != null) value = withContext(Dispatchers.Default) { charts(data, names, days, model.systemZone) }
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
                TextButton(onClick = { days = 7 }) { Text(if (days == 7) "✓ 7 days" else "7 days") }
                TextButton(onClick = { days = 28 }) { Text(if (days == 28) "✓ 28 days" else "28 days") }
            }
            Text("Front history on this device. Simultaneous fronts add together.", color = p.ink2)
            if (exportError != null) Text(exportError.orEmpty(), color = p.danger)
        }
        if (calculation?.isFailure == true) item {
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
