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

    @Test fun theJournalTileLinksOurNewestPostsAndEntries() {
        fun post(id: String, at: Long, replyTo: String? = null, repostOf: String? = null) =
            JournalPost(id, "note", listOf("kai"), null, "text $id", at, null, "followers", replyTo, null, emptyList(), repostOf = repostOf)
        val posts = listOf(post("old", 1), post("reply", 5, replyTo = "old"), post("new", 9), post("repost", 7, repostOf = "x"), post("mid", 4))
        assertEquals(listOf("new", "mid", "old"), AttachPicks.journalChoices(posts).map { it.id })
        assertEquals(listOf("new"), AttachPicks.journalChoices(posts, limit = 1).map { it.id })
        assertEquals("https://c/post/1", AttachPicks.withLink("", "https://c/post/1"))
        assertEquals("look https://c/post/1", AttachPicks.withLink("look", "https://c/post/1"))
        assertEquals("look\nhttps://c/post/1", AttachPicks.withLink("look\n", "https://c/post/1"))
        assertEquals("2 selected", AttachPicks.selectedLabel(2))
        assertEquals(null, AttachPicks.selectedLabel(0))
    }
}
