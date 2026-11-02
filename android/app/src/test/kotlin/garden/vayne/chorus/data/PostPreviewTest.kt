package garden.vayne.chorus.data

import org.junit.Assert.*
import org.junit.Test

class PostPreviewTest {
    @Test fun unicodeAndMultilinePreviews() {
        assertFalse(PostPreviews.of("a\nb\nc\nd", "[]").truncated)
        val result = PostPreviews.of("🪴".repeat(301), """[{"type":"spoiler","offset":590,"length":12},{"type":"mention","offset":598,"length":4}]""")
        assertEquals("🪴".repeat(300), result.text)
        assertTrue(result.truncated)
        val ranges = org.json.JSONArray(result.entities)
        assertEquals(1, ranges.length())
        assertEquals(10, ranges.getJSONObject(0).getInt("length"))
        assertFalse(PostPreviews.of("a".repeat(300), "[]").truncated)
    }
}
