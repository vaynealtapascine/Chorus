package garden.vayne.chorus.data

import org.junit.Assert.assertEquals
import org.junit.Test

class FrontReviewsTest {
    @Test fun labelsNameTheDevice() {
        assertEquals("Keep Pixel 8’s", FrontReviews.keepLabel("Pixel 8"))
        assertEquals("Keep this device’s", FrontReviews.keepLabel("This device"))
        assertEquals("Keep the other device’s", FrontReviews.keepLabel("Another device"))
    }

    @Test fun togetherNamesBothOnlyWhenTheyDiffer() {
        assertEquals("Foxglove and Amarynthe fronted together", FrontReviews.together("Foxglove", "Amarynthe"))
        assertEquals("Foxglove fronted", FrontReviews.together("Foxglove", "Foxglove"))
    }
}
