package garden.vayne.chorus.data

import garden.vayne.chorus.data.D132Ops.AMY
import garden.vayne.chorus.data.D132Ops.GENERAL
import garden.vayne.chorus.data.D132Ops.HANDOVER
import garden.vayne.chorus.data.D132Ops.HOME
import garden.vayne.chorus.data.D132Ops.LANTERNS
import garden.vayne.chorus.data.D132Ops.MINE
import garden.vayne.chorus.data.D132Ops.NOTES
import garden.vayne.chorus.data.D132Ops.OTHER
import garden.vayne.chorus.data.D132Ops.THEIRS
import garden.vayne.chorus.data.D132Ops.THEIR_NOTES
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Handover and Notes as the projection gives them (D-132, D-162); every model is built from ops, never hand-written rows. */
class SpecialTest {
    private val person = D132Ops.model(D132Ops.person())
    private val system = D132Ops.model(D132Ops.system())

    @Test fun aPersonIsBuiltByTheRealProjectionAndKnowsItsHome() {
        assertTrue(person.isPerson)
        assertEquals(setOf("Notes", "The Lanterns"), person.spaces.map { it.name }.toSet())
        assertEquals(setOf(HOME), person.personHomes)
        assertTrue(Special.isPersonHome(person, HOME))
        assertFalse(Special.isPersonHome(person, LANTERNS))
        assertFalse(Special.isPersonHome(person, null))
        assertEquals(NOTES, Special.channel(person, Special.NOTES)?.id)
        assertNull(Special.channel(person, Special.HANDOVER))
    }

    @Test fun aSystemHasNoPersonHomeAndFindsBothChannelsByRole() {
        assertFalse(system.isPerson)
        assertTrue(system.personHomes.isEmpty())
        assertEquals(HANDOVER, Special.channel(system, Special.HANDOVER)?.id)
        assertEquals(NOTES, Special.channel(system, Special.NOTES)?.id)
        assertNull(system.channels.first { it.id == GENERAL }.special)
    }

    @Test fun anOrdinaryChannelCalledHandoverStaysOrdinary() {
        val m = D132Ops.model(D132Ops.system() + D132Ops.Spec("channel.create", "space:$HOME", MINE,
            JSONObject().put("space_id", HOME).put("kind", "text").put("name", "Handover")))
        assertNull(m.channels.first { it.id == MINE }.special)
        assertEquals(HANDOVER, Special.channel(m, Special.HANDOVER)?.id)
        val folder = ChatFolders.fromSuggestion("f", ChatFolders.SUGGESTIONS[1], m.channels.map { it.id to it.special })
        assertEquals(setOf(HANDOVER, NOTES), folder.chats.toSet())
    }

    @Test fun anotherSystemsInternalSpaceSeenAsAGuestIsNotOurs() {
        val m = D132Ops.model(D132Ops.system() + listOf(
            D132Ops.Spec("space.create", "space:$THEIRS", THEIRS, JSONObject().put("kind", "internal").put("name", "Theirs"), account = OTHER),
            D132Ops.Spec("channel.create", "space:$THEIRS", THEIR_NOTES,
                JSONObject().put("space_id", THEIRS).put("kind", "text").put("name", "Notes to ourselves").put("special", "notes"), account = OTHER)))
        assertFalse(m.spaces.first { it.id == THEIRS }.own)
        assertTrue(m.spaces.first { it.id == HOME }.own)
        assertEquals(NOTES, Special.channel(m, Special.NOTES)?.id)
    }

    @Test fun trashedNotesIsNotListedAsLive() {
        val m = D132Ops.model(D132Ops.system() + D132Ops.Spec("channel.delete", "space:$HOME", NOTES))
        assertNull(Special.channel(m, Special.NOTES))
        assertEquals(HANDOVER, Special.channel(m, Special.HANDOVER)?.id)
    }

    @Test fun subtitlesAndLevels() {
        assertEquals("notes between fronts", Special.subtitle(Special.HANDOVER, false))
        assertEquals("saved for every member", Special.subtitle(Special.NOTES, false))
        assertNull(Special.subtitle(Special.NOTES, true))
        assertNull(Special.subtitle(null, false))
        assertEquals("all", Special.defaultLevel(Special.HANDOVER, "mentions"))
        assertEquals("none", Special.defaultLevel(Special.NOTES, "mentions"))
        assertEquals("mentions", Special.defaultLevel(null, "mentions"))
        val prefs = AccountPrefs()
        assertEquals("all", prefs.channelNotify(HANDOVER, "internal", Special.HANDOVER))
        assertEquals("none", prefs.channelNotify(NOTES, "internal", Special.NOTES))
        assertEquals("mentions", prefs.channelNotify(GENERAL, "internal"))
    }

    @Test fun aPersonHasNoInternalFolder() {
        val s = ChatFolders.Settings()
        assertEquals(listOf("all", "shared", "direct"), s.visibleFor(true))
        assertEquals(listOf("all", "internal", "shared", "direct"), s.visibleFor(false))
        assertEquals(listOf("all", "shared", "direct"), s.orderedFor(true))
    }

    @Test fun theHandoverAndNotesFolderGoesByRoleNotName() {
        val folder = ChatFolders.fromSuggestion("f", ChatFolders.SUGGESTIONS[1], system.channels.map { it.id to it.special })
        assertEquals(setOf(HANDOVER, NOTES), folder.chats.toSet())
        val mine = ChatFolders.fromSuggestion("f", ChatFolders.SUGGESTIONS[1], person.channels.map { it.id to it.special })
        assertEquals(listOf(NOTES), mine.chats)
    }

    @Test fun aNoteMatchesATagOrForwardedFromChats() {
        val plain = ChatMessage("a", NOTES, listOf(AMY), "x", 1, null, "all", emptySet(), null, null, emptyList())
        val forwarded = plain.copy(id = "b", forwardOf = "z")
        assertTrue(Special.noteMatches(plain, emptyList(), ""))
        assertTrue(Special.noteMatches(plain, listOf("codes"), "codes"))
        assertFalse(Special.noteMatches(plain, listOf("codes"), "reminders"))
        assertTrue(Special.noteMatches(forwarded, emptyList(), Special.FROM_CHATS))
        assertFalse(Special.noteMatches(plain, emptyList(), Special.FROM_CHATS))
    }

    @Test fun aSentHandoverKeepsItsDefinition() {
        val extra = JSONObject().put("handover", JSONObject().put("deliver", "next_switch")
            .put("checklist", JSONArray().put(JSONObject().put("id", "c1").put("text", "Keys"))))
        val m = D132Ops.model(D132Ops.system() + D132Ops.send(HANDOVER, D132Ops.u(0x501), "Keys by the door", listOf(AMY), extra) +
            D132Ops.send(HANDOVER, D132Ops.u(0x502), "Evening", listOf(AMY)))
        val sent = m.chatMessages.getValue(HANDOVER)
        assertEquals(HandoverDef("next_switch", listOf(ChecklistDef("c1", "Keys"))), sent[0].handover)
        assertNull(sent[1].handover)
    }
}
