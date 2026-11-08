package garden.vayne.chorus.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import org.json.JSONArray
import org.json.JSONObject
import uniffi.chorus_ffi.frontDaily
import uniffi.chorus_ffi.namedFrontDaily

data class FrontDay(val day: String, val memberId: String, val seconds: Long, val primarySeconds: Long)
data class FrontPair(val first: String, val second: String, val seconds: Long)
data class SwitchDay(val day: String, val count: Int)
/** One member's longest unbroken stretch in the range (F9.1 "Longest front"). */
data class FrontStretch(val memberId: String, val startAt: Long, val seconds: Long)
data class InsightsSnapshot(val daily: List<FrontDay>, val weekly: List<FrontDay>,
    val pairs: List<FrontPair>, val switches: List<SwitchDay>, val from: Long, val now: Long,
    /** Changes in the same span just before [from] ("3 more than last week"). */
    val previousSwitches: Int = 0,
    /** Seconds when two or more members were here at once, counted once however many. */
    val together: Long = 0,
    /** Local hour (0-23) with the most changes, if any. */
    val busiestHour: Int? = null,
    val longest: FrontStretch? = null)

/** A column of the F9.1 chart: its label and each member's seconds, in [InsightsData.columns]'s member order. */
data class InsightColumn(val label: String, val parts: List<Pair<String, Long>>) {
    val total: Long get() = parts.sumOf { it.second }
}

/** Week, Month, Year (F9.1): how many days each looks back over, and what a chart column holds. */
enum class InsightRange(val label: String, val days: Int, val noun: String) {
    Week("Week", 7, "week"), Month("Month", 30, "month"), Year("Year", 365, "year"),
}

/** Read-only charts over the own-account projection. Core does the timezone-aware day split. */
object InsightsData {
    fun snapshot(model: Model, days: Int, now: Long = System.currentTimeMillis(),
        history: NamedHistory? = null, layerId: String = "front", own: Boolean = false,
        core: (String, Long, String) -> String = if (history == null) ::frontDaily else ::namedFrontDaily): InsightsSnapshot {
        require(days in 1..366)
        val zone = runCatching { ZoneId.of(model.systemZone ?: ZoneId.systemDefault().id) }
            .getOrElse { ZoneId.systemDefault() }
        val from = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(days.toLong() - 1)
            .atStartOfDay(zone).toInstant().toEpochMilli()
        val named = history?.let { h -> (if (own) h.ownIntervals else h.intervals)
            .filter { it.frontId == h.frontId && (it.endAt ?: now) > from && it.startAt < now } }
        val candidates = named?.map { i -> FrontInterval(i.id, i.presence.subjectType, i.presence.subjectId,
            i.presence.layerId, i.presence.isPrimary, i.startAt, i.endAt) }
            ?: model.frontIntervals.filter { (it.endAt ?: now) > from && it.startAt < now }
        val intervals = if (named != null) JSONArray().apply { for (row in named) put(row.toJson()
            .put("start_at", maxOf(row.startAt, from)).put("end_at", minOf(row.endAt ?: now, now))) }
            else JSONArray().apply { for (row in candidates) put(JSONObject()
            .put("id", row.id).put("subject_type", row.subjectType).put("subject_id", row.subjectId)
            .put("level", row.level).put("is_primary", row.isPrimary)
            .put("start_at", maxOf(row.startAt, from)).put("end_at", minOf(row.endAt ?: now, now))) }
        val offsets = JSONArray()
        val rules = zone.rules
        val start = from - 3_600_000L
        offsets.put(JSONArray().put(start).put(rules.getOffset(Instant.ofEpochMilli(start)).totalSeconds / 60))
        var transition = rules.nextTransition(Instant.ofEpochMilli(start))
        while (transition != null && transition.instant.toEpochMilli() <= now) {
            offsets.put(JSONArray().put(transition.instant.toEpochMilli()).put(transition.offsetAfter.totalSeconds / 60))
            transition = rules.nextTransition(transition.instant)
        }
        val rows = JSONArray(core(intervals.toString(), now, offsets.toString()))
        val daily = (0 until rows.length()).mapNotNull { i ->
            val row = rows.getJSONObject(i)
            val layer = row.optString(if (named == null) "level" else "layer_id")
            if (row.optString("subject_type") != "member" || layer != layerId) null
            else FrontDay(row.getString("day"), row.getString("subject_id"), row.getLong("seconds"),
                row.getLong("as_primary_seconds"))
        }
        val weekly = daily.groupBy { day ->
            val week = LocalDate.parse(day.day).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            week.toString() to day.memberId
        }.map { (key, group) -> FrontDay(key.first, key.second, group.sumOf { it.seconds },
            group.sumOf { it.primarySeconds }) }.sortedWith(compareBy<FrontDay> { it.day }.thenBy { it.memberId })
        val active = candidates.filter { it.subjectType == "member" && it.level == layerId }
            .sortedBy { it.startAt }
        val pairTotals = HashMap<Pair<String, String>, Long>()
        for (i in active.indices) for (j in i + 1 until active.size) {
            val a = active[i]; val b = active[j]
            if (b.startAt >= (a.endAt ?: now)) break
            if (a.subjectId == b.subjectId) continue
            val overlap = minOf(a.endAt ?: now, b.endAt ?: now, now) - maxOf(a.startAt, b.startAt, from)
            if (overlap <= 0) continue
            val pair = if (a.subjectId < b.subjectId) a.subjectId to b.subjectId else b.subjectId to a.subjectId
            pairTotals[pair] = (pairTotals[pair] ?: 0) + overlap
        }
        val pairs = pairTotals.map { (pair, ms) -> FrontPair(pair.first, pair.second, ms / 1000) }
            .sortedWith(compareByDescending<FrontPair> { it.seconds }.thenBy { it.first }.thenBy { it.second })
        val changes = if (history == null) model.switches.filter { !it.retracted }.map { it.occurredAt }
            else if (own) history.saved.filter { !it.retracted }.map { it.occurredAt }
            else history.changes.map { it.occurredAt }
        val switches = changes.asSequence().filter { it in from..now }
            .groupingBy { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toString() }
            .eachCount().map { (day, count) -> SwitchDay(day, count) }.sortedBy { it.day }
        val previousFrom = Instant.ofEpochMilli(from).atZone(zone).toLocalDate().minusDays(days.toLong())
            .atStartOfDay(zone).toInstant().toEpochMilli()
        val previous = changes.count { it in previousFrom until from }
        val busiest = changes.filter { it in from..now }.groupingBy { Instant.ofEpochMilli(it).atZone(zone).hour }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key }).firstOrNull()?.key
        // each member's time as unbroken stretches, so a switch that keeps someone doesn't split them
        val stretches = active.groupBy { it.subjectId }.mapValues { (_, rows) ->
            val out = ArrayList<LongArray>()
            for (row in rows.sortedBy { it.startAt }) {
                val a = maxOf(row.startAt, from); val b = minOf(row.endAt ?: now, now)
                if (b <= a) continue
                val last = out.lastOrNull()
                if (last != null && a <= last[1]) last[1] = maxOf(last[1], b) else out += longArrayOf(a, b)
            }
            out
        }
        val longest = stretches.flatMap { (member, spans) -> spans.map { FrontStretch(member, it[0], (it[1] - it[0]) / 1000) } }
            .sortedWith(compareByDescending<FrontStretch> { it.seconds }.thenBy { it.startAt }.thenBy { it.memberId }).firstOrNull()
        val edges = stretches.values.flatten().flatMap { listOf(it[0] to 1, it[1] to -1) }
            .sortedWith(compareBy<Pair<Long, Int>> { it.first }.thenBy { it.second })
        var here = 0; var last = from; var together = 0L
        for ((at, step) in edges) {
            if (here >= 2) together += at - last
            here += step; last = at
        }
        return InsightsSnapshot(daily, weekly, pairs, switches, from, now, previous, together / 1000, busiest, longest)
    }

    /**
     * The chart's columns: a week by day, a month by week (from Monday), a year by month. Members
     * stack largest overall first (the bottom of each column), as in the legend.
     */
    fun columns(data: InsightsSnapshot, range: InsightRange, zone: ZoneId): List<InsightColumn> {
        val first = Instant.ofEpochMilli(data.from).atZone(zone).toLocalDate()
        val last = Instant.ofEpochMilli(data.now).atZone(zone).toLocalDate()
        val order = memberOrder(data)
        fun key(day: LocalDate): LocalDate = when (range) {
            InsightRange.Week -> day
            InsightRange.Month -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            InsightRange.Year -> day.withDayOfMonth(1)
        }
        val keys = generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.map(::key).distinct().toList()
        val sums = data.daily.groupBy { key(LocalDate.parse(it.day)) }
            .mapValues { (_, rows) -> rows.groupBy { it.memberId }.mapValues { (_, r) -> r.sumOf { it.seconds } } }
        return keys.map { k ->
            val label = when (range) {
                InsightRange.Week -> k.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.getDefault())
                InsightRange.Month -> "${k.dayOfMonth} " + k.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
                InsightRange.Year -> k.month.getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.getDefault())
            }
            val bucket = sums[k].orEmpty()
            InsightColumn(label, order.mapNotNull { m -> bucket[m]?.takeIf { it > 0 }?.let { m to it } })
        }
    }

    /** Members by their time in the range, most first. */
    fun memberOrder(data: InsightsSnapshot): List<String> = data.daily.groupBy { it.memberId }
        .mapValues { (_, rows) -> rows.sumOf { it.seconds } }.entries
        .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key }).map { it.key }

    /** "24" with "3 more than last week"; the member with most time and their share of the span. */
    fun switchDelta(data: InsightsSnapshot, range: InsightRange): String {
        val now = data.switches.sumOf { it.count }
        val d = now - data.previousSwitches
        return when {
            d > 0 -> "$d more than last ${range.noun}"
            d < 0 -> "${-d} fewer than last ${range.noun}"
            else -> "Same as last ${range.noun}"
        }
    }

    fun share(data: InsightsSnapshot, memberId: String): Int {
        val span = (data.now - data.from) / 1000
        if (span <= 0) return 0
        val seconds = data.daily.filter { it.memberId == memberId }.sumOf { it.seconds }
        return ((seconds * 100 + span / 2) / span).toInt().coerceIn(0, 100)
    }

    /** "11 h", "6 h 20 m", "40 m". */
    fun duration(seconds: Long, short: Boolean = false): String {
        val h = seconds / 3600; val m = (seconds % 3600) / 60
        return when {
            h == 0L -> "$m m"
            short || m == 0L -> "$h h"
            else -> "$h h $m m"
        }
    }
}
