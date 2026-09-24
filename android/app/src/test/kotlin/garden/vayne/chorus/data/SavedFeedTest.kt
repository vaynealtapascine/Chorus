package garden.vayne.chorus.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SavedFeedTest {
    @Test fun onlyLiveParsedFeedsAppearOffline() {
        val model = Model.parse("""{"rows":{"feed":{"live":{"exists":true,"fields":{"name":"Entries","query":"kind:entry","query_ast":{"op":"kind","kind":"entry"},"visibility":{"mode":"followers"}}},"draft":{"exists":true,"fields":{"name":"Draft","query":"kind:note"}},"gone":{"exists":true,"fields":{"name":"Gone","query":"kind:note","query_ast":{},"deleted_at":12}}}}}""", "mine")
        assertEquals(listOf(SavedFeed("live", "Entries", null, "kind:entry", "followers")), model.savedFeeds)
    }
}
