package garden.vayne.chorus.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class StoreChangesTest {
    @Test fun removedIdsAreReadEvenAlongsideIncomingOps() {
        val changes = JSONObject("""{"ops":[{"id":"new"}],"removed":["old-a","old-b"],"hlc_last":"clock"}""")
        assertEquals(listOf("old-a", "old-b"), removedOpIds(changes))
    }

    @Test fun olderChangesWithoutRemovalsKeepStoredOps() {
        assertEquals(emptyList<String>(), removedOpIds(JSONObject("""{"ops":[],"hlc_last":"clock"}""")))
    }
}
