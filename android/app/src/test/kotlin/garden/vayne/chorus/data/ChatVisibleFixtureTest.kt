package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which chats are shown (D-097, D-098, D-110, D-102, D-155): `fixtures/ui_chat_visible.json`, the same
 * cases web's chat-visible.fixture.test.ts runs through chat-list.ts.
 */
class ChatVisibleFixtureTest {
    private val fixture = JSONObject(java.io.File(System.getProperty("chorus.fixtures"), "ui_chat_visible.json").readText())

    private fun strings(a: JSONArray?): List<String> = (0 until (a?.length() ?: 0)).map { a!!.getString(it) }

    private fun entries(a: JSONArray): List<Entry> = (0 until a.length()).map { i ->
        val e = a.getJSONObject(i)
        Entry(e.getString("subject_type"), e.getString("subject_id"), e.getString("level"), false)
    }

    /** The listed ids and everyone inside a subsystem among them, recursively, each once. */
    private fun including(inside: JSONObject): (List<String>) -> List<String> = { ids ->
        val out = ids.distinct().toMutableList()
        var i = 0
        while (i < out.size) {
            for (part in strings(inside.optJSONArray(out[i]))) if (part !in out) out.add(part)
            i++
        }
        out
    }

    @Test fun everyCaseAsTheDecisionsSay() {
        val cases = fixture.getJSONArray("cases")
        assertTrue(cases.length() > 20)
        val wrong = mutableListOf<String>()
        for (n in 0 until cases.length()) {
            val c = cases.getJSONObject(n)
            val ch = c.getJSONObject("channel")
            val space = ChatSpace("s", c.getString("space"), "Space")
            val channel = ChatChannel("c", "s", ch.getString("kind"), "Chat", null, strings(ch.getJSONArray("member_ids")),
                invalidMembership = ch.getBoolean("invalid_membership"))
            val front = entries(c.getJSONArray("front"))
            val switched = c.optJSONArray("front_switched")?.let(::entries) ?: front
            val viewingAs = if (c.isNull("viewing_as")) null else c.getString("viewing_as")
            val private = ChatOrganize.isPrivate(channel, space)
            // both rules go by the front as switched (D-155)
            val visible = ChatOrganize.privateVisible(channel, space, switched, viewingAs, c.getBoolean("hide_private"),
                strings(c.getJSONArray("own")).toSet(), including(c.getJSONObject("inside")))
            if (private != c.getBoolean("is_private")) wrong += "${c.getString("name")}: is_private $private"
            if (visible != c.getBoolean("visible")) wrong += "${c.getString("name")}: visible $visible"
        }
        assertEquals(emptyList<String>(), wrong)
    }
}
