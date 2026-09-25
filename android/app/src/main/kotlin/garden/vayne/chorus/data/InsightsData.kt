package garden.vayne.chorus.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import org.json.JSONArray
import org.json.JSONObject
import uniffi.chorus_ffi.frontDaily

data class FrontDay(val day: String, val memberId: String, val seconds: Long, val primarySeconds: Long)
data class FrontPair(val first: String, val second: String, val seconds: Long)
data class SwitchDay(val day: String, val count: Int)
data class InsightsSnapshot(val daily: List<FrontDay>, val weekly: List<FrontDay>,
    val pairs: List<FrontPair>, val switches: List<SwitchDay>, val from: Long, val now: Long)

/** Read-only charts over the own-account projection. Core does the timezone-aware day split. */
object InsightsData {
    fun snapshot(model: Model, days: Int, now: Long = System.currentTimeMillis(),
        core: (String, Long, String) -> String = ::frontDaily): InsightsSnapshot {
        require(days in 1..366)
        val zone = runCatching { ZoneId.of(model.systemZone ?: ZoneId.systemDefault().id) }
            .getOrElse { ZoneId.systemDefault() }
        val from = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(days.toLong() - 1)
            .atStartOfDay(zone).toInstant().toEpochMilli()
        val candidates = model.frontIntervals.filter { (it.endAt ?: now) > from && it.startAt < now }
        val intervals = JSONArray().apply { for (row in candidates) put(JSONObject()
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
            if (row.optString("subject_type") != "member" || row.optString("level") != "front") null
            else FrontDay(row.getString("day"), row.getString("subject_id"), row.getLong("seconds"),
                row.getLong("as_primary_seconds"))
        }
        val weekly = daily.groupBy { day ->
            val week = LocalDate.parse(day.day).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            week.toString() to day.memberId
        }.map { (key, group) -> FrontDay(key.first, key.second, group.sumOf { it.seconds },
            group.sumOf { it.primarySeconds }) }.sortedWith(compareBy<FrontDay> { it.day }.thenBy { it.memberId })
        val active = candidates.filter { it.subjectType == "member" && it.level == "front" }
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
        val switches = model.switches.asSequence().filter { !it.retracted && it.occurredAt in from..now }
            .groupingBy { Instant.ofEpochMilli(it.occurredAt).atZone(zone).toLocalDate().toString() }
            .eachCount().map { (day, count) -> SwitchDay(day, count) }.sortedBy { it.day }
        return InsightsSnapshot(daily, weekly, pairs, switches, from, now)
    }
}
