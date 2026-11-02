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
}
