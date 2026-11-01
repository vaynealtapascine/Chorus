package garden.vayne.chorus.data

import org.junit.Assert.*
import org.junit.Test

class JournalBucketTest {
    @Test fun preservesBuckets() {
        val value = Audience("buckets", listOf("a", "b"))
        val json = PostCompose.visibilityPayload(value, setOf("a", "b")).getJSONObject("visibility")
        assertEquals("buckets", json.getString("mode"))
        assertEquals(listOf("a", "b"), (0 until json.getJSONArray("bucket_ids").length()).map { json.getJSONArray("bucket_ids").getString(it) })
    }
    @Test fun refusesMissingBucketsWithoutWidening() {
        for (ids in listOf(emptyList(), listOf("gone"), listOf("a", "gone"))) {
            try {
                PostCompose.visibilityPayload(Audience("buckets", ids), setOf("a"))
                fail("Invalid bucket selection accepted")
            } catch (_: IllegalArgumentException) { }
        }
    }
}
