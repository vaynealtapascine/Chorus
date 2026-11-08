package garden.vayne.chorus.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.FrontDay
import garden.vayne.chorus.data.InsightColumn
import garden.vayne.chorus.data.InsightRange
import garden.vayne.chorus.data.InsightsData
import garden.vayne.chorus.data.InsightsSnapshot
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.NamedHistory
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
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

/**
 * F9.1 Fronting insights, offline over this account's own history: Week / Month / Year, three stat
 * tiles, front time as stacked columns, View data + Download CSV, and Patterns. Which front, layer
 * and history it reads (configured accounts) and the other exports sit in the top bar's menu.
 */
@Composable
internal fun InsightsScreen(chorus: Chorus, model: Model, onClose: () -> Unit) {
    val p = LocalChorusPalette.current
    val ctx = LocalContext.current
    val actions = rememberCoroutineScope()
    var range by rememberSaveable { mutableStateOf(InsightRange.Week) }
    var selectedFront by rememberSaveable { mutableStateOf("") }
    var layerId by rememberSaveable { mutableStateOf("front") }
    var own by rememberSaveable { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf<String?>(null) }
    val revision by chorus.frontHistoryRevision.collectAsState()
    val definitions = model.namedFronts?.fronts
    val configured = definitions != null
    val front = selectedFront.takeIf { definitions?.containsKey(it) == true } ?: chorus.device?.accountId.orEmpty()
    val loadRequest = remember(revision, front, configured, retry) { Any() }
    // A new scope starts empty in the same composition. produceState keys alone retain the
    // previous value until their coroutine starts, briefly mislabelling old charts/exports.
    val loaded by key(loadRequest) { produceState<Result<NamedHistory?>?>(null) {
        value = if (configured) runCatching { chorus.frontHistory(front) } else Result.success(null)
    } }
    val history = loaded?.getOrNull()?.takeIf { it.frontId == front }
    val intervals = history?.let { if (own) it.ownIntervals else it.intervals }.orEmpty()
    val layers = (model.namedFronts?.layers.orEmpty().map { it.id } + intervals.map { it.presence.layerId } + layerId).toSet()
        .sortedWith(compareBy<String> { id -> model.namedFronts?.layers?.find { it.id == id }?.level ?: Int.MAX_VALUE }.thenBy { it })
        .map { id -> val definition = model.namedFronts?.layers?.find { it.id == id }
            id to ((definition?.name ?: id) + if (definition?.deleted == true) " (deleted)" else "") }
    val fronts = definitions?.values.orEmpty().sortedBy { it.id }.map { it.id to (it.name + if (it.deleted) " (deleted)" else "") }
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
    val calculationRequest = remember(history, layerId, own, range, now, model.systemZone,
        if (configured) null else model.frontIntervals, if (configured) null else model.switches) { Any() }
    val calculation by key(calculationRequest) { produceState<Result<InsightsSnapshot>?>(null) {
        if (!configured || history != null) value = withContext(Dispatchers.Default) {
            runCatching { InsightsData.snapshot(model, range.days, now, history = history, layerId = layerId, own = own) }
        }
    } }
    val data = calculation?.getOrNull()
    val names = remember(model.members) { model.members.associate { it.id to it.shownName } }
    val colors = remember(model.members) { model.members.associate { member ->
        member.id to runCatching { Color(android.graphics.Color.parseColor(member.color)) }.getOrDefault(Color(0xFFA09184))
    } }
    val zone = remember(model.systemZone) {
        runCatching { ZoneId.of(model.systemZone ?: ZoneId.systemDefault().id) }.getOrElse { ZoneId.systemDefault() }
    }
    val tables = remember(data, names, configured, front, layerId, own) { data?.let { tables(it, names, configured, front, layerId, own) } }
    fun download(t: InsightTable) { csvPending = t.csv(); saveCsv.launch(t.filename) }

    BackHandler(table != null) { table = null }
    table?.let { selected -> DataTable(selected) { table = null }; return }

    Column(Modifier.fillMaxSize().background(p.bg)) {
        SubpageBar("Fronting insights", "For your own reflection · only your system sees this", onClose) {
            BarIcon(R.drawable.ic_more_vert, "More", p.ink) { menu = true }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "range") {
                Segmented(InsightRange.entries.map { it.label }, range.ordinal, accent = true) { range = InsightRange.entries[it] }
            }
            exportError?.let { item(key = "error") { Text(it, color = p.danger, fontSize = 14.sp) } }
            when {
                loaded?.isFailure == true -> item(key = "failed") {
                    EmptyState(R.drawable.ic_history, "Couldn't load history", action = "Try again") { retry++ }
                }
                calculation?.isFailure == true -> item(key = "failed") {
                    Text(calculation?.exceptionOrNull()?.message ?: "Could not calculate insights.", color = p.danger, fontSize = 14.sp)
                }
                data == null -> item(key = "loading") { Box(Modifier.fillMaxWidth().height(320.dp)) }
                else -> {
                    item(key = "stats") { StatTiles(data, range, names) }
                    item(key = "chart") { FrontChart(data, range, zone, names, colors) }
                    item(key = "buttons") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            InsightButton("View data", R.drawable.ic_format_list_ul, Modifier.weight(1f)) { table = tables?.first() }
                            InsightButton("Download CSV", R.drawable.ic_down, Modifier.weight(1f)) { tables?.first()?.let(::download) }
                        }
                    }
                    val patterns = patterns(data, range, zone, names)
                    if (patterns.isNotEmpty()) {
                        item(key = "patterns") { SectionLabel("Patterns") }
                        items(patterns, key = { "pattern:" + it.first }) { (title, line, icon) -> PatternRow(title, line, icon) }
                    }
                    if (data.pairs.isNotEmpty()) item(key = "pairs") { PairsCard(data, names) }
                }
            }
        }
    }

    MenuDrawer(menu, { menu = false }) {
        if (configured) {
            MenuRow("Front", subtitle = fronts.find { it.first == front }?.second ?: front) { menu = false; choosing = "front" }
            MenuRow("Time layer", subtitle = layers.find { it.first == layerId }?.second ?: layerId) { menu = false; choosing = "layer" }
            MenuRow("History", subtitle = if (own) "Saved entries" else "Front history") { menu = false; choosing = "history" }
            MenuDivider()
        }
        tables?.drop(1)?.forEach { t -> MenuRow(t.title + " CSV", R.drawable.ic_down) { menu = false; download(t) } }
    }
    val options = when (choosing) {
        "front" -> fronts
        "layer" -> layers
        "history" -> listOf("effective" to "Front history", "own" to "Saved entries")
        else -> emptyList()
    }
    val current = when (choosing) { "front" -> front; "layer" -> layerId; else -> if (own) "own" else "effective" }
    ChoiceDrawer(choosing != null, when (choosing) { "front" -> "Front"; "layer" -> "Time layer"; else -> "History" }, options,
        options.find { it.first == current }, { it.second }, { picked ->
            when (choosing) { "front" -> selectedFront = picked.first; "layer" -> layerId = picked.first; else -> own = picked.first == "own" }
            choosing = null
        }, { choosing = null })
}

/** Switches · Most time · Together (F9.1). */
@Composable
private fun StatTiles(data: InsightsSnapshot, range: InsightRange, names: Map<String, String>) {
    val top = InsightsData.memberOrder(data).firstOrNull()
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatTile("Switches", data.switches.sumOf { it.count }.toString(), InsightsData.switchDelta(data, range), Modifier.weight(1f))
        StatTile("Most time", top?.let { names[it] ?: "Someone" } ?: "—",
            top?.let { "${InsightsData.share(data, it)}% of the ${range.noun}" }, Modifier.weight(1f))
        StatTile("Together", InsightsData.duration(data.together, short = true), "co-fronting", Modifier.weight(1f))
    }
}

@Composable
private fun StatTile(label: String, value: String, line: String?, modifier: Modifier) {
    val p = LocalChorusPalette.current
    Column(modifier.fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(cardColor()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = p.ink2, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium)
        Text(value, color = p.ink, fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        line?.let { Text(it, color = p.ink3, fontSize = 11.sp, lineHeight = 14.sp) }
    }
}

/** Front time as stacked columns in member colours, labels under them and a legend (F9.1). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FrontChart(data: InsightsSnapshot, range: InsightRange, zone: ZoneId, names: Map<String, String>, colors: Map<String, Color>) {
    val p = LocalChorusPalette.current
    val columns = remember(data, range, zone) { InsightsData.columns(data, range, zone) }
    val order = remember(data) { InsightsData.memberOrder(data) }
    val max = columns.maxOfOrNull { it.total }?.coerceAtLeast(1) ?: 1
    val title = when (range) { InsightRange.Week -> "Front time by day"; InsightRange.Month -> "Front time by week"; InsightRange.Year -> "Front time by month" }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cardColor()).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = p.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("hours", color = p.ink3, fontSize = 11.sp)
        }
        Spacer(Modifier.height(10.dp))
        val gap = if (columns.size > 8) 4.dp else 10.dp
        Row(Modifier.fillMaxWidth().height(140.dp), horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.Bottom) {
            columns.forEach { column -> StackedColumn(column, max, colors, Modifier.weight(1f)) }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            columns.forEach { column ->
                Text(column.label, color = p.ink3, fontSize = if (range == InsightRange.Month) 10.sp else 11.sp, textAlign = TextAlign.Center,
                    maxLines = 1, softWrap = false, overflow = TextOverflow.Clip, modifier = Modifier.weight(1f))
            }
        }
        if (order.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                order.forEach { id ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(Modifier.size(8.dp).background(colors[id] ?: p.accent, CircleShape))
                        Text(names[id] ?: "Someone", color = p.ink2, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun StackedColumn(column: InsightColumn, max: Long, colors: Map<String, Color>, modifier: Modifier) {
    val p = LocalChorusPalette.current
    val hours = "%.1f".format(Locale.ROOT, column.total / 3600.0)
    Column(modifier.fillMaxHeight().semantics { contentDescription = "${column.label}: $hours hours" }
        .padding(horizontal = 3.dp), verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.Bottom)) {
        if (column.total == 0L) Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(p.surface3))
        // largest member at the bottom, as the legend reads
        for ((member, seconds) in column.parts.asReversed()) {
            Box(Modifier.fillMaxWidth().height((140f * seconds / max).coerceAtLeast(2f).dp).clip(RoundedCornerShape(4.dp))
                .background(colors[member] ?: p.accent))
        }
    }
}

@Composable
private fun InsightButton(text: String, icon: Int, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    Row(modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(14.dp)).background(cardColor())
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        Glyph(icon, p.ink, 16.dp)
        Text(text, color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun PatternRow(title: String, line: String, icon: Int) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth().heightIn(min = 62.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Glyph(icon, p.ink2, 22.dp)
        Column(Modifier.weight(1f)) {
            Text(title, color = p.ink, fontSize = 16.sp, lineHeight = 21.sp)
            Text(line, color = p.ink3, fontSize = 13.sp, lineHeight = 17.sp)
        }
    }
}

/** Who fronts together most, as bars (no frame; the card style of the chart above). */
@Composable
private fun PairsCard(data: InsightsSnapshot, names: Map<String, String>) {
    val p = LocalChorusPalette.current
    val top = data.pairs.take(5)
    val max = top.maxOf { it.seconds }.coerceAtLeast(1)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cardColor()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Together most", color = p.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        top.forEach { pair ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${names[pair.first] ?: "Someone"} & ${names[pair.second] ?: "Someone"}", color = p.ink2, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1.4f))
                Box(Modifier.weight(1f).height(8.dp).clip(CircleShape).background(p.surface3)) {
                    Box(Modifier.fillMaxWidth(pair.seconds.toFloat() / max).fillMaxHeight().clip(CircleShape).background(p.accent))
                }
                Text(InsightsData.duration(pair.seconds, short = true), color = p.ink3, fontSize = 12.sp, modifier = Modifier.widthIn(min = 36.dp),
                    textAlign = TextAlign.End)
            }
        }
    }
}

/** View data: the front-time rows grouped by day, newest first. */
@Composable
private fun DataTable(table: InsightTable, onBack: () -> Unit) {
    val p = LocalChorusPalette.current
    val day = table.headers.indexOf("day")
    val member = table.headers.indexOf("member")
    val hours = table.headers.indexOf("hours")
    val byDay = table.rows.groupBy { it[day] }.toSortedMap(reverseOrder())
    Column(Modifier.fillMaxSize().background(p.bg)) {
        SubpageBar(table.title, "${table.rows.size} rows", onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            for ((date, rows) in byDay) {
                item(key = "d:$date") { SectionLabel(date) }
                item(key = "r:$date") {
                    FormCard {
                        rows.forEachIndexed { i, row ->
                            if (i > 0) FormDivider()
                            FormInfoRow(row[member], row[hours] + " h")
                        }
                    }
                }
            }
        }
    }
}

private fun patterns(data: InsightsSnapshot, range: InsightRange, zone: ZoneId, names: Map<String, String>): List<Triple<String, String, Int>> {
    val out = ArrayList<Triple<String, String, Int>>()
    data.busiestHour?.let { h ->
        out += Triple("Busiest hour", "Most switches happen %02d:00–%02d:00".format(h, (h + 1) % 24), R.drawable.ic_clock)
    }
    data.longest?.takeIf { it.seconds >= 60 }?.let { s ->
        val date = Instant.ofEpochMilli(s.startAt).atZone(zone).toLocalDate()
        val on = if (range == InsightRange.Week) date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
            else "${date.dayOfMonth} ${date.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())}"
        out += Triple("Longest front", "${names[s.memberId] ?: "Someone"}, ${InsightsData.duration(s.seconds)} on $on", R.drawable.ic_history)
    }
    return out
}

/** The exports: front time by day first (View data, Download CSV), then co-fronting and switches (the menu). */
private fun tables(data: InsightsSnapshot, names: Map<String, String>, configured: Boolean, front: String, layerId: String,
    own: Boolean): List<InsightTable> {
    fun name(id: String) = names[id] ?: "Someone"
    fun hours(seconds: Long) = "%.1f".format(Locale.ROOT, seconds / 3600.0)
    val scopeHeaders = if (configured) listOf("front_id", "history") else emptyList()
    val scope = if (configured) listOf(front, if (own) "own" else "effective") else emptyList()
    val layerHeader = if (configured) listOf("layer_id") else emptyList()
    val layer = if (configured) listOf(layerId) else emptyList()
    fun frontRows(rows: List<FrontDay>) = rows.sortedWith(compareBy<FrontDay> { it.day }.thenBy { it.memberId })
        .map { scope + layer + listOf(it.day, it.memberId, name(it.memberId), it.seconds.toString(), hours(it.seconds)) }
    val byDay = InsightTable("Front time by day", "front-by-day.csv",
        scopeHeaders + layerHeader + listOf("day", "member_id", "member", "seconds", "hours"), frontRows(data.daily))
    val pairs = InsightTable("Co-fronting", "cofront-pairs.csv",
        scopeHeaders + layerHeader + listOf("member_a_id", "member_a", "member_b_id", "member_b", "seconds", "hours"),
        data.pairs.map { scope + layer + listOf(it.first, name(it.first), it.second, name(it.second), it.seconds.toString(), hours(it.seconds)) })
    val switches = InsightTable("Switches by day", "switches-per-day.csv",
        scopeHeaders + listOf("day", if (configured) "changes" else "switches"), data.switches.map { scope + listOf(it.day, it.count.toString()) })
    return listOf(byDay, pairs, switches)
}
