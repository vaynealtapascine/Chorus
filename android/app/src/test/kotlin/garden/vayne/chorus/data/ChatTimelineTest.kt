package garden.vayne.chorus.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatTimelineTest {
    private fun interval(id: String, at: Long, level: String = "front") =
        FrontInterval(id, "member", id, level, false, at, null)

    @Test fun ordersGroupsAndBoundsEvents() {
        val events = ChatPresentation.frontEvents(listOf(
            interval("late", 130000), interval("b", 62000), interval("a", 61000),
            interval("a", 63000), interval("excluded", 60000), interval("future", 140001),
            interval("unknown", 125000)), 60000, 140000,
            { _, id -> id.takeUnless { it == "unknown" } }, { null })
        assertEquals(listOf(61000L, 130000L), events.map { it.at })
        assertEquals(listOf("a & b started fronting", "late started fronting"), events.map { it.text })
    }

    @Test fun distinctLayersRemainSeparate() {
        val events = ChatPresentation.frontEvents(listOf(interval("a", 61000), interval("b", 62000, "cocon")),
            0, 70000, { _, id -> id }, { null })
        assertEquals(listOf("a started fronting", "b became co-conscious"), events.map { it.text })
    }
    @Test fun actualColumnPlanInterleavesDatesAndTrailingEvents() {
        val midnight = java.time.LocalDate.of(2026, 11, 1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val m1 = midnight - 3600000
        val m2 = midnight + 3600000
        val events = listOf(ChatPresentation.FrontEvent(midnight - 1800000, "A"),
            ChatPresentation.FrontEvent(midnight + 60000, "B"),
            ChatPresentation.FrontEvent(midnight + 7200000, "C"),
            ChatPresentation.FrontEvent(midnight + 9000000, "D"))
        fun render(older: Long?, message: Long, newest: Boolean, name: String): List<String> {
            val plan = ChatPresentation.timelinePlan(older, message, newest, events)
            fun labels(markers: List<ChatPresentation.TimelineMarker>) = markers.map { it.event?.text ?: "date" }
            return labels(plan.before) + name + labels(plan.after)
        }
        assertEquals(listOf("date", "M1", "A", "date", "B", "M2", "C", "D"),
            render(null, m1, false, "M1") + render(m1, m2, true, "M2"))
    }
}
