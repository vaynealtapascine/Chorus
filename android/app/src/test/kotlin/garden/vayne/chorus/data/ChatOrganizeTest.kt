package garden.vayne.chorus.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatOrganizeTest {
    private val home = ChatSpace("home", "internal", "Home")
    private val friends = ChatSpace("s", "shared", "Club")
    private fun channel(id: String, kind: String = "text", members: List<String> = emptyList(), space: String = "home") =
        ChatChannel(id, space, kind, id, null, members)
    private fun front(vararg ids: String) = ids.map { Entry("member", it, "front", false) }

    @Test fun privateChatsShowOnlyWhileOneOfTheirMembersIsHere() {
        val kaiAndRin = channel("dm", "member_dm", listOf("kai", "rin"))
        assertTrue(ChatOrganize.isPrivate(kaiAndRin, home))
        assertFalse(ChatOrganize.privateVisible(kaiAndRin, home, front("june"), null, true))
        assertTrue(ChatOrganize.privateVisible(kaiAndRin, home, front("rin"), null, true))
        assertTrue(ChatOrganize.privateVisible(kaiAndRin, home, listOf(Entry("member", "kai", "cocon", false)), null, true))
        // someone merely present doesn't count; reading as a member does; the setting can be off
        assertFalse(ChatOrganize.privateVisible(kaiAndRin, home, listOf(Entry("member", "kai", "present", false)), null, true))
        assertTrue(ChatOrganize.privateVisible(kaiAndRin, home, front("june"), "kai", true))
        assertTrue(ChatOrganize.privateVisible(kaiAndRin, home, front("june"), null, false))
        // ordinary channels and other accounts' spaces are never anyone's private chat
        assertTrue(ChatOrganize.privateVisible(channel("general"), home, emptyList(), null, true))
        assertFalse(ChatOrganize.isPrivate(channel("x", members = listOf("kai"), space = "s"), friends))
        assertTrue(ChatOrganize.isPrivate(channel("art", members = listOf("kai")), home))
    }

    @Test fun pinsComeFirstInTheirOrderThenTheRestByActivity() {
        data class R(val id: String, val at: Long)
        val rows = listOf(R("a", 10), R("b", 50), R("c", 30), R("d", 40))
        val ordered = ChatOrganize.order(rows, listOf("c", "a"), { it.id }, { it.at }, { it.id }).map { it.id }
        assertEquals(listOf("c", "a", "b", "d"), ordered)
        assertEquals(listOf("a", "c"), ChatOrganize.togglePin(listOf("a"), "c"))
        assertEquals(listOf("c"), ChatOrganize.togglePin(listOf("a", "c"), "a"))
        assertEquals("[\"c\"]", ChatOrganize.pinsValue(listOf("gone", "c", "c"), setOf("c")).toString())
    }

    @Test fun aClearedChatStaysClearedUntilSomethingNewer() {
        assertTrue(ChatOrganize.cleared(100, 90))
        assertTrue(ChatOrganize.cleared(100, null))
        assertFalse(ChatOrganize.cleared(100, 101))
        assertFalse(ChatOrganize.cleared(null, 5))
    }
}
