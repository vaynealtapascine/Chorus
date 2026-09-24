package garden.vayne.chorus.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SpacesTest {
    @Test fun directoryAndConnectionsKeepOtherAccountIdentity() {
        val directory = Spaces.directory(JSONObject("""{"items":[{"id":"s","kind":"dm","owner_account_id":"me","accounts":[{"id":"me","handle":"me","display_name":"Me"},{"id":"them","handle":"them","display_name":null}]}]}"""))
        assertEquals("@them", directory.getValue("s").accounts.last().shownName)
        assertEquals("me", directory.getValue("s").ownerAccountId)

        val follows = JSONObject("""{"following":[{"status":"active","account":{"id":"them","handle":"them","display_name":null}},{"status":"pending","account":{"id":"pending","handle":"p"}}],"followers":[{"status":"active","account":{"id":"them","handle":"them"}},{"status":"active","account":{"id":"another","display_name":"Another"}}]}""")
        assertEquals(listOf("them", "another"), Spaces.connected(follows).map { it.id })
        assertFalse(Spaces.connected(follows).any { it.id == "pending" })
    }

    @Test fun publicForeignAuthorCardUsesDisplayNameAndHasSafeFallback() {
        val cards = Spaces.authors(JSONObject("""{"accounts":[],"members":[{"id":"a","account_id":"them","name":"Moss","display_name":"Mx Moss"},{"id":"b","account_id":"them","name":null,"display_name":null}]}"""))
        assertEquals("Mx Moss", cards.getValue("a").name)
        assertEquals("Someone", cards.getValue("b").name)
    }
}
