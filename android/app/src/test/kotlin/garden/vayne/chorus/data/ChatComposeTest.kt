package garden.vayne.chorus.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatComposeTest {
    @Test
    fun representativeSequencesSelectAnyNumberOfJointAuthorsWithoutADefault() {
        val members = (0 until 64).map { index -> Member("m$index", "Member $index", null, null,
            "#aabbcc", listOf("〔$index〕"), null, false, createdByAccountId = "ours") }
        val many = withMembers(members)
        val prefixes = members.joinToString("") { it.sigils.single() }
        val payload = ChatCompose.payload(many, "channel", null, "$prefixes hello", "", "all", emptySet(),
            "internal", accountId = "ours")
        assertEquals("hello", payload.getString("text"))
        assertEquals(members.map { it.id }, (0 until 64).map { payload.getJSONArray("authors").getString(it) })
        assertEquals(members.map { it.id }, ChatCompose.preview(many, "$prefixes hello", null, "ours").authors)
        assertEquals("hello", ChatCompose.preview(many, "〔0〕 hello", null, "ours").text)
    }

    @Test
    fun foreignRepresentativeCharactersCannotSelectAnAuthor() {
        val foreign = model.members.single().copy(id = "foreign", sigils = listOf("::foreign::"), createdByAccountId = "other")
        val mixed = withMembers(model.members + foreign)
        val preview = ChatCompose.preview(mixed, "::foreign:: hello", null, "ours")
        assertTrue(preview.authors.isEmpty())
        assertEquals("::foreign:: hello", preview.text)
    }

    @Test
    fun explicitSigilsKeepMultiCharacterUnicodeAndSegmentPreferences() {
        val members = model.members + model.members.single().copy(id = "moon", sigils = listOf("👩🏽‍🚀"))
        val together = withMembers(members, AccountPrefs(segmentParsing = false))
        val payload = ChatCompose.payload(together, "channel", null, "🌌👩🏽‍🚀 hi\n👩🏽‍🚀 still together", "", "all",
            emptySet(), "internal")
        assertEquals("hi\n👩🏽‍🚀 still together", payload.getString("text"))
        assertEquals(2, payload.getJSONArray("authors").length())
        assertEquals(1, payload.getJSONArray("segments").length())
    }

    private val model = Model(listOf(Member("kai", "Kai", null, null, "#aabbcc", listOf("🌌"), null, false,
        proxyTags = listOf(ProxyTag("k:", "")))), emptyList(), emptyMap(), emptyList(), null, emptyList())
    private fun withMembers(members: List<Member>, prefs: AccountPrefs = AccountPrefs()) =
        Model(members, emptyList(), emptyMap(), emptyList(), null, emptyList(), accountPrefs = prefs)
    private val composed: (String, String, String, String, String) -> String = { draft, speakers, _, authors, _ ->
        assertTrue(speakers.contains("\"prefix\":\"k:\""))
        assertEquals("[\"kai\"]", authors)
        """{"rich":{"text":"${if (draft.contains("hello")) "hello" else "message"}","entities":[{"type":"bold","offset":0,"length":5}]},"authors":["kai"],"segments":[]}"""
    }

    @Test
    fun coreCompositionAndRestrictedPayloadReachAndroid() {
        val payload = ChatCompose.payload(model, "channel", "kai", "k: **hello**", "Sensitive", "members", setOf("kai"), "internal", composed)
        assertEquals("hello", payload.getString("text"))
        assertEquals("kai", payload.getJSONArray("authors").getString(0))
        assertTrue(payload.getJSONArray("entities").toString().contains("bold"))
        assertEquals("Sensitive", payload.getString("cw"))
        assertEquals("kai", payload.getJSONObject("visibility").getJSONArray("member_ids").getString(0))
    }

    @Test
    fun sharedAsideIsExplicitAndInternalAsideIsRejected() {
        val aside = ChatCompose.payload(model, "channel", "kai", "hello", "", "system_only", emptySet(), "shared", composed,
            replyTo = "parent-message")
        assertEquals("system_only", aside.getJSONObject("visibility").getString("mode"))
        assertEquals("parent-message", aside.getString("reply_to"))
        val ordinary = ChatCompose.payload(model, "channel", "kai", "hello", "", "all", emptySet(), "shared", composed)
        assertFalse(ordinary.has("visibility"))
        var rejected = false
        try { ChatCompose.payload(model, "channel", "kai", "hello", "", "system_only", emptySet(), "internal", composed) }
        catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
    }
}
