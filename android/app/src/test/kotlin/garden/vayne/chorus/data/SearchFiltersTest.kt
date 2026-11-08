package garden.vayne.chorus.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchFiltersTest {
    @Test fun chipsWriteOneOperatorEach() {
        assertEquals("appointment from:Amarynthe", SearchFilters.set("appointment", "from", "Amarynthe"))
        assertEquals("appointment from:Kai", SearchFilters.set("appointment from:Amarynthe", "from", "Kai"))
        assertEquals("appointment", SearchFilters.set("appointment from:\"Old Name\"", "from", null))
        assertEquals("lunch in:\"Book club\"", SearchFilters.set("lunch", "in", "Book club"))
        assertEquals("lunch after:7d", SearchFilters.set("lunch after:1d", "after", "7d"))
        assertEquals("from:Kai", SearchFilters.set("", "from", "Kai"))
    }

    @Test fun chipsReadTheQuery() {
        assertEquals("Book club", SearchFilters.value("lunch in:\"Book club\" from:kai", "in"))
        assertEquals("kai", SearchFilters.value("lunch in:\"Book club\" FROM:kai", "from"))
        assertNull(SearchFilters.value("lunch", "from"))
        assertEquals("Past week", SearchFilters.dateLabel("x after:7d"))
        assertEquals("After 2026-09-01", SearchFilters.dateLabel("x after:2026-09-01"))
        assertEquals("Any date", SearchFilters.dateLabel("x"))
    }

    @Test fun otherTabsMatchTheWordsOnly() {
        assertEquals("book club", SearchFilters.words("book from:kai club after:7d"))
        assertTrue(SearchFilters.matches("book", "Book club", null))
        assertTrue(SearchFilters.matches("", "anything"))
        assertTrue(SearchFilters.matches("club lant", "Book club", "Lanterns"))
        assertFalse(SearchFilters.matches("garden", "Book club"))
    }
}
