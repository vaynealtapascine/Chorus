package garden.vayne.chorus.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Configured accounts read Body canonically; the stale legacy timeline is never consulted (D-080). */
class BodyConsumersTest {
    private val account = "acct"
    private val members = listOf("kai", "rin").map { Member(it, it.uppercase(), null, null, "#C0694E", emptyList(), null, false) }
    private val stale = listOf(SwitchRow("old", "switch", 100, listOf(Entry("member", "kai", "front", true)), emptyList(), "stale note", false))

    private fun model(authorship: List<FrontPresence>) = Model(members, emptyList(), emptyMap(),
        listOf(Entry("member", "kai", "front", true)), null, stale,
        namedFronts = NamedFronts("{\"fronts\":{}}", emptyList(), emptyMap(), mapOf(account to ResolvedFront(account, "Body",
            emptyList(), authorship, null, "independent", null, false, authorship, emptyList(), emptyMap())), account))

    @Test fun replySpeaksFromBodysAuthorshipPool() {
        assertEquals("rin", Reply.speaker(model(listOf(FrontPresence("member", "rin", "quiet", true))))?.id)
        // an explicit empty selection selects nobody, even though a legacy fronter is listed
        assertNull(Reply.speaker(model(emptyList())))
    }

    @Test fun searchIndexesSavedRecordsInsteadOfTheLegacyLog() {
        val saved = listOf(SwitchRow("n1", "switch", 200, listOf(Entry("member", "rin", "quiet", true)), emptyList(), "named note", false))
        val search = LocalSearch.fromModel(model(emptyList()), saved)
        assertEquals(listOf("n1"), search.search("named", "Switches").map { it.id })
        assertTrue(search.search("stale", "Switches").isEmpty())
        assertTrue(LocalSearch.fromModel(model(emptyList())).search("stale", "Switches").isEmpty()) // Loading: nothing.
    }
}
