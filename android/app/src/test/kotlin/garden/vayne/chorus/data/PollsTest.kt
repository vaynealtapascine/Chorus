package garden.vayne.chorus.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PollsTest {
    private fun row(fields: String) = JSONObject().put("exists", true).put("fields", JSONObject(fields))

    @Test fun chatVotesAsItsComposerRatherThanTheBodyDefault() {
        val members = listOf("body", "channel", "selected").map { Member(it, it, null, null, "#fff", emptyList(), null, false) }
        val model = Model(members, emptyList(), emptyMap(), listOf(Entry("member", "body", "front", true)), null, emptyList())
        assertEquals("selected", Polls.chatMember(model, "selected"))
        assertEquals("channel", Polls.chatMember(model, "channel"))
        assertNull(Polls.chatMember(model, null))
        assertNull(Polls.chatMember(model, "missing"))
    }

    @Test fun readsPollsTalliesAndOnlyOwnVotes() {
        val poll = """{"question":"Dinner?","options":[{"id":"a","text":"Soup"},{"id":"b","text":"Rice"}],"multiple":false}"""
        val p = JSONObject().put("rows", JSONObject()
            .put("message", JSONObject().put("m1", row("""{"text":"?","poll":$poll}""")))
            .put("poll_tally", JSONObject().put("m1", row("""{"channel_id":"c","counts":{"a":2,"b":1},"voters":3}""")))
            .put("poll_vote", JSONObject().put("m1|kai", row("""{"member_id":"kai","choices":["a"]}"""))))
        val state = Polls.fromProjection(p)
        assertEquals(listOf("Soup", "Rice"), state.polls["m1"]?.options?.map { it.text })
        assertEquals(PollTally(mapOf("a" to 2L, "b" to 1L), 3), state.tallies["m1"])
        assertEquals(listOf("a"), state.choices("m1", "kai"))
        assertTrue(state.choices("m1", "june").isEmpty())
        assertTrue(state.choices("m1", null).isEmpty())
        assertNull(Polls.parse(JSONObject("""{"options":[{"id":"a","text":"x"}]}""")))
    }

    @Test fun retainsOwnVotesWhenForeignParentsArriveOnlyThroughRest() {
        val projection = JSONObject().put("rows", JSONObject().put("poll_vote", JSONObject()
            .put("foreign|kai", row("""{"member_id":"kai","choices":["b"]}"""))
            .put("foreign|rin", row("""{"member_id":"rin","choices":["a"]}"""))))
        val state = Polls.fromProjection(projection)
        assertTrue(state.polls.isEmpty())
        assertEquals(listOf("b"), state.choices("foreign", "kai"))
        assertTrue(state.choices("foreign", "other").isEmpty())
    }

    @Test fun togglesAndBuildsDrafts() {
        val single = Poll(null, listOf(PollOption("a", "Soup"), PollOption("b", "Rice")), false)
        assertEquals(listOf("b"), Polls.toggled(single, listOf("a"), "b"))
        assertEquals(emptyList<String>(), Polls.toggled(single, listOf("a"), "a"))
        assertEquals(listOf("a", "b"), Polls.toggled(single.copy(multiple = true), listOf("a"), "b"))
        val draft = Polls.draft(listOf(" Soup ", "", "Rice"), true).getOrThrow()
        assertEquals("""{"options":[{"id":"a","text":"Soup"},{"id":"b","text":"Rice"}],"multiple":true}""", draft.toString())
        assertTrue(Polls.draft(listOf("Soup", " "), false).isFailure)
        assertEquals("""{"member_id":"kai","choices":[]}""", Polls.votePayload("kai", emptyList()).toString())
    }
}
