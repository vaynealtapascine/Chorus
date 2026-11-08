package garden.vayne.chorus.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WordsTest {
    @Test
    fun readsTheStoredWordsAndFallsBack() {
        assertEquals(Words.Default, Words.parse(null))
        assertEquals(Words.Default, Words.parse(JSONObject("""{"member":["member","members"]}""")))
        val w = Words.parse(JSONObject("""{"system":["Collective","collectives"],"front":["steer","Steering"]}"""))
        assertEquals(Words("collective", "collectives", "steer", "steering"), w)
    }

    @Test
    fun keepsOtherWordsWhenSaving() {
        val base = JSONObject("""{"member":["headmate","headmates"]}""")
        val out = Words.payload(base, Words("household", "households", "presence", "present"))
        assertEquals("headmate", out.getJSONArray("member").getString(0))
        assertEquals(listOf("household", "households"), out.getJSONArray("system").let { listOf(it.getString(0), it.getString(1)) })
        assertEquals("present", out.getJSONArray("front").getString(1))
        assertEquals("""{"member":["headmate","headmates"]}""", base.toString())
    }

    @Test
    fun customWords() {
        assertEquals("crew" to "crews", Words.customSystem(" Crew "))
        assertEquals("party" to "parties", Words.customSystem("party"))
        assertEquals("bus" to "buses", Words.customSystem("bus"))
        assertNull(Words.customSystem("  "))
        assertEquals("driving" to "driving", Words.customFront("Driving"))
        assertEquals("out" to "out", Words.customFront("out"))
    }
}
