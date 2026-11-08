package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrashTest {
    private val account = "0192f8c2-0000-7000-8000-0000000000a1"
    private val other = "0192f8c2-0000-7000-8000-0000000000a2"
    private val kai = "0192f8c2-0000-7000-8000-0000000000b1"
    private val june = "0192f8c2-0000-7000-8000-0000000000b2"
    private val space = "0192f8c2-0000-7000-8000-0000000000d1"
    private val channel = "0192f8c2-0000-7000-8000-0000000000c1"
    private val day = 86_400_000L
    private val t0 = 1_790_000_000_000L

    private fun project(build: (op: (Int, String, String, String, String, String?, Long, String) -> Unit) -> Unit): JSONObject {
        val ops = JSONArray()
        build { n, kind, scope, entity, payload, member, at, by ->
            ops.put(JSONObject().put("id", "0192f8c2-0000-7000-8000-${n.toString().padStart(12, '0')}")
                .put("scope", scope).put("kind", kind).put("entity_id", entity).put("v", 1)
                .put("hlc", "${at.toString(16).padStart(12, '0')}-0000-00000001").put("device_at", at)
                .put("account_id", by).put("payload", JSONObject(payload)).put("member_id", member ?: JSONObject.NULL))
        }
        return JSONObject(uniffi.chorus_ffi.project(ops.toString()))
    }

    private fun sample() = project { op ->
        val acct = "account:$account"
        op(1, "member.create", acct, kai, """{"name":"Kai"}""", null, t0, account)
        op(2, "member.create", acct, june, """{"name":"June","display_name":"Junebug"}""", null, t0, account)
        op(3, "space.create", "space:$space", space, """{"name":"Home","kind":"internal"}""", null, t0, account)
        op(4, "channel.create", "space:$space", channel, """{"space_id":"$space","name":"vent"}""", null, t0, account)
        val msg = "0192f8c2-0000-7000-8000-0000000000e1"
        op(5, "message.send", "space:$space", msg, """{"channel_id":"$channel","authors":["$kai"],"text":"hi","entities":[]}""", kai, t0, account)
        op(6, "message.delete", "space:$space", msg, "{}", june, t0 + day, account)
        val post = "0192f8c2-0000-7000-8000-0000000000e2"
        op(7, "post.create", acct, post, """{"kind":"entry","title":"Rough night","text":"long day","entities":[],"authors":["$kai"]}""", kai, t0, account)
        op(8, "post.delete", acct, post, "{}", null, t0 + 2 * day, account)
        op(9, "member.delete", acct, kai, "{}", june, t0 + 10 * day, account)
    }

    @Test fun listsDeletedItemsWithWhoDeletedThem() {
        val items = Trash.items(sample(), account)
        assertEquals(listOf(TrashKind.Member, TrashKind.Post, TrashKind.Message), items.map { it.kind })
        val (member, post, message) = items
        assertEquals("Member Kai", member.title)
        assertEquals("Junebug", member.deletedBy)
        assertEquals("account:$account", member.scope)
        assertEquals("Entry “Rough night”", post.title)
        assertNull(post.deletedBy)
        assertEquals("Message in #vent", message.title)
        assertEquals("space:$space", message.scope)
        assertEquals(channel, message.channelId)
        val now = t0 + 12 * day
        assertEquals("Deleted by Junebug · 2 days ago · history kept", Trash.detail(member, now))
        assertEquals("Deleted 10 days ago", Trash.detail(post, now))
        assertEquals(listOf(true, false, false), items.map { Trash.thisWeek(it, now) })
    }

    @Test fun anotherAccountsDeleteOfAnotherAccountsThingStaysOut() {
        val p = project { op ->
            val g = "0192f8c2-0000-7000-8000-0000000000f1"
            op(1, "group.create", "account:$other", g, """{"name":"Stars"}""", null, t0, other)
            op(2, "group.delete", "account:$other", g, "{}", null, t0 + 1, other)
        }
        assertEquals(emptyList<TrashItem>(), Trash.items(p, account))
        assertEquals(listOf("Group Stars"), Trash.items(p, other).map { it.title })
    }

    @Test fun restoreClearsTheRow() {
        val p = project { op ->
            op(1, "member.create", "account:$account", kai, """{"name":"Kai"}""", null, t0, account)
            op(2, "member.delete", "account:$account", kai, "{}", kai, t0 + 1, account)
            op(3, "member.restore", "account:$account", kai, "{}", null, t0 + 2, account)
        }
        assertEquals(emptyList<TrashItem>(), Trash.items(p, account))
    }

    @Test fun agesReadAsTheFrameDoes() {
        assertEquals("2 days ago", Trash.ago(t0, t0 + 2 * day))
        assertEquals("1 day ago", Trash.ago(t0, t0 + day))
        assertEquals("12 days ago", Trash.ago(t0, t0 + 12 * day))
        assertEquals("3 weeks ago", Trash.ago(t0, t0 + 21 * day))
        assertEquals("just now", Trash.ago(t0, t0 + 5_000))
    }

    @Test fun onlyTrashDeletesNameTheActingMember() {
        val model = Model.parse(sample(), account)
        assertNull(Trash.actingMember("message.send", model))
        assertNull(Trash.actingMember("feed.delete", model))
        assertEquals(june, Trash.actingMember("message.delete", model))
        assertEquals(listOf(1, 2, 3, 3, 1), TrashKind.entries.map { Trash.tab(it) })
    }
}
