package garden.vayne.chorus.data

import java.time.Instant
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightsDataTest {
    @Test fun localWindowAndCoreRowsFeedWeeklyPairsAndSwitchCounts() {
        val now = Instant.parse("2026-09-26T12:00:00Z").toEpochMilli()
        val start = Instant.parse("2026-09-25T00:00:00Z").toEpochMilli()
        val model = Model(emptyList(), emptyList(), emptyMap(), emptyList(), null,
            listOf(SwitchRow("sw", "switch", now - 1000, emptyList(), emptyList(), null, false)),
            systemZone = "Asia/Manila", frontIntervals = listOf(
                FrontInterval("a", "member", "kai", "front", true, start, null),
                FrontInterval("b", "member", "rin", "front", false, start + 1000, null)))
        val view = InsightsData.snapshot(model, 7, now) { intervals, end, offsets ->
            assertEquals(now, end)
            assertEquals(2, JSONArray(intervals).length())
            assertEquals(480, JSONArray(offsets).getJSONArray(0).getInt(1))
            """[{"day":"2026-09-25","subject_type":"member","subject_id":"kai","level":"front","seconds":7200,"as_primary_seconds":7200},{"day":"2026-09-25","subject_type":"member","subject_id":"rin","level":"front","seconds":3600,"as_primary_seconds":0}]"""
        }
        assertEquals(2, view.daily.size)
        assertEquals("2026-09-21", view.weekly.single { it.memberId == "kai" }.day)
        assertEquals(1, view.pairs.size)
        assertTrue(view.pairs.single().seconds > 0)
        assertEquals(SwitchDay("2026-09-26", 1), view.switches.single())
    }

    @Test fun suppliesTimezoneTransitionToCore() {
        val now = Instant.parse("2026-11-02T12:00:00Z").toEpochMilli()
        val model = Model(emptyList(), emptyList(), emptyMap(), emptyList(), null, emptyList(),
            systemZone = "America/New_York")
        InsightsData.snapshot(model, 3, now) { _, _, offsets ->
            val rows = JSONArray(offsets)
            assertTrue(rows.length() >= 2)
            assertEquals(-240, rows.getJSONArray(0).getInt(1))
            assertEquals(-300, rows.getJSONArray(1).getInt(1))
            "[]"
        }
    }
}
