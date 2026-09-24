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
        assertEquals(emptyList<String>(), PeopleApi.followerView(hidden).frontNames)
        val visible = JSONObject("""{"shared":true,"entries":[{"level":"cocon","name":"Elsewhere"},{"level":"front","name":"Kai"},{"level":"front","name":"Rin"}]}""")
        assertEquals(listOf("Kai", "Rin"), PeopleApi.followerView(visible).frontNames)
    }

    @Test fun followerHistoryAndStatsUseOnlyServerSharedFieldsAndPrecision() {
        val hidden = PeopleApi.followerView(JSONObject("""{"shared":false,"entries":[],"history":[{"entries":[{"name":"Secret","level":"front"}],"time":{"at":123,"precision":"exact"}}],"stats":{"days":30,"members":[{"name":"Secret","share_pct":100}]}}"""))
        assertEquals(null, hidden.history)
        assertEquals(null, hidden.stats)
        val view = PeopleApi.followerView(JSONObject("""{"entries":[{"level":"front","name":"Kai"}],"history":[{"entries":[{"name":"Kai","level":"front"}],"time":{"at":123,"precision":"none"}},{"entries":[{"name":"Rin","level":"front"}],"time":{"at":1700000000000,"precision":"part_of_day","part":"evening"}}],"stats":{"days":30,"members":[{"name":"Kai","share_pct":20},{"name":"Rin","share_pct":80}]}}"""))
        assertEquals(listOf("Kai"), view.frontNames)
        assertEquals("", view.history!![0].time)
        assertEquals(true, view.history[1].time.contains("evening"))
        assertEquals(false, view.history[1].time.contains(":"))
        assertEquals(listOf("Kai" to 20, "Rin" to 80), view.stats!!.members)
        assertEquals(null, PeopleApi.followerView(JSONObject("""{"entries":[]}""")).stats)
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
