package garden.vayne.chorus

import garden.vayne.chorus.data.Member
import garden.vayne.chorus.data.Group
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.Subject
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

    @Test fun multiSwitchKeepsOrderAndRejectsForeignOrRepeatedSubjects() {
        val model = Model(listOf(
            Member("kai", "Kai", null, null, "#888888", emptyList(), null, false, createdByAccountId = "own"),
            Member("moss", "Moss", null, null, "#888888", emptyList(), null, false, createdByAccountId = "own"),
            Member("alex", "Alex", null, null, "#888888", emptyList(), null, false, createdByAccountId = "other"),
        ), listOf(Group("inner", "Inner", "subsystem", null, null)), emptyMap(), emptyList(), null, emptyList())
        fun subject(id: String, type: String = "member") = Subject(type, id, id, "#888888", id.take(1))
        val entries = multiSwitchEntries(model, listOf(subject("moss"), subject("alex"), subject("moss"),
            subject("inner", "group"), subject("kai")), "own")
        assertEquals(listOf("moss", "inner", "kai"), entries.map { it.subjectId })
        assertEquals(listOf(true, false, false), entries.map { it.isPrimary })
    }
}
