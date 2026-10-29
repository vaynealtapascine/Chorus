package garden.vayne.chorus.data

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import kotlin.random.Random

class MemberOrderingTest {
    @Test fun crossClientFixtureIncludesUnicodeAndTies() {
        val fixture = JSONObject(java.io.File(System.getProperty("chorus.fixtures"), "ui_member_order.json").readText())
        val rows = fixture.getJSONArray("members")
        val members = List(rows.length()) { i -> rows.getJSONObject(i).let {
            member(it.getString("id"), it.optJSONArray("name_utf16")?.let { units ->
                CharArray(units.length()) { i -> units.getInt(i).toChar() }.concatToString()
            } ?: it.getString("name"), if (it.has("sort_key")) it.getString("sort_key") else null)
                .copy(displayName = if (it.has("display_name")) it.getString("display_name") else null)
        } }
        for (m in members) assertEquals(fixture.getJSONObject("keys").getString(m.id), MemberOrdering.key(m))
        val order = fixture.getJSONArray("order")
        assertEquals(List(order.length()) { order.getString(it) }, MemberOrdering.ordered(members.reversed()).map { it.id })
    }
    @Test fun unicodeVersionSkewProbeUsesThePinnedPortableContract() {
        // Original review probe diverged JBR vs Node. Both now preserve these non-ASCII units.
        val rows = listOf(member("z", "\u1c89"), member("a", "\u1c8a"))
        assertEquals(listOf("z", "a"), MemberOrdering.ordered(rows).map { it.id })
        assertEquals("a", MemberOrdering.ordered(rows).last().id)
        val latin = listOf(member("z", "\ua7cb"), member("a", "\u0264"))
        assertEquals(listOf("a", "z"), MemberOrdering.ordered(latin).map { it.id })
    }
    private fun member(id: String, name: String = id, key: String? = null) =
        Member(id, name, null, null, "#123456", emptyList(), null, false, sortKey = key)
    private fun apply(rows: List<Member>, patches: List<Pair<String, String>>) = rows.map { m ->
        patches.find { it.first == m.id }?.let { m.copy(sortKey = it.second) } ?: m
    }
    @Test fun oneMoveOf500WritesOneRowAndSurvivesProjection() {
        val rows = List(500) { member("m$it", "Member ${it.toString().padStart(3, '0')}") }
        val target = rows.map { it.id }.toMutableList().apply { add(3, removeAt(499)) }
        val patches = MemberOrdering.patches(rows, "mine", target, setOf("m499"))
        assertEquals(1, patches.size)
        assertEquals(target, MemberOrdering.ordered(apply(rows, patches)).map { it.id })
        val p = JSONObject().put("rows", JSONObject().put("member", JSONObject().also { members ->
            for (m in apply(rows, patches)) members.put(m.id, JSONObject().put("exists", true)
                .put("fields", JSONObject().put("name", m.name).put("sort_key", m.sortKey)))
        }))
        assertEquals(target, MemberOrdering.ordered(Model.parse(p, "mine").members).map { it.id })
    }
    @Test fun tiesUseIdsAndCanBeSplitWithoutRebalancing() {
        val rows = listOf(member("b", "same", "legacy"), member("a", "same", "legacy"), member("c", "same", "legacy"))
        val key = MemberOrdering.move(rows, "mine", "c", "b")!!
        assertEquals(listOf("a", "c", "b"), MemberOrdering.ordered(rows.map { if (it.id == "c") it.copy(sortKey = key) else it }).map { it.id })
        assertEquals(MemberOrdering.ordered(rows), MemberOrdering.ordered(rows.reversed()))
    }
    @Test fun externalArchivedStaleTargetsAndCancelledDraftsDoNotWrite() {
        val rows = listOf(member("a"), member("b"), member("c").copy(archived = true), member("d").copy(createdByAccountId = "other"))
        assertEquals(listOf("a", "b"), MemberOrdering.eligible(rows, "mine").map { it.id })
        assertNull(MemberOrdering.move(rows, "mine", "d", "a"))
        assertNull(MemberOrdering.move(rows, "mine", "b", "c"))
        assertNull(MemberOrdering.move(rows, "mine", "b", "missing"))
        assertTrue(MemberOrdering.patches(rows, "mine", listOf("a", "b"), emptySet()).isEmpty())
        assertTrue(MemberOrdering.patches(rows, "mine", listOf("a", "b"), setOf("b")).isEmpty())
    }
    @Test fun manyDraftMovesConvergeAndRemainSparse() {
        val random = Random(7)
        val original = List(40) { member("id$it", "n$it") }
        val target = MemberOrdering.ordered(original).map { it.id }.toMutableList()
        val moved = mutableSetOf<String>()
        repeat(100) {
            val id = target.removeAt(random.nextInt(target.size))
            target.add(random.nextInt(target.size + 1), id); moved.add(id)
            val patches = MemberOrdering.patches(original, "mine", target, moved)
            assertTrue(patches.size <= moved.size)
            assertEquals(target, MemberOrdering.ordered(apply(original, patches)).map { it.id })
        }
        var rows = original
        repeat(400) {
            val sorted = MemberOrdering.ordered(rows)
            val id = sorted.last().id
            val key = MemberOrdering.move(rows, "mine", id, sorted[1].id)!!
            rows = apply(rows, listOf(id to key))
            assertEquals(id, MemberOrdering.ordered(rows)[1].id)
        }
    }
 @Test fun pathologicalLegacyPositionsRemainStrictAndSparse() {
  val keys=listOf(null,"","legacy","~ui6:","~ui6: ","~ui6:a","~ui6:a!","~ui6:a  !","~ui6:~","~ui6:~~","~ui6:\u00e9","~ui6:\n","duplicate","duplicate")
  var rows=keys.mapIndexed { i,key-> member("id"+i.toString().padStart(2,'0'),"Name$i",key) }
  val random=Random(6509)
  repeat(600) {
   val current=MemberOrdering.ordered(rows)
   val expected=current.map {it.id}.toMutableList()
   val id=expected.removeAt(random.nextInt(expected.size))
   expected.add(random.nextInt(expected.size+1),id)
   val patches=MemberOrdering.patches(rows,"mine",expected,setOf(id))
   assertTrue(patches.size<=1)
   rows=rows.map {m-> patches.find {it.first==m.id}?.let {m.copy(sortKey=it.second)}?:m}
   assertEquals(expected,MemberOrdering.ordered(rows).map {it.id})
   val sorted=MemberOrdering.ordered(rows).map {MemberOrdering.key(it)}
   assertTrue(sorted.zipWithNext().all { (a,b)->a<b })
  }
 }
}
