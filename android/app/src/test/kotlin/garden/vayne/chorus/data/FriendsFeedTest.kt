package garden.vayne.chorus.data

import org.junit.Assert.assertEquals
import org.junit.Test

class FriendsFeedTest {
    private fun post(id: String, at: Long) = SharedPost(id, "note", null, id, null, at, emptyList())

    @Test fun showsDownToWhereEveryAccountWithMoreIsLoadedAndAsksThoseForOlderPosts() {
        val busy = FriendsFeed.Source("b", "Busy", listOf(post("b1", 90), post("b2", 50)), more = true)
        val quiet = FriendsFeed.Source("q", "Quiet", listOf(post("q1", 70), post("q2", 10)), more = false)
        assertEquals(50L, FriendsFeed.frontier(listOf(busy, quiet)))
        // q2 (10) waits: Busy may have posts between 50 and 10 that belong above it
        assertEquals(listOf("b1", "q1", "b2"), FriendsFeed.shown(listOf(busy, quiet)).map { it.second.id })
        assertEquals(listOf(0), FriendsFeed.toLoad(listOf(busy, quiet)))
        // Busy's next page comes back short: nothing older, so everything shows
        val done = listOf(busy.copy(posts = busy.posts + post("b3", 30), more = false), quiet)
        assertEquals(listOf("b1", "q1", "b2", "b3", "q2"), FriendsFeed.shown(done).map { it.second.id })
        assertEquals(emptyList<Int>(), FriendsFeed.toLoad(done))
    }

    @Test fun aPostSharedTwiceShowsOnce() {
        val a = FriendsFeed.Source("a", "A", listOf(post("x", 5)), more = false)
        val b = FriendsFeed.Source("b", "B", listOf(post("x", 5)), more = false)
        assertEquals(listOf("A" to "x"), FriendsFeed.shown(listOf(a, b)).map { it.first to it.second.id })
    }
}
