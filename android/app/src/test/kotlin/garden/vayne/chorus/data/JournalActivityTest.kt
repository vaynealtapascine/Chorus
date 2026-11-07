package garden.vayne.chorus.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** D-127: other accounts' activity on our posts, feed context, the action row's choices and a
 * post's notification choice. */
class JournalActivityTest {
    @Test fun activityCarriesRepliesReactionsCountsAndTargets() {
        val a = JournalActivityApi.parse(JSONObject("""{"items":[
            {"kind":"reply","at":20,"post":{"id":"r","kind":"note","text":"lovely","occurred_at":20,"reply_to":"mine",
              "author_cards":[{"id":"ash","display_name":"Ash"}],"parent":{"id":"mine","text":"A walk","occurred_at":10,"author_cards":[{"name":"Kai"}]}},
             "target":{"id":"mine","kind":"note","title":null,"text":"A walk","cw":null}},
            {"kind":"reaction","at":15,"emoji":"💜","member_id":"ash","member_name":"Ash","account_id":"them","target":{"id":"mine","text":"A walk"}}],
            "reply_counts":{"mine":1},"reactions":{"mine":[{"emoji":"💜","member_id":"ash","member_name":"Ash"}]}}"""))
        assertEquals(listOf("reply", "reaction"), a.items.map { it.kind })
        assertEquals(listOf("r"), a.replies.map { it.id })
        val reply = a.replies.single()
        assertEquals("mine", reply.replyTo)
        assertEquals(listOf("Kai"), reply.parent?.authorNames)
        assertEquals("mine", a.items[1].target?.id)
        assertEquals("Ash", a.items[1].memberName)
        assertEquals(1, a.replyCounts["mine"])
        assertEquals(listOf(PostReaction("💜", "ash", "Ash")), a.reactions["mine"])
        assertTrue(JournalActivityApi.parse(JSONObject("{}")).items.isEmpty())
    }

    @Test fun sharedPostsCarryWhatTheyQuoteAndAnswer() {
        val post = PeopleApi.sharedPosts(JSONObject("""{"items":[{"id":"q","kind":"note","text":"So true","occurred_at":2,
            "quote":{"post_id":"orig"},"quoted":{"id":"orig","text":"Rain today","occurred_at":1,"author_cards":[{"name":"Rin"}]},
            "reply_count":3}]}""")).single()
        assertEquals("orig", post.quotePostId)
        assertEquals("Rain today", post.quoted?.text)
        assertEquals(3, post.replyCount)
        assertNull(post.replyTo)
        assertNull(post.parent)
    }

    @Test fun repostsStayOutOfTheFriendsFeed() {
        val post = { id: String, repost: String? -> SharedPost(id, "note", null, "", null, 1, emptyList(), repostOf = repost) }
        val shown = FriendsFeed.shown(listOf(FriendsFeed.Source("a", "A", listOf(post("p", null), post("r", "p")), false)))
        assertEquals(listOf("p"), shown.map { it.second.id })
    }

    @Test fun aPostsNotificationChoiceTravelsOnlyWhenChosen() {
        val model = Model(listOf(Member("own", "Kai", null, null, "#fff", emptyList(), null, false, createdByAccountId = "mine")),
            emptyList(), emptyMap(), emptyList(), null, emptyList())
        val markup: (String, String) -> String = { _, _ -> """{"text":"hi","entities":[]}""" }
        fun payload(alerts: String) = PostCompose.payload(model, "mine", "note", "own", "hi", "", "", "server", "", "",
            markup = markup, alerts = alerts)
        assertEquals("delayed", payload("delayed").getString("alerts"))
        assertEquals("none", payload("none").getString("alerts"))
        assertFalse(payload("").has("alerts"))
        assertFalse(payload("loud").has("alerts"))
        // a draft keeps the choice, and a damaged value falls back to the usual
        val draft = JournalDraft("00000000-0000-4000-8000-000000000001", alerts = "none")
        assertTrue(draft.occupied)
        assertEquals("none", JournalDraft.decode(draft.encode()) { "x" }.alerts)
        assertEquals("", JournalDraft.decode(draft.encode().replace("\"none\"", "\"loud\"")) { "x" }.alerts)
    }
}
