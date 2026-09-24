package garden.vayne.chorus.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class PeopleTest {
    @Test fun followDirectionsKeepRequestStatusAndAccountNames() {
        val list = PeopleApi.parse(JSONObject("""{"following":[{"id":"out","account":{"id":"a","handle":"alice","display_name":null},"status":"requested"}],"followers":[{"id":"in","account":{"id":"b","handle":"bob","display_name":"Bob"},"status":"active"}]}"""))
        assertEquals("@alice", list.following.single().account.shownName)
        assertEquals("requested", list.following.single().status)
        assertEquals("Bob", list.followers.single().account.shownName)
    }

    @Test fun followerViewShowsOnlyAlreadyReleasedFrontNames() {
        val hidden = JSONObject("""{"shared":false,"entries":[{"level":"front","name":"Secret"}]}""")
        assertEquals(emptyList<String>(), PeopleApi.frontNames(hidden))
        val visible = JSONObject("""{"shared":true,"entries":[{"level":"cocon","name":"Elsewhere"},{"level":"front","name":"Kai"},{"level":"front","name":"Rin"}]}""")
        assertEquals(listOf("Kai", "Rin"), PeopleApi.frontNames(visible))
    }
}
