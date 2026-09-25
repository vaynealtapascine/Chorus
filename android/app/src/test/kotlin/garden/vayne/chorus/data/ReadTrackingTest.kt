package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadTrackingTest {
    private fun row(at: Long, id: String) = JSONObject().put("exists", true)
        .put("fields", JSONObject().put("last_read_message_at", at).put("last_read_message_id", id))

    @Test fun onlyOwnAndPendingMarksAreUsedWithTheFurthestWinning() {
        val p = JSONObject().put("rows", JSONObject().put("read_state", JSONObject()
            .put("c|account|", row(20, "m2"))
            .put("c||", row(10, "m1"))
            .put("c|account|kai", row(15, "m3"))
            .put("c||kai", row(15, "m4"))
            .put("c|other|rin", row(99, "foreign"))))
        val marks = ReadTracking.marks(p, "account")["c"].orEmpty()
        assertEquals(listOf(ReadMark("", 20, "m2"), ReadMark("kai", 15, "m4")), marks)
        assertFalse(ReadTracking.needsMark(15, "m3", marks, "kai"))
        assertTrue(ReadTracking.needsMark(16, "m5", marks, "kai"))
    }

    @Test fun coreReceivesOnlyMemberFrontAndReturnsReaderAndUnseenIds() {
        val front = listOf(Entry("member", "kai", "front", true), Entry("group", "g", "front", false),
            Entry("member", "rin", "cocon", false))
        assertEquals(listOf("", "kai", "rin"), ReadTracking.readers(true, front) { enabled, json ->
            assertTrue(enabled)
            val rows = JSONArray(json)
            assertEquals(2, rows.length())
            assertEquals("kai", rows.getJSONObject(0).getString("member_id"))
            "[\"\",\"kai\",\"rin\"]"
        })
        val marks = listOf(ReadMark("kai", 10, "m1"), ReadMark("rin", 9, "m0"))
        assertEquals(listOf("rin"), ReadTracking.unseen(10, "m1", marks) { at, id, json ->
            assertEquals(10, at)
            assertEquals("m1", id)
            assertEquals("rin", JSONArray(json).getJSONObject(1).getString("member"))
            "[\"rin\"]"
        })
    }

    @Test fun pendingPrefTakesPrecedenceForReadTracking() {
        fun row(value: Boolean) = JSONObject().put("exists", true)
            .put("fields", JSONObject().put("value", value))
        val p = JSONObject().put("rows", JSONObject().put("pref", JSONObject()
            .put("account||chat.read_per_member", row(false))
            .put("||chat.read_per_member", row(true))))
        assertTrue(AccountPrefs.fromProjection(p, "account").readPerMember)
    }
}
