package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Notifications screen's rows, chips, read marks and "what shows here" (D-127). */
class InboxTest {
    private val notes = JSONArray("""[
        {"id":"m1","kind":"mention","title":"#general","text":"@Fox could you check?","delivered_at":400,
         "channel_id":"c","message_id":"msg","sender":{"name":"Amarynthe","color":"#f00"}},
        {"id":"s1","kind":"switch","text":"Kit is steering","delivered_at":300,"account":{"id":"j","display_name":"The Junipers"}},
        {"id":"o1","kind":"own_switch","title":"Fox is fronting","delivered_at":250},
        {"id":"r1","kind":"post_reply","title":"Ash replied to your post","text":"lovely","delivered_at":200,"post_id":"reply","sender":{"name":"Ash"}},
        {"id":"n1","kind":"poll_nudge","title":"Kai nudged Rin","text":"Dinner?","delivered_at":150,"message_id":"m9","channel_id":"c9","sender":{"name":"Kai"}},
        {"id":"x","kind":"something_new","delivered_at":100},
        {"id":"d1","kind":"dm","title":"Kit","text":"hi","delivered_at":90,"channel_id":"c"},
        {"id":"g1","kind":"message","title":"#general","text":"hello","delivered_at":80,"channel_id":"c"},
        {"id":"md","kind":"member_dm","title":"Amy","text":"psst","delivered_at":70,"channel_id":"c"}]""")

    @Test fun notificationsBecomeRowsInTheirChips() {
        val rows = Inbox.fromNotifications(notes)
        assertEquals(listOf("m1", "s1", "o1", "r1", "n1"), rows.map { it.id })
        assertEquals("Kai nudged Rin", rows[4].title)
        assertEquals("c9", rows[4].channelId)
        val mention = rows[0]
        assertEquals("Amarynthe mentioned you", mention.title)
        assertEquals("@Fox could you check?", mention.quote)
        assertEquals("mentions", mention.category)
        assertEquals("msg", mention.messageId)
        assertEquals("The Junipers: Kit is steering", rows[1].title)
        assertEquals("friends", rows[1].category)
        assertEquals("system", rows[2].category)
        assertEquals("reply", rows[3].postId)
        // own switches start hidden; turning them on shows them
        assertFalse(Inbox.visible(rows, "all", null).any { it.id == "o1" })
        assertTrue(Inbox.visible(rows, "all", JSONObject().put("own_switches", true)).any { it.id == "o1" })
        assertEquals(listOf("m1", "r1", "n1"), Inbox.visible(rows, "mentions", null).map { it.id })
        assertFalse(Inbox.chips(true).any { it.first == "system" })
    }

    @Test fun reactorsWeMayNotSeeAreCountedButNotNamed() {
        fun liked(vararg who: Pair<String?, String?>) = Inbox.fromActivity(JournalActivity(who.mapIndexed { i, (id, name) ->
            ActivityItem("reaction", 50L + i, emoji = "💜", memberId = id, memberName = name, target = ActivityTarget("p", "note", "Tea", "", null))
        })).single()
        assertEquals("Someone liked “Tea”", liked(null to null).title)
        assertEquals("Kit and someone else liked “Tea”", liked("kit" to "Kit", null to null).title)
        assertEquals("2 people liked “Tea”", liked(null to null, null to null).title)
        assertEquals("Kit and 2 others liked “Tea”", liked("kit" to "Kit", null to null, "jun" to "Juniper").title)
        assertEquals(listOf("Kit"), liked("kit" to "Kit", null to "Leak").faces)
    }

    @Test fun reactionsGroupAndRequestsNeedYou() {
        val activity = JournalActivity(listOf(
            ActivityItem("reaction", 50, emoji = "💜", memberId = "kit", memberName = "Kit", target = ActivityTarget("p", "note", "A quiet afternoon", "", null)),
            ActivityItem("reaction", 60, emoji = "💜", memberId = "jun", memberName = "Juniper", target = ActivityTarget("p", "note", "A quiet afternoon", "", null))))
        val liked = Inbox.fromActivity(activity).single()
        assertEquals("Kit and Juniper liked “A quiet afternoon”", liked.title)
        assertEquals(60, liked.at)
        assertEquals(listOf("Kit", "Juniper"), liked.faces)
        val follows = FollowList(emptyList(), listOf(FollowInfo("f", SpaceAccount("mb", "mossbank", "Mossbank", "system"), "requested")))
        val asks = Inbox.needsYou(follows, emptyList(), listOf(FrontReview("r", "a", "b")), false, { null }, 10)
        assertEquals(listOf("Mossbank wants to follow your system", "Two devices switched within a minute"), asks.map { it.title })
        // what needs us is never marked read, and comes first
        assertFalse(InboxRead(until = 1000).read(asks[0]))
        assertEquals("follow:f", Inbox.visible(asks + liked, "all", null).first().id)
    }

    @Test fun readMarksMoveForwardAndBackOnePostAtATime() {
        val rows = Inbox.fromNotifications(notes)
        val none = InboxRead()
        assertEquals(4, Inbox.unread(rows, none, null))
        val one = none.mark(rows[0])
        assertTrue(one.read(rows[0]))
        assertEquals(3, Inbox.unread(rows, one, null))
        // everything up to 400 read, then the mention unread again: the others keep their marks
        val all = InboxRead(400)
        val back = all.unmark(rows[0], rows)
        assertFalse(back.read(rows[0]))
        assertTrue(back.read(rows[1]) && back.read(rows[3]))
        assertEquals(back, InboxRead.parse(back.json()))
        assertEquals(InboxRead(), InboxRead.parse("nonsense"))
        assertEquals(false, Inbox.showsPayload(null, "likes", false).getJSONObject("value").getBoolean("likes"))
    }
}
