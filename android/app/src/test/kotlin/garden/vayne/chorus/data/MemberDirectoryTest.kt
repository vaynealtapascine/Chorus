package garden.vayne.chorus.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemberDirectoryTest {
    private fun member(id: String, archived: Boolean = false, by: String? = null) = Member(id, id.replaceFirstChar { it.uppercase() }, null, null,
        "#aaaaaa", emptyList(), null, archived, createdByAccountId = by)

    // garden is subsystem g (kai and wren inside); wren is subsystem w (moss inside); "t" is a plain group
    private fun model(current: List<Entry> = emptyList(), groups: List<Group>? = null) =
        Model(listOf(member("garden"), member("kai"), member("wren"), member("moss"), member("old", archived = true), member("fox"),
            member("guest", by = "other")),
            groups ?: listOf(Group("g", "Garden", "subsystem", null, null), Group("w", "Wren", "subsystem", "g", null),
                Group("t", "Tag", "group", null, null)),
            mapOf("g" to setOf("kai", "wren", "old"), "w" to setOf("moss"), "t" to setOf("fox", "kai")),
            current, null, emptyList(), accountPrefs = AccountPrefs(memberSubsystems = mapOf("garden" to "g", "wren" to "w")))

    @Test fun countsOwnActiveFrontingAndArchivedMembers() {
        val m = model(listOf(Entry("member", "kai", "front", true), Entry("member", "old", "front", false), Entry("group", "t", "front", false)))
        // guest belongs to another account; old is archived, so it fronts but isn't counted as fronting
        assertEquals(MemberDirectory.Counts(all = 5, fronting = 1, archived = 1), MemberDirectory.counts(m, "acc"))
        assertEquals(setOf("kai", "old"), MemberDirectory.frontingIds(m))
    }

    @Test fun groupLinesCountActiveMembersAndSayWhoSeesOnlyOnceChosen() {
        val m = model()
        assertEquals(listOf("kai", "wren"), MemberDirectory.members(m, "g").map { it.id })
        assertEquals("2 members", MemberDirectory.line(m, m.group("g")!!))
        val seen = model(groups = listOf(Group("t", "Tag", "group", null, null, visibility = """{"mode":"server"}"""),
            Group("p", "Quiet", "group", null, null, visibility = """{"mode":"private"}""")))
        assertEquals("2 members · everyone here can see it", MemberDirectory.line(seen, seen.group("t")!!))
        assertEquals("0 members · only our system", MemberDirectory.line(seen, seen.group("p")!!))
        assertEquals("Fox, Kai", MemberDirectory.names(MemberDirectory.members(m, "t")))
        assertEquals("Garden +3", MemberDirectory.names(listOf(member("garden"), member("kai"), member("wren"), member("moss")), shown = 1))
    }

    @Test fun anyOwnMemberCanJoinAPlainGroupButNotASubsystemThatHoldsThem() {
        val m = model()
        val plain = MemberDirectory.candidates(m, m.group("t")!!, "acc")
        assertEquals(listOf("fox", "kai"), plain.filter { it.inside }.map { it.member.id })
        assertTrue(plain.none { it.loops })
        assertFalse(plain.any { it.member.id == "guest" || it.member.id == "old" })
        // wren's own subsystem: wren can't be inside, and garden (who holds wren) would make a loop
        val inner = MemberDirectory.candidates(m, m.group("w")!!, "acc")
        assertFalse(inner.any { it.member.id == "wren" })
        assertTrue(inner.first { it.member.id == "garden" }.loops)
        assertEquals("moss", inner.first().member.id)
        assertEquals(listOf("g", "t"), MemberDirectory.groupsOf(m, "kai").map { it.id })
        assertEquals("group.add_member", MemberDirectory.membershipOp(false))
        assertEquals("group.remove_member", MemberDirectory.membershipOp(true))
    }
}
