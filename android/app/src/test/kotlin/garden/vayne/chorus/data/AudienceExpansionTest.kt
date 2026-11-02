package garden.vayne.chorus.data

import org.junit.Assert.*
import org.junit.Test

class AudienceExpansionTest {
    @Test fun expansionUsesAudienceAndBucketInclusion() {
        val a = Audience("buckets", listOf("a"))
        val ab = Audience("buckets", listOf("a", "b"))
        assertTrue(ab.possiblyWidens(a))
        assertFalse(a.possiblyWidens(ab))
        assertTrue(Audience("buckets", listOf("b")).possiblyWidens(a))
        assertFalse(a.possiblyWidens(a))
        assertFalse(a.possiblyWidens(Audience("followers")))
        assertTrue(Audience("followers").possiblyWidens(a))
        assertFalse(Audience.PRIVATE.possiblyWidens(a))
        assertTrue(a.possiblyWidens(Audience.PRIVATE))
        assertFalse(Audience("followers").possiblyWidens(Audience("server")))
        assertTrue(Audience("server").possiblyWidens(Audience("followers")))
    }
}
