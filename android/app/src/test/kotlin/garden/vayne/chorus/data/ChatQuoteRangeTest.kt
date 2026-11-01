package garden.vayne.chorus.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatQuoteRangeTest {
    @Test fun selectionsKeepSurrogatePairsWhole() {
        val text = "a\uD83D\uDE00b"
        assertEquals(1..2, ChatForward.safeRange(text, 1..1))
        assertEquals(1..2, ChatForward.safeRange(text, 2..2))
        assertEquals(0..2, ChatForward.safeRange(text, 0..1))
        assertEquals(3..3, ChatForward.safeRange(text, 3..3))
        assertEquals(text.indices, ChatForward.safeRange(text, -1..2))
        assertEquals("".indices, ChatForward.safeRange("", null))
    }
}
