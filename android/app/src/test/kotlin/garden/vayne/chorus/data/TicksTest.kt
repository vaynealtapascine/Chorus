package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TicksTest {
    private fun message(id: String, at: Long, account: String?, vararg authors: String) = ChatMessage(id, "c", authors.toList(), "", at,
        null, "all", emptySet(), account, null, emptyList())

    @Test fun onlyOurOwnMessagesAndThoseStillWaitingGetATickAskedOfCore() {
        var asked: Triple<JSONArray, JSONArray, JSONArray>? = null
        val ticks = Ticks.of(listOf(message("a", 1, "me", "kai"), message("b", 2, "them", "rin"), message("c", 3, null, "kai")), "me",
            listOf(ReadMark("june", 1, "a")), listOf(ReadMark("friend", 3, "c"))) { msgs, members, accounts ->
            asked = Triple(JSONArray(msgs), JSONArray(members), JSONArray(accounts))
            "[\"read\",\"pending\"]"
        }
        assertEquals(mapOf("a" to "read", "c" to "pending"), ticks)
        val (msgs, members, accounts) = asked!!
        assertEquals(2, msgs.length())
        assertEquals(true, msgs.getJSONObject(0).getBoolean("acked"))
        assertEquals(false, msgs.getJSONObject(1).getBoolean("acked"))
        assertEquals("kai", msgs.getJSONObject(0).getJSONArray("authors").getString(0))
        assertEquals("june", members.getJSONObject(0).getString("member"))
        assertEquals("friend", accounts.getJSONObject(0).getString("member"))
    }

    @Test fun aChatWithNoneOfOursAsksNothing() {
        assertTrue(Ticks.of(listOf(message("b", 2, "them", "rin")), "me", emptyList(), emptyList()) { _, _, _ -> error("not asked") }.isEmpty())
    }

    @Test fun aFetchReplacesAChannelAndAFrameOnlyMovesAReaderForward() {
        val fetched = ReadReceipts.replace(emptyMap(), "c", JSONArray().put(JSONObject().put("account_id", "a").put("message_id", "m2").put("at", 20)))
        assertEquals(mapOf("c" to listOf(ReadMark("a", 20, "m2"))), fetched)
        fun frame(account: String, id: String, at: Long) = JSONObject().put("t", "read").put("channel", "c")
            .put("account", account).put("message_id", id).put("at", at)
        assertEquals(fetched, ReadReceipts.receive(fetched, frame("a", "m1", 10)))
        assertEquals(fetched, ReadReceipts.receive(fetched, frame("a", "m2", 20)))
        val moved = ReadReceipts.receive(fetched, frame("a", "m3", 20))
        assertEquals(listOf(ReadMark("a", 20, "m3")), moved["c"])
        val two = ReadReceipts.receive(moved, frame("b", "m1", 5))
        assertEquals(listOf(ReadMark("a", 20, "m3"), ReadMark("b", 5, "m1")), two["c"])
        assertTrue(ReadReceipts.replace(two, "c", JSONArray()).isEmpty())
        assertEquals(two, ReadReceipts.receive(two, JSONObject().put("t", "read").put("channel", "c")))
    }
}
