package garden.vayne.chorus.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplyTest {
    private fun m(id: String, archived: Boolean = false) = Member(id, id.uppercase(), null, null, "#C0694E", emptyList(), null, archived)
    private fun model(members: List<Member>, current: List<Entry>) = Model(members, emptyList(), emptyMap(), current, null, emptyList())

    @Test fun explicitlyEmptyConfiguredPoolDoesNotPickTheOnlySystemMember() {
        val body = ResolvedFront("body", "Body", emptyList(), emptyList(), null, "independent", null, false,
            emptyList(), emptyList(), emptyMap())
        val named = NamedFronts("{}", emptyList(), emptyMap(), mapOf("body" to body), "body")
        fun configured(member: Member) = Model(listOf(member), emptyList(), emptyMap(), emptyList(), null, emptyList(), namedFronts = named)
        assertNull(Reply.speaker(configured(m("kai"))))
        assertEquals("me", Reply.speaker(configured(m("me").copy(isSelf = true)))?.id)
        // Legacy accounts retain the old sole-active-member default.
        assertEquals("kai", Reply.speaker(model(listOf(m("kai")), emptyList()))?.id)
    }

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

    @Test
    fun theMentionedMemberSpeaksWhileActive() {
        val all = listOf(m("kai"), m("june"), m("old", archived = true))
        val cur = listOf(Entry("member", "kai", "front", true))
        assertEquals("june", Reply.speaker(model(all, cur), "june")?.id)
        assertEquals("kai", Reply.speaker(model(all, cur), "old")?.id)
        assertEquals("kai", Reply.speaker(model(all, cur), null)?.id)
    }
}
