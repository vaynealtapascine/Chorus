package garden.vayne.chorus

import org.junit.Assert.assertEquals
import org.junit.Test

class ShareIntentsTest {
    @Test fun repeatedClipTextDoesNotDuplicateTheDraft() {
        assertEquals("First\nSecond", combineSharedText(listOf("First", "First", "", "Second", "Second")))
    }
}
