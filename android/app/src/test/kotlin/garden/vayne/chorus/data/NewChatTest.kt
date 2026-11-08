package garden.vayne.chorus.data

import org.junit.Assert.*
import org.junit.Test

class NewChatTest {
    private fun member(id: String, pronouns: String? = null, by: String? = null, archived: Boolean = false) =
        Member(id, id.replaceFirstChar { it.uppercase() }, null, pronouns, "#fff", emptyList(), null, archived, createdByAccountId = by)

    private val members = listOf(member("violet", "she/her"), member("amarynthe"), member("foxglove"), member("nairon"),
        member("guest", by = "other"), member("old", archived = true))
    private val model = Model(members, emptyList(), emptyMap(),
        listOf(Entry("member", "amarynthe", "front", true), Entry("member", "nairon", "cocon", false), Entry("member", "foxglove", "front", false)),
        null, emptyList())

    @Test fun theSharedFolderStartsASharedSpaceAndEveryOtherFolderAMessage() {
        val settings = ChatFolders.Settings(custom = listOf(
            ChatFolders.Custom("friends", "Friends", kinds = setOf("shared")),
            ChatFolders.Custom("mixed", "Mixed", kinds = setOf("shared"), chats = listOf("c1")),
            ChatFolders.Custom("both", "Both", kinds = setOf("shared", "direct"))))
        assertEquals("shared", NewChat.startFrom("shared", settings))
        assertEquals("shared", NewChat.startFrom("friends", settings))
        for (folder in listOf("all", "internal", "direct", "mixed", "both", null)) assertEquals(folder, "message", NewChat.startFrom(folder, settings))
    }

    @Test fun membersLeaveOutTheSpeakerGuestsAndArchivedWithThoseHereFirst() {
        assertEquals(listOf("amarynthe", "nairon", "violet"), NewChat.members(model, "own", "foxglove", "").map { it.id })
        assertEquals(listOf("nairon", "foxglove", "violet"), NewChat.members(model, "own", "amarynthe", "o").map { it.id })
        assertEquals(listOf("violet"), NewChat.members(model, "own", null, "VIO").map { it.id })
    }

    @Test fun aMemberLineIsWhereTheyAreElseTheirPronouns() {
        assertEquals("co-conscious", NewChat.memberLine(model, model.member("nairon")!!))
        assertEquals("she/her", NewChat.memberLine(model, model.member("violet")!!))
    }

    @Test fun friendsAreFoundByNameOrHandleAndSayWhoIsFronting() {
        val junipers = SpaceAccount("j", "junipers", "The Junipers", "system")
        val ren = SpaceAccount("r", "ren", null, "person")
        assertEquals(listOf(ren), NewChat.friends(listOf(junipers, ren), "re"))
        assertEquals(listOf(junipers), NewChat.friends(listOf(junipers, ren), "junip"))
        assertEquals("Kit is fronting", NewChat.friendLine(junipers, listOf("Kit")))
        assertEquals("a system", NewChat.friendLine(junipers, emptyList()))
        assertEquals("one person", NewChat.friendLine(ren, null))
    }

    @Test fun theCountAndFirstNameOfANewSharedSpace() {
        val junipers = SpaceAccount("j", null, "The Junipers", "system")
        val drift = SpaceAccount("d", null, "Driftwood", "system")
        val ren = SpaceAccount("r", "ren", null, "person")
        assertNull(NewChat.selectedLabel(emptyList()))
        assertEquals("2 systems selected", NewChat.selectedLabel(listOf(junipers, drift)))
        assertEquals("1 person selected", NewChat.selectedLabel(listOf(ren)))
        assertEquals("2 selected", NewChat.selectedLabel(listOf(junipers, ren)))
        assertEquals("The Lanterns, The Junipers & Driftwood", NewChat.spaceName("The Lanterns", listOf(junipers, drift)))
        assertEquals("The Junipers & @ren", NewChat.spaceName(null, listOf(junipers, ren)))
        assertEquals(listOf(1, 2), NewChat.toggle(listOf(1), 2))
        assertEquals(listOf(2), NewChat.toggle(listOf(1, 2), 1))
    }
}
