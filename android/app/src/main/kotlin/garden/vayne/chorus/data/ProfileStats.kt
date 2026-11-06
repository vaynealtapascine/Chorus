package garden.vayne.chorus.data

import java.time.Instant
import java.time.ZoneId

data class ProfileStats(val weekHours: Double, val monthHours: Double, val lastFrontAt: Long?, val messages: Int)

/** Calendar-day windows, with DST handled by the configured system zone. */
object ProfileMetrics {
    /** Time in the innermost layer (`front`, "Fronting"/"Steering") from Body's named-front history
     * (D-080): an account with named fronts has no legacy intervals, so its front time read 0 h. */
    fun spans(history: NamedHistory): List<FrontSpan> = history.intervals
        .filter { it.presence.subjectType == "member" && it.presence.layerId == "front" }
        .map { FrontSpan(it.presence.subjectId, it.startAt, it.endAt) }

    fun compute(model: Model, memberId: String, now: Long = System.currentTimeMillis(),
        spans: List<FrontSpan> = model.frontSpans): ProfileStats {
        val zone = runCatching { ZoneId.of(model.systemZone ?: "") }.getOrElse { ZoneId.systemDefault() }
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val weekStart = today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
        val monthStart = today.minusDays(27).atStartOfDay(zone).toInstant().toEpochMilli()
        var weekMs = 0L
        var monthMs = 0L
        var last: Long? = null
        for (span in spans) {
            if (span.memberId != memberId) continue
            val end = (span.endAt ?: now).coerceAtMost(now)
            if (end <= span.startAt) continue
            weekMs += (end - maxOf(span.startAt, weekStart)).coerceAtLeast(0)
            monthMs += (end - maxOf(span.startAt, monthStart)).coerceAtLeast(0)
            last = maxOf(last ?: Long.MIN_VALUE, end)
        }
        return ProfileStats(weekMs / 3_600_000.0, monthMs / 3_600_000.0, last,
            model.messageCounts[memberId] ?: 0)
    }
}
