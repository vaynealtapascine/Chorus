package garden.vayne.chorus.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class JournalModelTest {
    @Test fun localProjectionKeepsOfflinePostAndDropsTombstone() {
        val p = JSONObject("""{"rows":{"member":{"m":{"exists":true,"fields":{"name":"Kai","created_by_account_id":"mine"}}},"post":{"new":{"exists":true,"fields":{"kind":"entry","authors":["m"],"title":"Day","text":"Hello","occurred_at":120,"cw":"Sensitive","visibility":{"mode":"followers"},"tags":["notes"]}},"old":{"exists":true,"fields":{"kind":"note","text":"Gone","occurred_at":100,"deleted_at":150}}}}}""")
        val model = Model.parse(p, "mine")
        assertEquals(listOf("new"), model.posts.map { it.id })
        assertEquals("followers", model.posts.single().visibility)
        assertEquals("Kai", model.member("m")?.shownName)
        assertFalse(model.members.single().createdByAccountId != "mine")
    }
}
