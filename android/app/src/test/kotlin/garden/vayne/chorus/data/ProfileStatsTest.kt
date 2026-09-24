package garden.vayne.chorus.data

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test
import org.json.JSONObject

class ProfileStatsTest {
    @Test fun frontHoursUseLocalCalendarWindowsAcrossDst() {
        val zone = ZoneId.of("America/New_York")
        fun at(day: Int, hour: Int, minute: Int) = ZonedDateTime.of(2026, 3, day, hour, minute, 0, 0, zone)
            .toInstant().toEpochMilli()
        val start = at(2, 23, 30)
        val end = at(3, 0, 30)
        val dstStart = at(8, 1, 30)
        val dstEnd = at(8, 3, 30) // spring forward: one elapsed hour
        val model = Model(emptyList(), emptyList(), emptyMap(), emptyList(), null, emptyList(),
            frontSpans = listOf(FrontSpan("kai", start, end), FrontSpan("kai", dstStart, dstEnd)),
            systemZone = "America/New_York", messageCounts = mapOf("kai" to 7))
        val stats = ProfileMetrics.compute(model, "kai", at(9, 12, 0))
        assertEquals(1.5, stats.weekHours, 0.001)
        assertEquals(2.0, stats.monthHours, 0.001)
        assertEquals(dstEnd, stats.lastFrontAt)
        assertEquals(7, stats.messages)
    }

    @Test fun localProjectionSuppliesFrontSpansAndAllVisibleMessageCounts() {
        val p = JSONObject("""{"rows":{"system":{"acct":{"exists":true,"fields":{"timezone":"Asia/Manila"}}},"space":{"s":{"exists":true,"fields":{"kind":"internal","name":"Internal"}}},"channel":{"c":{"exists":true,"fields":{"space_id":"s","name":"general"}}},"message":{"m1":{"exists":true,"fields":{"channel_id":"c","authors":["kai"],"text":"one","occurred_at":1}},"m2":{"exists":true,"fields":{"channel_id":"c","authors":["kai"],"text":"two","occurred_at":2}}}},"fronts":{"acct":{"intervals":[{"subject_type":"member","subject_id":"kai","level":"front","start_at":10,"end_at":20}],"switches":[],"current":[]}}}""")
        val model = Model.parse(p, "acct")
        assertEquals("Asia/Manila", model.systemZone)
        assertEquals(listOf(FrontSpan("kai", 10, 20)), model.frontSpans)
        assertEquals(2, model.messageCounts["kai"])
    }
}
