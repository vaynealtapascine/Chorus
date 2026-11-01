package garden.vayne.chorus.data

import org.junit.Assert.*
import org.junit.Test

class JournalSelectionTest {
    @Test fun personIgnoresExplicitAndEmptyMemberSelections() {
        assertEquals(listOf("person"), JournalSelection.authors(true, listOf("old"), "person"))
        assertEquals(listOf("person"), JournalSelection.authors(true, emptyList(), "person"))
        assertEquals(emptyList<String>(), JournalSelection.authors(false, emptyList(), "front"))
        assertEquals(listOf("front"), JournalSelection.authors(false, null, "front"))
    }
    @Test fun staleSelectionsStayRemovableInOrder() {
        val selected = listOf("archived", "active", "missing")
        assertEquals(listOf("archived", "missing"), JournalSelection.unavailable(selected, setOf("active")))
        assertEquals(listOf("active", "missing"), PostCompose.toggleAuthor(selected, "archived"))
    }
}
