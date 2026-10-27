package garden.vayne.chorus.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatLinksTest {
    private fun msg(id: String, text: String, at: Long, entities: String = "[]") =
        ChatMessage(id, "c", listOf("kai"), text, at, null, "all", emptySet(), "acct", null, emptyList(), entities = entities)

    @Test fun findsEntityAndBareLinksNewestFirstWithoutTrailingPunctuation() {
        val a = msg("m1", "Bus times: https://example.org/bus. Also go https://x.example now", 1,
            """[{"type":"url","offset":44,"length":17}]""")
        val b = msg("m2", "See the map", 2, """[{"type":"text_link","offset":8,"length":3,"url":"https://maps.example.com/x"},""" +
            """{"type":"text_link","offset":0,"length":3,"url":"javascript:alert(1)"}]""")
        val c = msg("m3", "two https://a.example/1 and (https://www.b.example/2)", 3)
        assertEquals(listOf("https://a.example/1", "https://www.b.example/2", "https://maps.example.com/x",
            "https://x.example", "https://example.org/bus"), ChatLibrary.links(listOf(a, b, c)).map { it.url })
        assertEquals("b.example", ChatLibrary.domain("https://www.b.example/2"))
        assertEquals("not a url", ChatLibrary.domain("not a url"))
    }
}
