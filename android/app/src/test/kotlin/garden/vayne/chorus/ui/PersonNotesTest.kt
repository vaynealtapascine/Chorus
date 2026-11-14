package garden.vayne.chorus.ui

import garden.vayne.chorus.data.ChatFolders
import garden.vayne.chorus.data.ChatForward
import garden.vayne.chorus.data.D132Ops
import garden.vayne.chorus.data.D132Ops.GENERAL
import garden.vayne.chorus.data.D132Ops.HANDOVER
import garden.vayne.chorus.data.D132Ops.ME
import garden.vayne.chorus.data.D132Ops.NOTES
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.Special
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a person account sees of its Notes space (D-093, D-162): one Notes row, and the space nowhere. */
class PersonNotesTest {
    private val person = D132Ops.model(D132Ops.person())
    private val system = D132Ops.model(D132Ops.system())
    private fun rows(m: Model) = chatListRows(m, null, ME, emptyMap(), emptyMap(), emptyMap())

    @Test fun theChatListShowsNotesAsOneRowAndNoInternalFolder() {
        val rows = rows(person)
        assertEquals(setOf("Notes to self", "The Lanterns"), rows.map { it.title }.toSet())
        val notes = rows.first { it.channel.id == NOTES }
        assertEquals(Special.NOTES, notes.channel.special)
        assertFalse(notes.muted)          // quiet by default, and no bell for it
        assertFalse(notes.private)
        assertNull(Special.subtitle(notes.channel.special, person.isPerson))   // no "saved for every member" for one member
        // no row for the space, and the Internal folder is not offered
        assertFalse(rows.any { it.title == "Notes" })
        assertEquals(listOf("all", "shared", "direct"), person.accountPrefs.chatFolders.visibleFor(person.isPerson))
        // the Notes chat is still of the Internal kind, so All holds it
        assertTrue(ChatFolders.contains(person.accountPrefs.chatFolders, "all", NOTES, ChatFolders.kindOf(notes.channel, notes.space), 0, false))
    }

    @Test fun theForwardSheetLeadsWithSaveToNotesAndNeverNamesTheSpace() {
        val rows = rows(person)
        val shown = forwardDestinations(rows, "")
        assertEquals(listOf("Save to notes", "The Lanterns"), shown.map(::forwardTitle))
        assertEquals(NOTES, shown.first().channel.id)
        // "notes" finds it
        assertEquals(listOf(NOTES), forwardDestinations(rows, "notes").map { it.channel.id })
        assertEquals(emptyList<String>(), forwardDestinations(rows, "notes", exclude = NOTES).map { it.channel.id })
        assertEquals(ChatForward.SAVE_TO_NOTES, forwardTitle(shown.first()))
    }

    @Test fun aSystemsHandoverAndNotesAreOrdinaryRowsWithTheirRole() {
        val rows = rows(system)
        assertEquals(setOf("general", "Handover", "Notes to ourselves"), rows.map { it.title }.toSet())
        assertEquals(Special.HANDOVER, rows.first { it.channel.id == HANDOVER }.channel.special)
        assertNull(rows.first { it.channel.id == GENERAL }.channel.special)
        assertEquals("Save to notes", forwardTitle(forwardDestinations(rows, "").first()))
        assertEquals(listOf("all", "internal", "shared", "direct"), system.accountPrefs.chatFolders.visibleFor(system.isPerson))
    }
}
