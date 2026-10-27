package garden.vayne.chorus.ui

import garden.vayne.chorus.data.NamedFront
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrontsScreenTest {
    @Test fun purposePatchesAreSparseAndKeepDefaultEmptyAndSelectionDistinct() {
        assertEquals("""{"authorship_layers":["front","quiet"]}""", purposePatch("authorship", setOf("quiet", "front")).toString())
        assertTrue(purposePatch("notifications", null).isNull("notification_layers"))
        // one purpose never overwrites the others: it is a nested sparse key
        val mentions = purposePatch("mentions", emptySet())
        assertEquals(setOf("purpose_layers"), mentions.keys().asSequence().toSet())
        assertEquals(0, mentions.getJSONObject("purpose_layers").getJSONArray("mentions").length())
        assertTrue(purposePatch("reading", null).getJSONObject("purpose_layers").isNull("reading"))
        val f = NamedFront("h", "Head", emptyList(), null, false, false, setOf("front"), null,
            mapOf("chat_alerts" to setOf("quiet"), "reading" to null))
        assertEquals(setOf("front"), selectionOf(f, "authorship"))
        assertEquals(null, selectionOf(f, "notifications"))
        assertEquals(setOf("quiet"), selectionOf(f, "chat_alerts"))
        assertEquals(null, selectionOf(f, "reading"))
        assertEquals(null, JSONObject().optJSONArray("x"))
    }
}
