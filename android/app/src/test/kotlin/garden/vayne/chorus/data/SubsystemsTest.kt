package garden.vayne.chorus.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubsystemsTest {
    private fun member(id: String, archived: Boolean = false) = Member(id, id.replaceFirstChar { it.uppercase() }, null, null,
        "#aaaaaa", emptyList(), null, archived)

    // garden (a member) is subsystem g: kai and wren are inside; wren is herself subsystem w with moss inside
    private fun model(current: List<Entry> = emptyList(), links: Map<String, String> = mapOf("garden" to "g", "wren" to "w")) =
        Model(listOf(member("garden"), member("kai"), member("wren"), member("moss"), member("old", archived = true), member("fox")),
            listOf(Group("g", "Garden", "subsystem", null, null), Group("w", "Wren", "subsystem", "g", null),
                Group("t", "Tag", "group", null, null), Group("s", "Stars", "subsystem", null, null),
                Group("s2", "Inner", "subsystem", "s", null)),
            mapOf("g" to setOf("kai", "wren", "old", "garden"), "w" to setOf("moss"), "t" to setOf("fox"),
                "s" to setOf("fox"), "s2" to setOf("kai")),
            current, null, emptyList(), accountPrefs = AccountPrefs(memberSubsystems = links))

    @Test fun partsAreTheLinkedGroupsActiveMembersButNeverThemselves() {
        val m = model()
        assertEquals(listOf("kai", "wren"), Subsystems.parts(m, "garden").map { it.id })
        assertEquals(listOf("moss"), Subsystems.parts(m, "wren").map { it.id })
        assertEquals(emptyList<String>(), Subsystems.parts(m, "fox").map { it.id })
        assertEquals(listOf("garden"), Subsystems.containers(m, "wren").map { it.id })
        assertEquals("garden", Subsystems.memberOf(m, "g")?.id)
        assertNull(Subsystems.memberOf(m, "s"))
        // a link to a plain group or a missing group isn't a subsystem
        assertNull(Subsystems.groupOf(model(links = mapOf("fox" to "t")), "fox"))
        assertNull(Subsystems.groupOf(model(links = mapOf("fox" to "gone")), "fox"))
        assertEquals(listOf("s", "s2"), Subsystems.unlinkedGroups(m).map { it.id })
    }

    @Test fun aMemberCannotEndUpInsideThemselves() {
        val m = model()
        assertTrue(Subsystems.wouldLoop(m, "garden", "garden"))
        // wren is inside garden, and moss inside wren: neither can hold garden
        assertTrue(Subsystems.wouldLoop(m, "wren", "garden"))
        assertTrue(Subsystems.wouldLoop(m, "moss", "garden"))
        assertFalse(Subsystems.wouldLoop(m, "garden", "fox"))
    }

    @Test fun aFrontingSubsystemMemberBringsEveryoneInsideAtItsLayer() {
        val m = model(listOf(Entry("member", "garden", "front", true), Entry("member", "kai", "cocon", false)))
        val out = Subsystems.expand(m, m.current)
        // kai keeps their own place; wren comes in with garden and brings moss
        assertEquals(listOf(Entry("member", "garden", "front", true), Entry("member", "kai", "cocon", false),
            Entry("member", "wren", "front", false), Entry("member", "moss", "front", false)), out.entries)
        assertEquals(mapOf("wren" to Subsystems.Via("garden", "Garden"), "moss" to Subsystems.Via("wren", "Wren")), out.via)
    }

    @Test fun aSubsystemGroupFrontingAsAUnitBringsItsNestedMembers() {
        val m = model(listOf(Entry("group", "s", "front", true)))
        val out = Subsystems.expand(m, m.current)
        assertEquals(listOf("fox", "kai"), out.entries.drop(1).map { it.subjectId }.sorted())
        assertTrue(out.entries.drop(1).all { it.level == "front" && !it.isPrimary })
    }

    @Test fun nothingIsAddedWithoutAFrontingSubsystem() {
        val m = model(listOf(Entry("member", "fox", "front", true)))
        assertEquals(m.current, Subsystems.expand(m, m.current).entries)
    }

    @Test fun linksComeFromPrefsAndAPendingClearHidesTheStampedLink() {
        val p = JSONObject("""{"rows":{"pref":{
            "acct||member_subsystem:garden":{"exists":true,"fields":{"value":"g"}},
            "acct||member_subsystem:wren":{"exists":true,"fields":{"value":"w"}},
            "||member_subsystem:wren":{"exists":true,"fields":{"value":null}},
            "acct|phone|member_subsystem:fox":{"exists":true,"fields":{"value":"s"}}}}}""")
        assertEquals(mapOf("garden" to "g"), AccountPrefs.fromProjection(p, "acct").memberSubsystems)
        assertEquals("member_subsystem:kai", Subsystems.payload("kai", null).getString("key"))
        assertTrue(Subsystems.payload("kai", null).isNull("value"))
    }

    @Test fun theNewGroupNestsUnderTheOneSubsystemTheMemberIsIn() {
        val m = model()
        val wren = Subsystems.groupPayload(m, m.member("wren")!!)
        assertEquals("g", wren.getString("parent_id"))
        assertEquals("subsystem", wren.getString("kind"))
        assertFalse(wren.getBoolean("can_front"))
        // fox is in one subsystem besides a plain group; kai is in two subsystems, so no single parent
        assertEquals("s", Subsystems.groupPayload(m, m.member("fox")!!).getString("parent_id"))
        assertFalse(Subsystems.groupPayload(m, m.member("kai")!!).has("parent_id"))
    }
}
