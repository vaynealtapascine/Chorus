package garden.vayne.chorus.data

import org.junit.Assert.assertEquals
import org.junit.Test

class JournalPresentationTest {
    private val now = 1_000_000_000L
    private fun ago(seconds: Long) = JournalPresentation.shortAgo(now - seconds * 1000, now) { "date" }

    @Test fun shortAgoIsCompactAndFallsBackToADateAfterAWeek() {
        assertEquals("now", ago(5))
        assertEquals("25m", ago(25 * 60 + 30))
        assertEquals("3h", ago(3 * 3600 + 1800))
        assertEquals("2d", ago(2 * 86_400))
        assertEquals("6d", ago(6 * 86_400 + 3600))
        assertEquals("date", ago(7 * 86_400))
        assertEquals("now", JournalPresentation.shortAgo(now + 5000, now) { "date" })
    }

    @Test fun entryMetaListsKindReadTimeAndTags() {
        assertEquals("Journal entry · 1 min read · #day #notes", JournalPresentation.entryMeta("Short", listOf("day", "notes")))
        assertEquals("Journal entry · 2 min read", JournalPresentation.entryMeta(List(250) { "word" }.joinToString(" "), emptyList()))
    }
}
