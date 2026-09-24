package garden.vayne.chorus.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatComposeTest {
    private val model = Model(listOf(Member("kai", "Kai", null, null, "#aabbcc", listOf("🌌"), null, false,
        proxyTags = listOf(ProxyTag("k:", "")))), emptyList(), emptyMap(), emptyList(), null, emptyList())
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
        val aside = ChatCompose.payload(model, "channel", "kai", "hello", "", "system_only", emptySet(), "shared", composed)
        assertEquals("system_only", aside.getJSONObject("visibility").getString("mode"))
        val ordinary = ChatCompose.payload(model, "channel", "kai", "hello", "", "all", emptySet(), "shared", composed)
        assertFalse(ordinary.has("visibility"))
        var rejected = false
        try { ChatCompose.payload(model, "channel", "kai", "hello", "", "system_only", emptySet(), "internal", composed) }
        catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
    }
}
