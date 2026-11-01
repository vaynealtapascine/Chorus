package garden.vayne.chorus.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatManageTest {
    private val dm = ChatSpace("d", "dm", "dm")
    private fun chat(id: String, members: List<String>, kind: String = "member_dm") = ChatChannel(id, "d", kind, id, null, members)

    @Test fun saysWhoseAPrivateChatIsOnEachSide() {
        assertEquals(ChatManage.Sides(listOf("kai"), listOf("robin")), ChatManage.sides(chat("p", listOf("kai", "robin")), dm, setOf("kai")))
        assertNull(ChatManage.sides(chat("p", listOf("kai")), ChatSpace("h", "internal", "Home"), setOf("kai")))
        assertNull(ChatManage.sides(chat("dm", emptyList(), "text"), dm, setOf("kai")))
    }

    @Test fun findsAnExistingPrivateChatInsteadOfMakingAnother() {
        val own = setOf("kai", "june")
        val list = listOf(chat("dm", emptyList(), "text"), chat("p1", listOf("kai", "robin")), chat("p2", listOf("kai", "june")))
        assertEquals("p1", ChatManage.existingPrivate(list, "d", "kai", own)?.id)
        assertNull(ChatManage.existingPrivate(list, "d", "june", own))
        val payload = ChatManage.privatePayload("d", Member("kai", "Kai", "Kai", null, "#aabbcc", emptyList(), null, false))
        assertEquals("member_dm", payload.getString("kind"))
        assertEquals("[\"kai\"]", payload.getJSONArray("member_ids").toString())
    }

    @Test fun privateSpeakerCannotFallBackToAnotherMember() {
        assertEquals("kai", ChatManage.keptSpeaker(listOf("kai"), "june", listOf("june")))
        assertEquals("june", ChatManage.keptSpeaker(listOf("kai", "june"), null, listOf("june")))
        assertEquals("kai", ChatManage.keptSpeaker(listOf("kai", "june"), "kai", listOf("june")))
        assertEquals("june", ChatManage.keptSpeaker(emptyList(), "june", emptyList()))
    }

    @Test fun knowsWhoMayChangeASpace() {
        val info = SpaceInfo("s", "shared", "o", listOf(SpaceAccount("o", null, null), SpaceAccount("a", null, null), SpaceAccount("m", null, null)))
        val roles = mapOf("s|a" to "admin")
        assertEquals("owner", ChatManage.roleIn(roles, "s", info, "o"))
        assertEquals("admin", ChatManage.roleIn(roles, "s", info, "a"))
        assertEquals("member", ChatManage.roleIn(roles, "s", info, "m"))
        assertNull(ChatManage.roleIn(roles, "s", info, "x"))
        assertTrue(ChatManage.canEditSpace("shared", "admin"))
        assertFalse(ChatManage.canEditSpace("dm", "owner"))
        assertFalse(ChatManage.canInvite("shared", "admin"))
        assertTrue(ChatManage.canInvite("shared", "owner"))
    }

    @Test fun groupEditsWriteOnlyWhatChanged() {
        val group = ChatSpace("s", "shared", "Club", "Books")
        assertEquals(0, ChatManage.spaceChanges(group, " Club ", "Books").length())
        assertEquals(setOf("name"), ChatManage.spaceChanges(group, "Book club", "Books").keys().asSequence().toSet())
        assertTrue(ChatManage.spaceChanges(group, "Club", " ").isNull("description"))
    }
}
