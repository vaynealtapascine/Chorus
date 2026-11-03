package garden.vayne.chorus.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatFoldersTest {
    private val friends = ChatFolders.Custom("f1", "Friends", chats = listOf("c1"), kinds = setOf("direct"), except = setOf("c9"))

    @Test fun foldersHoldChosenChatsAndWholeKindsLessTheLeftOut() {
        val s = ChatFolders.Settings(custom = listOf(friends))
        assertTrue(ChatFolders.contains(s, "f1", "c1", "internal", 0, false))
        assertTrue(ChatFolders.contains(s, "f1", "c2", "direct", 0, false))
        assertFalse(ChatFolders.contains(s, "f1", "c9", "direct", 0, false))
        assertFalse(ChatFolders.contains(s, "f1", "c3", "shared", 0, false))
        assertTrue(ChatFolders.contains(s, "all", "c3", "shared", 0, true))
        assertTrue(ChatFolders.contains(s, "shared", "c3", "shared", 0, false))
        assertFalse(ChatFolders.contains(s, "gone", "c1", "internal", 0, false))
        val unread = s.copy(custom = listOf(friends.copy(unreadOnly = true, hideMuted = true)))
        assertFalse(ChatFolders.contains(unread, "f1", "c1", "internal", 0, false))
        assertTrue(ChatFolders.contains(unread, "f1", "c1", "internal", 2, false))
        assertFalse(ChatFolders.contains(unread, "f1", "c1", "internal", 2, true))
    }

    @Test fun puttingAChatInOrOutRespectsItsKind() {
        val out = ChatFolders.setChat(friends, "c2", "direct", false)
        assertTrue("c2" in out.except)
        assertFalse(ChatFolders.holds(out, "c2", "direct"))
        val back = ChatFolders.setChat(out, "c2", "direct", true)
        assertFalse("c2" in back.except)
        assertFalse("c2" in back.chats)
        assertTrue(ChatFolders.holds(back, "c2", "direct"))
        val chosen = ChatFolders.setChat(friends, "c5", "shared", true)
        assertEquals(listOf("c1", "c5"), chosen.chats)
        assertEquals(listOf("c1"), ChatFolders.setChat(chosen, "c5", "shared", false).chats)
        // a whole kind taken in forgets the chats chosen and left out of it
        val kinds = mapOf("c1" to "internal", "c5" to "shared", "c9" to "direct")
        val shared = ChatFolders.setKind(chosen, "shared", true) { kinds[it] }
        assertEquals(listOf("c1"), shared.chats)
        assertTrue("shared" in shared.kinds)
        assertEquals(emptySet<String>(), ChatFolders.setKind(shared, "direct", false) { kinds[it] }.except)
    }

    @Test fun orderAndHiddenFoldersSurviveARoundTrip() {
        val s = ChatFolders.Settings(order = listOf("direct", "all"), hidden = setOf("internal", "gone"), custom = listOf(friends),
            showHere = false, compact = true)
        assertEquals(listOf("direct", "all", "internal", "shared", "f1"), s.ordered)
        assertEquals(listOf("direct", "all", "shared", "f1"), s.visible)
        val moved = ChatFolders.move(s, "f1", 0)
        assertEquals(listOf("f1", "direct", "all", "internal", "shared"), moved.ordered)
        assertEquals(s, ChatFolders.move(s, "f1", 99))
        val back = ChatFolders.parse(JSONObject(ChatFolders.toJson(moved).toString()))
        assertEquals(moved.ordered, back.ordered)
        assertEquals(setOf("internal"), back.hidden)
        assertEquals(listOf(friends), back.custom)
        assertFalse(back.showHere)
        assertTrue(back.compact)
        val without = ChatFolders.without(moved, "f1")
        assertEquals(listOf("direct", "all", "internal", "shared"), without.ordered)
    }

    @Test fun oddPrefsFallBackSafely() {
        assertEquals(ChatFolders.Settings(), ChatFolders.parse(null))
        assertEquals(ChatFolders.Settings(), ChatFolders.parse("folders"))
        val odd = ChatFolders.parse(JSONObject("""{"order":[],"custom":[{"id":"all","name":"Mine"},{"id":"","name":"x"},
            {"id":"f","name":"F","kinds":["direct","bogus"],"chats":["a","a",3]},{"id":"f","name":"Again"}]}"""))
        assertEquals(ChatFolders.BUILT_IN + "f", odd.ordered)
        assertEquals(1, odd.custom.size)
        assertEquals(setOf("direct"), odd.custom.single().kinds)
        assertEquals(listOf("a"), odd.custom.single().chats)
        assertEquals("Folder", ChatFolders.label(odd.copy(custom = listOf(odd.custom.single().copy(name = " "))), "f"))
    }

    @Test fun suggestionsStartUsefulFolders() {
        val (unread, handover, unmuted) = ChatFolders.SUGGESTIONS
        assertTrue(ChatFolders.fromSuggestion("n", unread, emptyList()).unreadOnly)
        assertTrue(ChatFolders.fromSuggestion("n", unmuted, emptyList()).hideMuted)
        val ours = listOf("a" to "Handover", "b" to "general", "c" to "Front notes")
        assertEquals(listOf("a", "c"), ChatFolders.fromSuggestion("n", handover, ours).chats)
    }
}
