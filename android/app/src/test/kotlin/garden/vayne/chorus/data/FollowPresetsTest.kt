package garden.vayne.chorus.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class FollowPresetsTest {
    private val core: (String) -> String = { choice -> when (choice) {
        "gentle" -> """{"delay":{"min_s":300,"max_s":1200},"digest_only":false}"""
        "off" -> """{"switch_notifications":false}"""
        else -> "{}"
    } }

    @Test fun matchingIgnoresJsonObjectKeyOrderAndSeparateSharingFlags() {
        val override = JSONObject("""{"share_history":true,"digest_only":false,"delay":{"max_s":1200,"min_s":300}}""")
        assertEquals("gentle", FollowPresets.choiceOf(override, core))
        assertEquals("custom", FollowPresets.choiceOf(JSONObject("""{"delay":{"min_s":90}}"""), core))
    }

    @Test fun choosingPresetKeepsSeparateHistoryAndStatsLimits() {
        val old = JSONObject("""{"share_history":true,"share_stats":false,"delay":{"min_s":300}}""")
        val next = FollowPresets.ceiling("off", old, core)
        assertEquals(false, next.getBoolean("switch_notifications"))
        assertEquals(true, next.getBoolean("share_history"))
        assertEquals(false, next.getBoolean("share_stats"))
        assertEquals("inherit", FollowPresets.choiceOf(FollowPresets.ceiling("inherit", JSONObject(), core), core))
    }
}
