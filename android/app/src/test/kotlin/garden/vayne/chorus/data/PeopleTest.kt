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

    @Test fun sharedPostsKeepWarningAndNeverUseLiteralNullAuthor() {
        val posts = PeopleApi.sharedPosts(JSONObject("""{"items":[{"id":"p1","kind":"entry","title":"Dear diary","text":"private detail","cw":"heavy topic","occurred_at":123,"author_cards":[{"name":"Kai","display_name":null}]},{"id":"p2","kind":"note","title":null,"text":"Hello","cw":null,"occurred_at":124,"author_cards":[]}]}"""))
        assertEquals("heavy topic", posts[0].cw)
        assertEquals("Dear diary", posts[0].title)
        assertEquals(listOf("Kai"), posts[0].authorNames)
        assertEquals(null, posts[1].title)
        assertEquals(null, posts[1].cw)
        assertEquals(emptyList<String>(), posts[1].authorNames)
    }
}
