package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatModelTest {
    private fun row(fields: JSONObject) = JSONObject().put("exists", true).put("fields", fields)

    @Test
    fun sharedAndDmMessagesKeepAudienceAndAttachmentMetadata() {
        val rows = JSONObject()
            .put("space", JSONObject().put("internal", row(JSONObject().put("kind", "internal").put("name", "Home")))
                .put("shared", row(JSONObject().put("kind", "shared").put("name", "Friends")))
                .put("dm", row(JSONObject().put("kind", "dm").put("name", "Sam"))))
            .put("channel", JSONObject().put("general", row(JSONObject().put("space_id", "shared").put("kind", "text").put("name", "general")))
                .put("direct", row(JSONObject().put("space_id", "dm").put("kind", "text").put("name", "chat"))))
            .put("attachment", JSONObject().put("photo", row(JSONObject().put("blob_hash", "a".repeat(64))
                .put("thumb_blob_hash", "b".repeat(64)).put("mime", "image/png").put("filename", "cat.png")
                .put("alt_text", "A cat").put("size", 42).put("is_spoiler", true))))
            .put("message", JSONObject().put("hello", row(JSONObject().put("channel_id", "general")
                .put("authors", JSONArray().put("kai")).put("text", "Hello").put("occurred_at", 10)
                .put("cw", "Animals").put("visibility", JSONObject().put("mode", "members")
                    .put("member_ids", JSONArray().put("kai"))).put("attachments", JSONArray().put("photo"))))
                .put("secret", row(JSONObject().put("channel_id", "direct").put("text", "Private DM").put("occurred_at", 20)))
                .put("gone", row(JSONObject().put("channel_id", "general").put("text", "Gone").put("deleted_at", 30))))
        val model = Model.parse(JSONObject().put("rows", rows), "acct")
        assertEquals(listOf("internal", "shared", "dm"), model.spaces.map { it.id })
        assertEquals(listOf("direct", "general"), model.channels.map { it.id })
        val message = model.chatMessages["general"]!!.single()
        assertEquals("Animals", message.cw)
        assertEquals("members", message.visibilityMode)
        assertEquals(setOf("kai"), message.visibleMemberIds)
        assertEquals("cat.png", message.attachments.single().filename)
        assertTrue(message.attachments.single().spoiler)
        assertEquals("Private DM", model.chatMessages["direct"]!!.single().text)
        assertFalse(model.chatMessages["general"]!!.any { it.id == "gone" })
    }

    @Test
    fun largeChannelKeepsNewestHundredForFirstScreen() {
        val messages = JSONObject()
        for (n in 0 until 120) messages.put("m$n", row(JSONObject().put("channel_id", "c")
            .put("text", "$n").put("occurred_at", n)))
        val rows = JSONObject()
            .put("space", JSONObject().put("s", row(JSONObject().put("kind", "shared").put("name", "Shared"))))
            .put("channel", JSONObject().put("c", row(JSONObject().put("space_id", "s").put("name", "general"))))
            .put("message", messages)
        val projection = JSONObject().put("rows", rows)
        val model = Model.parse(projection, "acct")
        assertEquals(100, model.chatMessages["c"]!!.size)
        assertEquals("20", model.chatMessages["c"]!!.first().text)
        assertEquals("119", model.chatMessages["c"]!!.last().text)
        val firstWindow = Model.channelWindow(projection, "c", 100)
        assertTrue(firstWindow.hasOlder)
        assertEquals(model.chatMessages["c"], firstWindow.messages)
        val expanded = Model.channelWindow(projection, "c", 200)
        assertFalse(expanded.hasOlder)
        assertEquals(120, expanded.messages.size)
        assertEquals("0", expanded.messages.first().text)
        val filtered = Model.channelWindow(projection, "c", 100) { it.occurredAt < 10 }
        assertFalse(filtered.hasOlder)
        assertEquals(10, filtered.messages.size)
    }
}
