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

    @Test fun retainedActionsLoseAccessWhenThePrivateChatLocks() {
        val kai = Member("kai", "Kai", "Kai", null, "#aabbcc", emptyList(), null, false)
        val dm = ChatSpace("d", "dm", "Friend")
        val private = channel("p", "member_dm", listOf("kai"), "d")
        val message = ChatMessage("m", "p", listOf("kai"), "secret", 42, null, "all", emptySet(), "own", null, emptyList())
        fun model(here: List<Entry>) = Model(listOf(kai), emptyList(), emptyMap(), here, null, emptyList(),
            spaces = listOf(dm), channels = listOf(private), chatMessages = mapOf("p" to listOf(message)))
        assertEquals(message, ChatOrganize.actionMessage(model(front("kai")), "m", null, "own"))
        assertEquals(null, ChatOrganize.actionMessage(model(front("june")), "m", "kai", "own"))
        assertEquals(null, ChatOrganize.actionMessage(model(front("kai")), "missing", null, "own"))
        assertTrue(ChatOrganize.restricted(model(front("kai")), "p"))
        assertTrue(ChatOrganize.restricted(model(front("kai")), "missing"))
    }

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

    @Test fun aPrivateChatInADmShowsOnlyWhileOurMemberInItIsHereAsSwitched() {
        val dm = ChatSpace("d", "dm", "dm")
        val chat = channel("p", "member_dm", listOf("kai", "robin"), space = "d")
        val own = setOf("kai", "june")
        assertFalse(ChatOrganize.privateVisible(chat, dm, front("june"), null, true, own))
        assertTrue(ChatOrganize.privateVisible(chat, dm, front("kai"), null, true, own))
        assertTrue(ChatOrganize.privateVisible(chat, dm, listOf(Entry("member", "kai", "cocon", false)), null, true, own))
        // not by "Read as", not with hiding turned off, not through a fronting subsystem (display only, D-092)
        assertFalse(ChatOrganize.privateVisible(chat, dm, front("june"), "kai", true, own))
        assertFalse(ChatOrganize.privateVisible(chat, dm, front("june"), null, false, own))
        // a channel kept to some members in a shared space is no one's private chat (D-155)
        val club = channel("art", members = listOf("kai"), space = "s")
        assertTrue(ChatOrganize.privateVisible(club, friends, front("june"), null, true, own))
        assertEquals(null, ChatManage.sides(club, friends, own))
        // the other side (nobody of theirs listed) sees it as any DM
        assertTrue(ChatOrganize.privateVisible(chat, dm, emptyList(), null, true, setOf("robin-self")))
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

    @Test fun anArchivedChatComesBackWithANewMessage() {
        assertTrue(ChatOrganize.archived(100, 100))
        assertTrue(ChatOrganize.archived(100, null))
        assertFalse(ChatOrganize.archived(100, 101))
        assertFalse(ChatOrganize.archived(null, 5))
        // a latest message stamped ahead of this clock still counts as already seen
        assertEquals(120L, ChatOrganize.archiveAt(100, 120))
        assertEquals(100L, ChatOrganize.archiveAt(100, 90))
        val prefs = AccountPrefs.fromProjection(org.json.JSONObject("""{"rows":{"pref":{
            "acct||chat_archived:c1":{"exists":true,"fields":{"value":100}},
            "acct||chat_archived:c2":{"exists":true,"fields":{"value":200}},
            "||chat_archived:c2":{"exists":true,"fields":{"value":null}}}}}"""), "acct")
        assertEquals(mapOf("c1" to 100L), prefs.archivedChats)
    }

    @Test fun aSharedSpaceWithSeveralChannelsIsOneEntryLikeATelegramForum() {
        assertTrue(ChatOrganize.groupsInto("shared", 2))
        assertFalse(ChatOrganize.groupsInto("shared", 1))
        assertFalse(ChatOrganize.groupsInto("internal", 9))
        assertFalse(ChatOrganize.groupsInto("dm", 3))
        data class R(val id: String, val at: Long, val unread: Int = 0, val muted: Boolean = false, val space: String? = null)
        val rows = listOf(R("general", 5), R("a", 10, 2, true, "club"), R("b", 30, 1, space = "club"), R("c", 20, 0, true, "club"), R("x", 25))
        val entries = ChatOrganize.withSpaces(rows, emptyList(), { it.id }, { it.at }, { it.id }, { it.unread }, { it.muted },
            { r -> r.space?.let { it to "Book club" } })
        assertEquals(listOf("club", "x", "general"), entries.map { (it as? ChatOrganize.SpaceEntry<*>)?.spaceId ?: (it as R).id })
        val club = entries[0] as ChatOrganize.SpaceEntry<*>
        assertEquals(listOf("b", "c", "a"), club.rows.map { (it as R).id })
        assertEquals(3, club.unread)
        assertFalse(club.muted)
        assertEquals(30L, club.at)
        // pinned where its first pinned channel is
        val pinned = ChatOrganize.withSpaces(rows, listOf("general", "c"), { it.id }, { it.at }, { it.id }, { it.unread }, { it.muted },
            { r -> r.space?.let { it to "Book club" } })
        assertEquals(listOf("general", "club", "x"), pinned.map { (it as? ChatOrganize.SpaceEntry<*>)?.spaceId ?: (it as R).id })
        assertTrue((pinned[1] as ChatOrganize.SpaceEntry<*>).pinned)
    }
}
