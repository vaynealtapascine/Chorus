package garden.vayne.chorus.data

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatForwardTest {
    private val photo = ChatAttachment("a1", "hash", "thumb", "p.png", "image/png", 12, "a cat", false)
    private fun message(mode: String = "all") = ChatMessage("m1", "general", listOf("kai"), "Bring the blue folder.",
        42, null, mode, emptySet(), "acct", null, listOf(photo))

    @Test fun payloadCarriesTheSnapshotAndCopiedFiles() {
        val p = ChatForward.payload("notes", "rin", message(), "General", listOf("a2"))
        assertEquals("notes", p.getString("channel_id"))
        assertEquals("rin", p.getJSONArray("authors").getString(0))
        assertEquals("", p.getString("text"))
        assertEquals("m1", p.getString("forward_of_id"))
        assertEquals("a2", p.getJSONArray("attachments").getString(0))
        val item = p.getJSONArray("forward_snapshot").getJSONObject(0)
        assertEquals("General", item.getString("channel_name"))
        assertEquals("Bring the blue folder.", item.getString("text"))
        assertEquals(22, item.getInt("length"))
        assertEquals("hash", item.getJSONArray("attachments").getJSONObject(0).getString("blob_hash"))
        val copy = ChatForward.attachmentCopy(photo)
        assertEquals("thumb", copy.getString("thumb_blob_hash"))
        assertFalse(copy.has("id"))
    }

    @Test fun parsesSnapshotsForDisplay() {
        val items = ChatForward.parse(JSONArray("""[{"message_id":"m1","channel_name":"General","authors":["kai"],"text":"hi","occurred_at":5},{"text":"no id"}]"""))
        assertEquals(listOf(ForwardItem("m1", "hi", "General", listOf("kai"), 5)), items)
    }

    @Test fun asksBeforeLeavingAPrivateChannelEvenWithinTheSameDmSpace() {
        val dm = ChatSpace("d", "dm", "Friend")
        assertTrue(ChatForward.asksFirst(dm, "d", "ordinary", "general", message(), restrictedChannel = true))
        assertTrue(ChatForward.asksFirst(dm, "other", "ordinary", "general", message(), restrictedChannel = true))
        assertFalse(ChatForward.asksFirst(dm, "d", "general", "general", message(), restrictedChannel = true))
    }

    @Test fun asksBeforeLeavingTheSystemOrAnAudience() {
        val home = ChatSpace("home", "internal", "Home")
        val shared = ChatSpace("s", "shared", "Friends")
        assertTrue(ChatForward.asksFirst(home, "s", "c2", "general", message()))
        assertFalse(ChatForward.asksFirst(home, "home", "notes", "general", message()))
        assertTrue(ChatForward.asksFirst(home, "home", "notes", "general", message("members")))
        assertFalse(ChatForward.asksFirst(shared, "s", "general", "general", message("members")))
        assertFalse(ChatForward.asksFirst(shared, "other", "c3", "general", message()))
    }
}
