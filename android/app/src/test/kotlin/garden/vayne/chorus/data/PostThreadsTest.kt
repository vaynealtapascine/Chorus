package garden.vayne.chorus.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class PostThreadsTest {
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
}
