package garden.vayne.chorus.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class MemberListTest {
    @Test fun listRowsAndMembershipSurviveProjectionParsing() {
        val p = JSONObject("""{"rows":{"member_list":{"live":{"exists":true,"fields":{"name":"Close","description":"Notes","visibility":{"mode":"private"}}},"gone":{"exists":true,"fields":{"name":"Gone","deleted_at":3}}}},"sets":{"member_list_item":{"live|{\"member_id\":\"kai\"}":true,"live|{\"member_id\":\"rin\"}":false,"live|bad-json":true}}}""")
        val lists = Model.parse(p, "mine").memberLists
        assertEquals(1, lists.size)
        assertEquals("Close", lists.single().name)
        assertEquals(setOf("kai"), lists.single().memberIds)
    }
}
