package garden.vayne.chorus.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WideLayoutTest {
    @Test fun breakpointIsEightForty() {
        assertFalse(WideLayout.isWide(839))
        assertTrue(WideLayout.isWide(840))
    }

    @Test fun desktopFrameWidthsAtTwelveEighty() {
        val l = WideLayout.of(1280)
        assertEquals(340, l.listDp)
        assertEquals(300, l.infoDp)
        assertTrue(l.chatDp >= 560)
    }

    @Test fun smallestTabletKeepsTheOldCompactPanes() {
        val l = WideLayout.of(840)
        assertEquals(240, l.listDp)
        assertEquals(260, l.infoDp)
        assertTrue(l.chatDp >= 280)
    }

    @Test fun midWidthsUseTheFullListFirst() {
        val l = WideLayout.of(1024)
        assertEquals(340, l.listDp)
        assertEquals(260, l.infoDp)
    }

    @Test fun chatNeverShrinksAndWidthsAreMonotonic() {
        var lastList = 0; var lastInfo = 0
        for (w in 840..2000) {
            val l = WideLayout.of(w)
            assertTrue("chat at $w", l.chatDp >= 280)
            assertTrue(l.listDp >= lastList && l.infoDp >= lastInfo)
            lastList = l.listDp; lastInfo = l.infoDp
        }
    }
}
