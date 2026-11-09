package garden.vayne.chorus.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManyMembersTest {
    @Test fun changeAddsAndTakesBackInListOrder() {
        val c = ManyMembers.change(listOf("a", "b", "c", "d"), had = setOf("b", "d"), chosen = setOf("d", "c", "a"))
        assertEquals(listOf("a", "c"), c.add)
        assertEquals(listOf("b"), c.remove)
        assertTrue(ManyMembers.change(listOf("a"), setOf("a"), setOf("a")).empty)
    }

    @Test fun singleChoiceMovesEachVoteAndLeavingOutTakesItBack() {
        val poll = Poll(null, listOf(PollOption("x", "Soup"), PollOption("y", "Rice")), multiple = false)
        val state = PollState(votes = mapOf("p|a" to listOf("y"), "p|b" to listOf("x"), "p|c" to listOf("x")))
        // a moves from Rice to Soup, b keeps Soup (no op), c is left out and withdraws, d votes Soup
        assertEquals(listOf("a" to listOf("x"), "c" to emptyList(), "d" to listOf("x")),
            ManyMembers.votes(poll, state, "p", listOf("a", "b", "c", "d"), "x", setOf("a", "b", "d")))
    }

    @Test fun multipleChoiceKeepsOtherChoices() {
        val poll = Poll(null, listOf(PollOption("x", "Soup"), PollOption("y", "Rice")), multiple = true)
        val state = PollState(votes = mapOf("p|a" to listOf("y"), "p|b" to listOf("x", "y")))
        assertEquals(listOf("a" to listOf("y", "x"), "b" to listOf("y")),
            ManyMembers.votes(poll, state, "p", listOf("a", "b"), "x", setOf("a")))
    }
}
