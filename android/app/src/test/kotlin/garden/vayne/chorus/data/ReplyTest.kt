package garden.vayne.chorus.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplyTest {
    private fun m(id: String, archived: Boolean = false) = Member(id, id.uppercase(), null, null, "#C0694E", emptyList(), null, archived)
    private fun model(members: List<Member>, current: List<Entry>) = Model(members, emptyList(), emptyMap(), current, null, emptyList())

    @Test
    fun speaksAsThePrimaryFronterThenTheFirstFronter() {
        val all = listOf(m("kai"), m("june"), m("rin"))
        val cur = listOf(Entry("member", "kai", "front", false), Entry("member", "june", "front", true), Entry("member", "rin", "cocon", false))
        assertEquals("june", Reply.speaker(model(all, cur))?.id)
        assertEquals("kai", Reply.speaker(model(all, cur.map { it.copy(isPrimary = false) }))?.id)
    }

    @Test
    fun aPersonSpeaksAsThemselvesAndAnEmptyFrontHasNoSpeaker() {
        assertEquals("me", Reply.speaker(model(listOf(m("me"), m("gone", archived = true)), emptyList()))?.id)
        assertNull(Reply.speaker(model(listOf(m("kai"), m("june")), listOf(Entry("member", "kai", "cocon", false)))))
    }
}
