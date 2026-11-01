package garden.vayne.chorus.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AttachPicksTest {
    @Test fun picksKeepTapOrderAndStopAtTheLimit() {
        var picked = emptyList<String>()
        for (id in listOf("a", "b", "c")) picked = AttachPicks.toggle(picked, id)
        assertEquals(listOf("a", "b", "c"), picked)
        picked = AttachPicks.toggle(picked, "b")
        assertEquals(listOf("a", "c"), picked)
        assertEquals("2", AttachPicks.number(picked, "c"))
        assertNull(AttachPicks.number(picked, "b"))
        // files already in the composer count toward the ten
        assertEquals(listOf("a", "c"), AttachPicks.toggle(picked, "d", already = AttachPicks.MAX - 2))
        // deselecting always works, even at the limit
        assertEquals(listOf("c"), AttachPicks.toggle(picked, "a", already = AttachPicks.MAX))
    }

    @Test fun labels() {
        assertEquals("0:07", AttachPicks.duration(7_400))
        assertEquals("12:30", AttachPicks.duration(750_000))
        assertEquals("1:02:03", AttachPicks.duration(3_723_000))
        assertEquals("Add", AttachPicks.addLabel(0))
        assertEquals("Add 3", AttachPicks.addLabel(3))
        assertEquals("PDF", AttachPicks.extension("notes.pdf"))
        assertEquals("FILE", AttachPicks.extension("README"))
        assertEquals("FILE", AttachPicks.extension("archive.verylongext"))
    }
}
