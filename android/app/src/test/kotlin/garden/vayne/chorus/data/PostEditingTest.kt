package garden.vayne.chorus.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import uniffi.chorus_ffi.toMarkup

class PostEditingTest {
    @Test fun sharesWebIdentityFixture() {
        val fixture = JSONObject(java.io.File(System.getProperty("chorus.fixtures"), "ui_post_edit.json").readText())
        val text = fixture.getString("text")
        val entities = fixture.getJSONArray("entities").toString()
        val rich = JSONObject().put("text", text).put("entities", fixture.getJSONArray("entities"))
        val original = RichEditHistory.open(text, entities, toMarkup(rich.toString()))
        val cases = fixture.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val draft = c.getString("draft")
            val start = c.getInt("start"); val end = c.getInt("end")
            val insertedEnd = end + draft.length - original.current.text.length
            val state = original.input(draft, start, end, insertedEnd, insertedEnd,
                listOf(RichInputChange(start, end, start, insertedEnd)))
            val parsed = state.parse("").getJSONArray("entities")
            val ids = (0 until parsed.length()).map { parsed.getJSONObject(it).optString("target_id") }.filter { it.isNotBlank() }
            val expected = c.getJSONArray("ids").let { a -> (0 until a.length()).map { a.getString(it) } }
            assertEquals(c.getString("name"), expected, ids)
        }
    }

    /** D-134: a post's headings open as `# ` lines and save back unchanged; a literal "# " line stays literal. */
    @Test fun headingsOpenAndSaveUnchanged() {
        val entities = """[{"type":"heading","level":1,"offset":0,"length":8},{"type":"heading","level":2,"offset":9,"length":4}]"""
        val post = JournalPost("p", "entry", listOf("kai"), "T", "Saturday\nPlan\n# literal", 0, null, "private", null, null,
            emptyList(), entities = entities)
        val raw = JSONObject(PostEditing.open(post))
        assertEquals("# Saturday\n## Plan\n\\# literal", raw.getString("markup"))
        val history = RichEditHistory.open(post.text, post.entities, raw.getString("markup"), RichTextEdit.POST_NAMES)
        val payload = PostEditing.payload(post, history.legacyState(), history.current.text, "T", "")
        assertEquals(post.text, payload.getString("text"))
        val got = payload.getJSONArray("entities")
        assertEquals(2, got.length())
        assertEquals(listOf(1, 2), (0 until 2).map { got.getJSONObject(it).getInt("level") })
        // chat and bios keep "# " literal
        val chat = JSONObject(uniffi.chorus_ffi.parseMarkup("# hi", ""))
        assertEquals("# hi", chat.getString("text"))
        assertEquals(0, chat.getJSONArray("entities").length())
    }

    @Test fun headingLevelReachesTheRenderer() {
        val prepared = RichTextPresentation.prepare("Title\nbody", """[{"type":"heading","level":2,"offset":0,"length":5}]""", emptyList())
        assertEquals(listOf(BodySpan(0, 5, "heading", "", 2)), prepared.spans)
    }
}
