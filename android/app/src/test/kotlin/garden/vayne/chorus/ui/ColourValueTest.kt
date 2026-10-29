package garden.vayne.chorus.ui

import org.junit.Assert.*
import org.junit.Test

class ColourValueTest {
    @Test fun precisionAcceptsOnlyCompleteRgb() {
        assertEquals("#AABB09", ColourValue.normalize(" aabb09 "))
        listOf("#123", "#12345678", "oops", "#gggggg", "").forEach { assertNull(ColourValue.normalize(it)) }
    }
    @Test fun hsvKeepsBlackWhitePrimariesAndArbitraryRgb() {
        listOf("#000000", "#FFFFFF", "#FF0000", "#00FF00", "#0000FF", "#42A397", "#C0694E", "#020109").forEach {
            val hsv = ColourValue.hsv(it)
            assertEquals(it, ColourValue.hex(hsv[0], hsv[1], hsv[2]))
        }
        assertEquals("#FF0000", ColourValue.hex(360f, 1f, 1f))
        assertEquals("#808080", ColourValue.hex(230f, 0f, 0.5f))
    }
}
