package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Test

class ProfileAudienceTest {
    private val fixture = JSONObject(java.io.File(System.getProperty("chorus.fixtures"), "ui_profile_audience.json").readText())

    @Test fun matchesTheSharedFixtureLikeWeb() {
        val b = fixture.getJSONObject("buckets")
        val names = b.keys().asSequence().associateWith { b.getString(it) }
        val cases = fixture.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val profile = c.opt("profile").takeIf { it != JSONObject.NULL }
            val parts = c.opt("parts").takeIf { it != JSONObject.NULL }
            val a = ProfileAudience.effective(profile, parts, c.getString("key"), c.has("default"), c.opt("default").takeIf { it != JSONObject.NULL })
            assertTrue(c.getString("name"), a.same(ProfileAudience.of(c.getJSONObject("audience"))))
            assertEquals(c.getString("name"), c.getString("label"), a.label(names))
            assertEquals(c.getString("name"), c.getString("label") == "Only us", a.isPrivate(names.keys))
        }
    }

    @Test fun editsOnePartWithoutTouchingOthers() {
        val parts = JSONObject("""{"pronouns":{"mode":"private"},"banner":{"mode":"followers"}}""")
        val set = ProfileAudience.with(parts, "birthday", Audience("server"))
        assertEquals(setOf("pronouns", "banner", "birthday"), set.keys().asSequence().toSet())
        assertEquals("server", set.getJSONObject("birthday").getString("mode"))
        assertEquals(setOf("banner"), ProfileAudience.with(parts, "pronouns", null).keys().asSequence().toSet())
        assertEquals(0, ProfileAudience.with(JSONArray("[1]"), "pronouns", null).length())
        assertEquals("private", ProfileAudience.with(JSONObject("""{"x":{"mode":"everyone"}}"""), "y", null).getJSONObject("x").getString("mode"))
        assertEquals("""{"mode":"buckets","bucket_ids":["a"]}""", Audience("buckets", listOf("a")).toJson().toString())
    }

    @Test fun readsStoredTextStrictly() {
        assertNull(ProfileAudience.parse(null))
        assertNull(ProfileAudience.parse("not json"))
        assertEquals("followers", ProfileAudience.of(ProfileAudience.parse("""{"mode":"followers"}""")).mode)
        assertEquals("private", ProfileAudience.of(JSONTokener("\"followers\"").nextValue()).mode)
        assertNull(ProfileAudience.own(JSONObject(), "pronouns"))
    }
}
