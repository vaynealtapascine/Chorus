package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreMessageSearchTest {
    @Test fun coreReceivesAllLocalMessageFieldsAndControlsResultOrder() {
        val model = Model(listOf(Member("kai", "Kai Vale", null, null, "#fff", emptyList(), null, false)),
            emptyList(), emptyMap(), emptyList(), null, emptyList(),
            searchMessages = listOf(
                SearchDocument("old", "Messages", 10, "a link", authors = listOf("kai"), channelId = "c",
                    hasLink = true, pinned = true),
                SearchDocument("new", "Messages", 20, "an image", cw = "warning", authors = listOf("kai"),
                    channelId = "c", mimes = listOf("image/png"))))
        val search = CoreMessageSearch(model)
        val result = search.search("from:Kai is:pinned", 30, 480,
            parse = { raw -> assertEquals("from:Kai is:pinned", raw); "{\"words\":[]}" },
            filter = { query, candidates, context ->
                assertEquals("{\"words\":[]}", query)
                assertEquals(480, JSONObject(context).getInt("tz_offset_min"))
                val rows = JSONArray(candidates)
                assertEquals("Kai Vale", rows.getJSONObject(0).getJSONArray("authors").getJSONArray(0).getString(1))
                assertTrue(rows.getJSONObject(0).getBoolean("link"))
                assertTrue(rows.getJSONObject(0).getBoolean("pinned"))
                assertEquals("image/png", rows.getJSONObject(1).getJSONArray("mimes").getString(0))
                assertEquals("warning", rows.getJSONObject(1).getString("cw"))
                "[0,1]"
            })
        assertTrue(result.validQuery)
        assertEquals(listOf("new", "old"), result.items.map { it.id })
    }

    @Test fun parserErrorStopsFiltering() {
        val search = CoreMessageSearch(Model.Empty)
        val result = search.search("has:missing", 0, 0,
            parse = { throw IllegalArgumentException("{\"pos\":0,\"message\":\"has is one of image, file\"}") },
            filter = { _, _, _ -> error("must not filter") })
        assertFalse(result.validQuery)
        assertEquals("has is one of image, file", result.error)
    }
}
