package garden.vayne.chorus.data

import java.time.Instant
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightsDataTest {
    @Test fun namedSelectionUsesActualCoreAndNeverFallsBackToStaleLegacyIntervals() {
        val now = Instant.parse("2026-10-26T12:00:00Z").toEpochMilli()
        fun interval(id: String, member: String, layer: String, seconds: Long, front: String = "body") =
            NamedFrontInterval(id,front,FrontPresence("member",member,layer,member=="kai"),0,
                now-seconds*1000,now+60_000,id,null,480)
        val saved = listOf(SwitchRow("own","switch",now-1000,emptyList(),emptyList(),null,false),
            SwitchRow("undone","switch",now-2000,emptyList(),emptyList(),null,true))
        val history = NamedHistory("body",saved,listOf(interval("own","moon","quiet",600)),
            listOf(EffectiveFrontChange("change",now-1000,emptyList(),null,"independent",null,false,emptyList())),
            listOf(interval("a","kai","quiet",3600),interval("b","rin","quiet",1800),
                interval("c","kai","front",7200),interval("d","foreign-front","quiet",3600,"chorus")))
        val model = Model(emptyList(),emptyList(),emptyMap(),emptyList(),null,emptyList(),
            systemZone="Asia/Manila",frontIntervals=listOf(FrontInterval("stale","member","stale","front",false,now-7200000,null)))
        val view = InsightsData.snapshot(model,7,now,history=history,layerId="quiet")
        assertEquals(listOf("kai" to 3600L,"rin" to 1800L),view.daily.map { it.memberId to it.seconds })
        assertEquals(listOf(3600L,1800L),view.weekly.map { it.seconds })
        assertEquals(listOf(FrontPair("kai","rin",1800)),view.pairs)
        assertEquals(listOf(SwitchDay("2026-10-26",1)),view.switches)
        val own = InsightsData.snapshot(model,7,now,history=history,layerId="quiet",own=true)
        assertEquals(listOf("moon" to 600L),own.daily.map { it.memberId to it.seconds })
        assertEquals(emptyList<FrontPair>(),own.pairs)
        assertEquals(listOf(SwitchDay("2026-10-26",1)),own.switches)
        val unavailable = InsightsData.snapshot(model,7,now,history=history.copy(intervals=emptyList()),layerId="quiet")
        assertEquals(emptyList<FrontDay>(),unavailable.daily)
        assertEquals(emptyList<FrontPair>(),unavailable.pairs)
    }
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

    @Test fun statsPatternsAndColumnsForTheF91Screen() {
        val zone = java.time.ZoneId.of("UTC")
        val now = Instant.parse("2026-11-08T12:00:00Z").toEpochMilli() // a Sunday
        val h = 3_600_000L
        val tue = Instant.parse("2026-11-03T08:00:00Z").toEpochMilli()
        val switches = listOf(now - 2 * h, tue + 30 * 60_000, tue + 40 * 60_000, // two in the 08:00 hour this week
            Instant.parse("2026-10-30T10:00:00Z").toEpochMilli()) // last week
            .mapIndexed { i, at -> SwitchRow("s$i", "switch", at, emptyList(), emptyList(), null, false) }
        val model = Model(emptyList(), emptyList(), emptyMap(), emptyList(), null, switches, systemZone = "UTC",
            frontIntervals = listOf(
                // kai's stretch is split by a switch that kept them: one 6 h 20 m front
                FrontInterval("a", "member", "kai", "front", true, tue, tue + 3 * h),
                FrontInterval("b", "member", "kai", "front", true, tue + 3 * h, tue + 6 * h + 20 * 60_000),
                FrontInterval("c", "member", "rin", "front", false, tue + h, tue + 3 * h),
                FrontInterval("d", "member", "moss", "front", false, tue + 2 * h, tue + 4 * h)))
        val view = InsightsData.snapshot(model, 7, now) { _, _, _ ->
            """[{"day":"2026-11-03","subject_type":"member","subject_id":"kai","level":"front","seconds":22800,"as_primary_seconds":22800},
               {"day":"2026-11-03","subject_type":"member","subject_id":"rin","level":"front","seconds":7200,"as_primary_seconds":0},
               {"day":"2026-11-08","subject_type":"member","subject_id":"rin","level":"front","seconds":3600,"as_primary_seconds":0}]"""
        }
        assertEquals(1, view.previousSwitches)
        assertEquals("2 more than last week", InsightsData.switchDelta(view, InsightRange.Week))
        assertEquals(8, view.busiestHour)
        assertEquals(FrontStretch("kai", tue, 22800), view.longest)
        // two or more here from 09:00 to 12:00, whether two or three
        assertEquals(3 * 3600L, view.together)
        assertEquals("6 h 20 m", InsightsData.duration(22800))
        assertEquals("3 h", InsightsData.duration(3 * 3600 + 120, short = true))
        assertEquals(listOf("kai", "rin"), InsightsData.memberOrder(view))
        assertEquals(4, InsightsData.share(view, "kai")) // 6.3 h of the 6.5 days so far
        val days = InsightsData.columns(view, InsightRange.Week, zone)
        assertEquals(7, days.size)
        assertEquals(listOf("kai" to 22800L, "rin" to 7200L), days[1].parts)
        assertEquals(listOf("rin" to 3600L), days.last().parts)
        val months = InsightsData.columns(view.copy(from = Instant.parse("2025-11-09T00:00:00Z").toEpochMilli()), InsightRange.Year, zone)
        assertEquals(13, months.size)
        assertEquals(33600L, months.last().total)
    }
}
