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

    @Test fun aNewPostsDefaultAudienceFailsClosedLikeWeb() {
        val live = fixture.getJSONObject("buckets").keys().asSequence().toSet()
        val cases = fixture.getJSONArray("post_default")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val a = ProfileAudience.of(c.opt("value")).live(live)
            assertEquals(c.getString("name"), c.getJSONObject("audience").toString(), a.toJson().toString())
        }
    }

    @Test fun aDraftFollowsTheDefaultOnlyUntilItHoldsSomething() {
        val friends = Audience("followers")
        val empty = JournalDraft("id")
        assertEquals("followers", empty.following(friends).audience)
        assertFalse(empty.following(friends).occupied)
        assertSame(empty, empty.following(Audience.PRIVATE))
        // words being written never widen because the default changed
        val writing = empty.copy(body = "words")
        assertEquals("private", writing.following(friends).audience)
        // a chosen audience stays, and counts as something to keep
        val chosen = empty.copy(audience = "server", audienceChosen = true)
        assertEquals("server", chosen.following(friends).audience)
        assertTrue(chosen.occupied)
    }

    @Test fun aDraftFromBeforeTheDefaultKeepsItsChoice() {
        val old = JSONObject(JournalDraft("0e0f6f0e-6f3c-4b6e-9c55-3a1e5f8c2d11", audience = "followers").encode()).apply { remove("audienceChosen") }
        assertTrue(JournalDraft.decode(old.toString()) { "new" }.audienceChosen)
        assertFalse(JournalDraft.decode(JournalDraft("0e0f6f0e-6f3c-4b6e-9c55-3a1e5f8c2d11").encode().let { JSONObject(it).apply { remove("audienceChosen") }.toString() }) { "new" }.audienceChosen)
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
