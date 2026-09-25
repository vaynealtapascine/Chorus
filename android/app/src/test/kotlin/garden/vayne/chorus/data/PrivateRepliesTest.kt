package garden.vayne.chorus.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrivateRepliesTest {
    @Test fun findsOnlyTheTwoMemberDmInTheSameSpace() {
        val channels = listOf(
            ChatChannel("wrong-space", "other", "member_dm", "", null, listOf("kai", "rin")),
            ChatChannel("group", "home", "member_dm", "", null, listOf("kai", "rin", "june")),
            ChatChannel("pair", "home", "member_dm", "", null, listOf("rin", "kai")),
        )
        assertEquals("pair", PrivateReplies.existing(channels, "home", "kai", "rin")?.id)
        assertNull(PrivateReplies.existing(channels, "home", "kai", "kai"))
    }

    @Test fun payloadAndProjectionKeepDmParticipants() {
        val kai = Member("kai", "Kai", null, null, "#fff", emptyList(), null, false)
        val rin = Member("rin", "Rin", null, null, "#fff", emptyList(), null, false)
        val payload = PrivateReplies.createPayload("home", kai, rin)
        assertEquals("member_dm", payload.getString("kind"))
        assertEquals("rin", payload.getJSONArray("member_ids").getString(1))
        fun row(fields: JSONObject) = JSONObject().put("exists", true).put("fields", fields)
        val projection = JSONObject().put("rows", JSONObject()
            .put("space", JSONObject().put("home", row(JSONObject().put("kind", "internal").put("name", "Home"))))
            .put("channel", JSONObject().put("pair", row(payload))))
        assertEquals(listOf("kai", "rin"), Model.parse(projection, "account").channels.single().memberIds)
    }
}
