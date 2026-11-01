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
}
