package garden.vayne.chorus.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import garden.vayne.chorus.ui.parseSwitchTime
import java.text.SimpleDateFormat
import java.util.Locale

class FrontTest {
    @Test fun switchPayloadCarriesLevelsPrimaryNoteAndNotification() {
        val payload = Front.switchPayload(
            listOf(Entry("member", "a", "front", true), Entry("group", "b", "cocon", false)),
            "  backdated  ", "silent",
        )
        val entries = payload.getJSONArray("entries")
        assertEquals(2, entries.length())
        assertEquals("group", entries.getJSONObject(1).getString("subject_type"))
        assertEquals("cocon", entries.getJSONObject(1).getString("level"))
        assertFalse(entries.getJSONObject(1).getBoolean("is_primary"))
        assertEquals("backdated", payload.getString("note"))
        assertEquals("silent", payload.getString("notify"))
        assertFalse(Front.switchPayload(emptyList()).has("notify"))
    }

    @Test fun typedTimeRejectsPartialOrInvalidDates() {
        val input = "2026-09-23 18:45"
        assertEquals(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(input)!!.time, parseSwitchTime(input))
        assertEquals(null, parseSwitchTime("2026-02-30 18:45"))
        assertEquals(null, parseSwitchTime("2026-09-23 18:45 trailing"))
    }
}
