package garden.vayne.chorus.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class ChatEditTest {
    @Test fun editingTwoSegmentsKeepsAuthorsAndUtf16Offsets() {
        val message = ChatMessage("m", "c", listOf("kai", "moss"), "Hi😀\nBye", 1, null,
            "all", emptySet(), "account", null, emptyList(),
            entities = """[{"type":"bold","offset":0,"length":4},{"type":"italic","offset":5,"length":3}]""",
            segments = listOf(ChatSegment(0, 4, listOf("kai")), ChatSegment(5, 3, listOf("moss"))))
        assertEquals(listOf("Hi😀", "Bye"), ChatEdit.parts(message) { JSONObject(it).getString("text") })
        val payload = ChatEdit.payload(message, listOf("Hi😀!", "Bye?"), "") { part, _ ->
            JSONObject().put("text", part).put("entities", org.json.JSONArray().put(JSONObject()
                .put("type", "bold").put("offset", 0).put("length", part.length))).toString()
        }
        assertEquals("Hi😀!\nBye?", payload.getString("text"))
        val segments = payload.getJSONArray("segments")
        assertEquals(5, segments.getJSONObject(0).getInt("length"))
        assertEquals(6, segments.getJSONObject(1).getInt("offset"))
        assertEquals("moss", segments.getJSONObject(1).getJSONArray("authors").getString(0))
        val entities = payload.getJSONArray("entities")
        assertEquals(6, entities.getJSONObject(1).getInt("offset"))
    }

    @Test fun originalMentionAndEmojiStillResolveAfterEditing() {
        val message = ChatMessage("m", "c", listOf("kai"), "@Kai :wave:", 1, null,
            "all", emptySet(), "account", null, emptyList(), entities = """[
            {"type":"mention","offset":0,"length":4,"target_type":"member","target_id":"kai"},
            {"type":"custom_emoji","offset":5,"length":6,"emoji_id":"wave-id"}]""")
        val model = Model(listOf(Member("kai", "Kai", null, null, "#aabbcc", emptyList(), null, false)),
            emptyList(), emptyMap(), emptyList(), null, emptyList())
        val names = JSONObject(ChatEdit.names(message, model))
        assertEquals("kai", names.getJSONObject("mentions").getJSONObject("kai").getString("target_id"))
        assertEquals("wave-id", names.getJSONObject("emoji").getString("wave"))
        val payload = ChatEdit.payload(message, listOf("@Kai :) :wave:"), names.toString()) { part, resolver ->
            val directory = JSONObject(resolver)
            JSONObject().put("text", part).put("entities", org.json.JSONArray()
                .put(JSONObject().put("type", "mention").put("offset", 0).put("length", 4)
                    .put("target_type", "member")
                    .put("target_id", directory.getJSONObject("mentions").getJSONObject("kai").getString("target_id")))
                .put(JSONObject().put("type", "custom_emoji").put("offset", part.indexOf(":wave:"))
                    .put("length", 6).put("emoji_id", directory.getJSONObject("emoji").getString("wave")))).toString()
        }
        val entities = payload.getJSONArray("entities")
        assertTrue((0 until entities.length()).map { entities.getJSONObject(it).getString("type") }.containsAll(
            listOf("mention", "custom_emoji")))
        assertEquals("wave-id", (0 until entities.length()).map { entities.getJSONObject(it) }
            .first { it.getString("type") == "custom_emoji" }.getString("emoji_id"))
    }
}
