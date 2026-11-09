package garden.vayne.chorus

import garden.vayne.chorus.data.Member
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.ui.switchSubjects
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchActivityTest {
    @Test fun launcherSearchNeverOffersAnotherAccountsMember() {
        val model = Model(listOf(
            Member("mine", "Kai", null, null, "#888888", emptyList(), null, false, createdByAccountId = "own"),
            Member("foreign", "Alex", null, null, "#888888", emptyList(), null, false, createdByAccountId = "other"),
        ), emptyList(), emptyMap(), emptyList(), null, emptyList())
        assertEquals(listOf("mine"), switchSubjects(model, "", "own", emptyList()).map { it.id })
        assertTrue(switchSubjects(model, "Alex", "own", emptyList()).isEmpty())
    }

    @Test fun pinsLeadTheGrid() {
        val model = Model(listOf(
            Member("kai", "Kai", null, null, "#888888", emptyList(), null, false, createdByAccountId = "own"),
            Member("moss", "Moss", null, null, "#888888", emptyList(), null, false, createdByAccountId = "own"),
        ), emptyList(), emptyMap(), emptyList(), null, emptyList())
        assertEquals(listOf("moss", "kai"), switchSubjects(model, "", "own", listOf("moss")).map { it.id })
    }
}
