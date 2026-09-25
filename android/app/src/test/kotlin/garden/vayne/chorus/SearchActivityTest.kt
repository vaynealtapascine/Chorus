package garden.vayne.chorus

import garden.vayne.chorus.data.Member
import garden.vayne.chorus.data.Model
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchActivityTest {
    @Test fun launcherSearchNeverOffersAnotherAccountsMember() {
        val model = Model(listOf(
            Member("mine", "Kai", null, null, "#888888", emptyList(), null, false, createdByAccountId = "own"),
            Member("foreign", "Alex", null, null, "#888888", emptyList(), null, false, createdByAccountId = "other"),
        ), emptyList(), emptyMap(), emptyList(), null, emptyList())
        assertEquals(listOf("mine"), searchSubjects(model, "", "own").map { it.id })
        assertTrue(searchSubjects(model, "Alex", "own").isEmpty())
    }
}
