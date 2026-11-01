package garden.vayne.chorus.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class PostThreadsTest {
    @Test fun detailIncludesTheParentAndItsRichEntitiesWithoutLosingReplies() {
        val detail = PostThreads.parseDetail(JSONObject("""{"id":"parent","text":"Secret","occurred_at":10,"cw":"warning","entities":[{"type":"spoiler","offset":0,"length":6}],"replies":[{"id":"reply","text":"Hello","occurred_at":20}]}"""))
        assertEquals("parent", detail.root?.id)
        assertEquals("warning", detail.root?.cw)
        assertEquals("spoiler", org.json.JSONArray(detail.root?.entities).getJSONObject(0).getString("type"))
        assertEquals(listOf("reply"), detail.replies.map { it.id })
    }
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

    @Test fun detailCarriesWhatItAnswersAndHowManyRepliesEachHas() {
        val detail = PostThreads.parseDetail(JSONObject("""{"id":"reply","text":"Me too","occurred_at":30,"reply_count":2,"authors":["kai"],
            "author_cards":[{"name":"Kai","color":"#aabbcc"}],
            "ancestors":[{"id":"root","text":"Hello","occurred_at":10,"reply_count":1,"author_cards":[{"name":"June"}]}],
            "replies":[{"id":"deeper","text":"Same","occurred_at":40,"reply_count":3}]}"""))
        assertEquals(listOf("root"), detail.ancestors.map { it.id })
        assertEquals(1, detail.ancestors.single().replyCount)
        assertEquals(2, detail.root?.replyCount)
        assertEquals(listOf("kai"), detail.root?.authorIds)
        assertEquals("#aabbcc", detail.root?.color)
        assertEquals(3, detail.replies.single().replyCount)
        assertEquals(emptyList<ThreadReply>(), PostThreads.parseDetail(JSONObject("""{"replies":[]}""")).ancestors)
    }

    @Test fun ownParentsGiveContextOfflineOldestFirstAndStopAtAMissingOrLoopingOne() {
        fun post(id: String, to: String?) = JournalPost(id, "note", listOf("kai"), null, id, 0, null, "private", to, null, emptyList())
        val model = Model(listOf(Member("kai", "Kai", null, null, "#888888", emptyList(), null, false)),
            emptyList(), emptyMap(), emptyList(), null, emptyList(),
            posts = listOf(post("a", null), post("b", "a"), post("c", "b"), post("orphan", "gone"), post("x", "y"), post("y", "x")))
        assertEquals(listOf("a", "b"), PostThreads.localAncestors(model, "c").map { it.id })
        assertEquals(1, PostThreads.localAncestors(model, "c").first().replyCount)
        assertEquals(emptyList<String>(), PostThreads.localAncestors(model, "orphan").map { it.id })
        assertEquals(listOf("y"), PostThreads.localAncestors(model, "x").map { it.id })
    }

    @Test fun detailCarriesQuotesOfThePostAndWhatEachPostQuotes() {
        val detail = PostThreads.parseDetail(JSONObject("""{"id":"q","text":"Look","occurred_at":30,"quote":{"post_id":"orig"},
            "quoted":{"id":"orig","text":"Original","occurred_at":10,"author_cards":[{"name":"June"}]},
            "quotes":[{"id":"q2","text":"Me too","occurred_at":40,"quote":{"post_id":"q"}}],
            "replies":[{"id":"r","text":"Hm","occurred_at":50,"quote":{"post_id":"gone"}}]}"""))
        assert(detail.root?.quotePostId == "orig")
        assertEquals("Original", detail.root?.quoted?.text)
        assertEquals(listOf("June"), detail.root?.quoted?.authorNames)
        assertEquals(listOf("q2"), detail.quotes.map { it.id })
        assertEquals("gone", detail.replies.single().quotePostId)
        assertEquals(null, detail.replies.single().quoted)
    }
}
