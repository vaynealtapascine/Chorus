package garden.vayne.chorus.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietHoursTest {
    @Test fun savingAndClearingKeepsUnrelatedFollowPreferences() {
        val previous = JSONObject("""{"digest":true,"other":{"value":1}}""")
        val on = QuietHours.updated(previous, QuietWindow("23:00", "08:00"), 480)
        assertEquals(QuietWindow("23:00", "08:00"), QuietHours.read(on))
        assertEquals(480, on.getInt("tz_offset_min"))
        assertTrue(on.getBoolean("digest"))
        assertEquals(1, on.getJSONObject("other").getInt("value"))
        val off = QuietHours.updated(on, null, 480)
        assertNull(QuietHours.read(off))
        assertTrue(off.getBoolean("digest"))
        assertFalse(previous.has("quiet_hours"))
    }

    @Test fun rejectsMalformedLocalClockValues() {
        assertTrue(QuietHours.valid("00:00", "23:59"))
        assertFalse(QuietHours.valid("24:00", "08:00"))
        assertFalse(QuietHours.valid("9:00", "08:00"))
        assertNull(QuietHours.read(JSONObject("""{"quiet_hours":{"from":"25:00","to":"08:00"}}""")))
    }
}
