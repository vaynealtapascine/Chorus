package garden.vayne.chorus.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileHighlightsTest {
    @Test fun onlyPresentMatchingHighlightsAppearOffline() {
        val key = "m|{\"post_id\":\"p\",\"profile_member_id\":\"m\"}"
        val sets = JSONObject().put("highlight", JSONObject()
            .put(key, true)
            .put("m|{\"post_id\":\"removed\",\"profile_member_id\":\"m\"}", false)
            .put("m|{\"post_id\":\"wrong\",\"profile_member_id\":\"other\"}", true)
            .put("m|broken", true))
        assertEquals(setOf("p"), ProfileHighlights.fromProjection(sets)["m"])
        val payload = ProfileHighlights.payload("m", "p")
        assertEquals("m", payload.getString("profile_member_id"))
        assertEquals("p", payload.getString("post_id"))
    }
}
