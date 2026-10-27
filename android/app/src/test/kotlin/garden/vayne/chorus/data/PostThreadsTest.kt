package garden.vayne.chorus.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class PostThreadsTest {
    @Test fun keepsAudienceFilteredPollDefinitionAndCounts() {
        val post = PostThreads.parse(JSONObject("""{"replies":[{"id":"foreign","kind":"note","text":"Dinner?","occurred_at":1,"author_cards":[],"poll":{"options":[{"id":"a","text":"Soup"},{"id":"b","text":"Rice"}],"multiple":true},"poll_tally":{"counts":{"a":2,"b":3},"voters":4}}]}""")).single()
        assertEquals(listOf("Soup", "Rice"), post.poll?.options?.map { it.text })
        assertEquals(true, post.poll?.multiple)
        assertEquals(PollTally(mapOf("a" to 2L, "b" to 3L), 4), post.pollTally)
    }

    @Test fun parsesReadableRepliesAndKeepsCwCollapsedInTheReadModel() {
        val replies = PostThreads.parse(JSONObject("""{"replies":[{"id":"remote","author_cards":[{"name":"June","display_name":null}],"text":"hidden body","title":"Hidden title","cw":"heavy topic","occurred_at":200},{"id":"plain","author_cards":[],"text":"hello","title":null,"cw":null,"occurred_at":300}]}"""))
        assertEquals(listOf("June"), replies[0].authorNames)
        assertEquals("heavy topic", replies[0].cw)
        assertEquals("Hidden title", replies[0].title)
        assertEquals(null, replies[1].cw)
        assertEquals(emptyList<String>(), replies[1].authorNames)
    }

    @Test fun pendingOwnReplyAppearsOnceAlongsideServerReplies() {
        val local = listOf(ThreadReply("mine", listOf("Kai"), "offline", null, null, 100))
        val remote = listOf(ThreadReply("theirs", listOf("June"), "online", null, null, 90),
            ThreadReply("mine", listOf("Kai"), "synced", null, null, 100))
        assertEquals(listOf("theirs", "mine"), PostThreads.merge(local, remote).map { it.id })
        assertEquals("synced", PostThreads.merge(local, remote)[1].text)
        assertEquals(listOf("mine"), PostThreads.merge(local, emptyList()).map { it.id })
    }

    @Test fun detailKeepsReactionsFromTheServer() {
        val detail = PostThreads.parseDetail(JSONObject("""{"replies":[],"reactions":[{"emoji":"💜","member_id":"june","member_name":"June"}]}"""))
        assertEquals(emptyList<ThreadReply>(), detail.replies)
        assertEquals(listOf(PostReaction("💜", "june", "June")), detail.reactions)
    }
}
