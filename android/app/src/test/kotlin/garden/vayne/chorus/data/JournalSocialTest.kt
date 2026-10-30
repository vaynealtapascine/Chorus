package garden.vayne.chorus.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class JournalSocialTest {
    private fun post(id: String, at: Long, authors: List<String> = listOf("kai"), repostOf: String? = null,
        replyTo: String? = null) = JournalPost(id, "note", authors, null, if (repostOf == null) "text $id" else "", at, null,
        "private", replyTo, null, emptyList(), repostOf = repostOf)

    private val posts = listOf(post("original", 10), post("boost-kai", 20, listOf("kai"), "original"),
        post("boost-june", 30, listOf("june"), "original"), post("reply", 40, replyTo = "original"),
        post("other", 50), post("boost-other", 60, repostOf = "other"))

    @Test fun repostsAndRepliesAreCountedLocally() {
        assertEquals(listOf("boost-kai", "boost-june"), JournalSocial.reposts(posts, "original").map { it.id })
        assertEquals(1, JournalSocial.replyCount(posts, "original"))
        assertEquals(0, JournalSocial.replyCount(posts, "other"))
    }

    @Test fun undoTargetsOnlyTheActingMembersOwnRepost() {
        assertEquals("boost-kai", JournalSocial.repostBy(posts, "original", "kai")?.id)
        assertEquals("boost-june", JournalSocial.repostBy(posts, "original", "june")?.id)
        assertNull(JournalSocial.repostBy(posts, "original", "nairon"))
        assertNull(JournalSocial.repostBy(posts, "other", "june"))
    }

    @Test fun savedViewIsNewestFirstSkipsRepostsAndUnknownIds() {
        assertEquals(listOf("other", "original"), JournalSocial.saved(posts, setOf("original", "other", "gone", "boost-kai")).map { it.id })
        assertEquals(emptyList<String>(), JournalSocial.saved(posts, emptySet()).map { it.id })
    }

    @Test fun onlyPostsByOwnMembersCanHaveTheirAudienceChanged() {
        val model = Model(listOf(Member("own", "Kai", null, null, "#fff", emptyList(), null, false, createdByAccountId = "mine"),
            Member("legacy", "June", null, null, "#fff", emptyList(), null, false),
            Member("foreign", "Alex", null, null, "#fff", emptyList(), null, false, createdByAccountId = "theirs")),
            emptyList(), emptyMap(), emptyList(), null, emptyList())
        assertTrue(JournalSocial.canEditAudience(post("a", 1, listOf("own")), model, "mine"))
        assertTrue(JournalSocial.canEditAudience(post("b", 1, listOf("legacy")), model, "mine"))
        assertTrue(JournalSocial.canEditAudience(post("c", 1, listOf("foreign", "own")), model, "mine"))
        assertFalse(JournalSocial.canEditAudience(post("d", 1, listOf("foreign")), model, "mine"))
        assertFalse(JournalSocial.canEditAudience(post("e", 1, listOf("gone")), model, "mine"))
    }

    @Test fun shareTextLeadsWithTheContentWarningThenTitleAndBody() {
        assertEquals("Content warning: food\n\nSunday\n\nRecipe", JournalSocial.shareText("Sunday", "food", "Recipe"))
        assertEquals("Hello", JournalSocial.shareText(null, " ", "Hello"))
        assertEquals("Content warning: loud", JournalSocial.shareText("", "loud", ""))
    }
}
