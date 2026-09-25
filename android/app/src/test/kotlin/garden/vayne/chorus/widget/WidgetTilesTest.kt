package garden.vayne.chorus.widget

import garden.vayne.chorus.data.Entry
import garden.vayne.chorus.data.Group
import garden.vayne.chorus.data.Member
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.SwitchRow
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetTilesTest {
    private fun m(id: String, archived: Boolean = false) = Member(id, id.uppercase(), null, null, "#C0694E", emptyList(), null, archived)
    private fun sw(n: Long, id: String, retracted: Boolean = false) =
        SwitchRow("s$n", "switch", n, listOf(Entry("member", id, "front", true)), emptyList(), null, retracted)

    private val model = Model(
        members = listOf(m("ash"), m("kai"), m("june"), m("rin"), m("old", archived = true)),
        groups = listOf(Group("stars", "Stars", "subsystem", null, null), Group("inner", "Inner", "subsystem", "stars", null), Group("friends", "Friends", "group", null, null)),
        membership = mapOf("stars" to setOf("kai", "june"), "inner" to setOf("june")),
        current = emptyList(),
        since = null,
        switches = listOf(sw(1, "rin"), sw(2, "kai"), sw(3, "old"), sw(4, "ash", retracted = true)),
    )

    private fun names(t: List<WidgetTile>) = t.map {
        when (it) {
            is WidgetTile.Subject -> it.id
            is WidgetTile.Folder -> "[${it.id}]"
        }
    }

    @Test
    fun rootShowsRecentsThenTopLevelSubsystemsThenEveryoneElse() {
        // newest first; retracted and archived switches don't count; plain groups aren't folders
        assertEquals(listOf("kai", "rin", "[stars]", "ash", "june"), names(widgetTiles(model, null)))
    }

    @Test
    fun manualPinsComeFirstWithoutDuplicatingRecentsOrArchivedMembers() {
        assertEquals(listOf("june", "rin", "kai", "[stars]", "ash"),
            names(widgetTiles(model, null, pinned = listOf("june", "old", "rin", "june"))))
    }

    @Test
    fun shortcutsUseOnlyTheFirstFourLivePinnedMembers() {
        assertEquals(listOf("june", "rin", "kai", "ash"),
            PinnedShortcuts.members(model, listOf("june", "old", "rin", "kai", "ash", "june"), "acct").map { it.id })
    }

    @Test
    fun foreignMemberCannotBecomeAWidgetTileOrShortcut() {
        val mixed = Model(listOf(m("mine").copy(createdByAccountId = "acct"),
            m("guest").copy(createdByAccountId = "other")), emptyList(), emptyMap(), emptyList(), null, emptyList())
        assertEquals(listOf("mine"), names(widgetTiles(mixed, null, pinned = listOf("guest", "mine"), accountId = "acct")))
        assertEquals(listOf("mine"), PinnedShortcuts.members(mixed, listOf("guest", "mine"), "acct").map { it.id })
    }

    @Test
    fun folderShowsWholeSubsystemThenSubfoldersThenMembersByRecency() {
        assertEquals(listOf("stars", "[inner]", "kai", "june"), names(widgetTiles(model, "stars")))
    }

    @Test
    fun aMissingFolderFallsBackToRoot() {
        assertEquals(names(widgetTiles(model, null)), names(widgetTiles(model, "gone")))
    }

    @Test
    fun personAccountHasNoQuickSwitchTiles() {
        val person = Model(listOf(m("self").copy(isSelf = true)), emptyList(), emptyMap(), emptyList(), null, emptyList())
        assertEquals(emptyList<WidgetTile>(), widgetTiles(person, null))
    }
}
