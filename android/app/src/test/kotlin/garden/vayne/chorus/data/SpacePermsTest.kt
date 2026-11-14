package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rule is core's (`spacePerms`); these run it over a projection built from ops, never hand-written rows. */
class SpacePermsTest {
    private fun id(n: Int) = "0192f8c2-0000-7000-8000-${n.toString().padStart(12, '0')}"
    private val owner = id(1)
    private val me = id(2)
    private val space = id(100)
    private val chan = id(200)
    private val thread = id(201)
    private val chatSpace = ChatSpace(space, "shared", "Lanterns")
    private val general = ChatChannel(chan, space, "text", "general", null)
    private val info = SpaceInfo(space, "shared", owner, listOf(SpaceAccount(owner, null, "Owner"), SpaceAccount(me, null, "Me")))

    private class World(val state: JSONObject, val roles: Map<String, String>)

    /** A shared space owned by [owner] with [me] in it, then whatever [more] writes. */
    private fun build(more: ((String, String, JSONObject, String) -> Unit) -> Unit = {}): World {
        val ops = JSONArray()
        val op = { kind: String, entity: String, payload: JSONObject, by: String ->
            val n = ops.length() + 1
            ops.put(JSONObject().put("id", id(1000 + n)).put("scope", "space:$space").put("kind", kind).put("entity_id", entity).put("v", 1)
                .put("hlc", "${(0x1a0c4506c00L + n).toString(16).padStart(12, '0')}-0000-00000001").put("device_at", 1_790_000_000_000L + n)
                .put("account_id", by).put("payload", payload).put("member_id", JSONObject.NULL))
            Unit
        }
        op("space.create", space, JSONObject().put("kind", "shared").put("name", "Lanterns"), owner)
        op("space.join", space, JSONObject().put("account_id", owner), owner)
        op("space.join", space, JSONObject().put("account_id", me), owner)
        op("channel.create", chan, JSONObject().put("space_id", space).put("kind", "text").put("name", "general"), owner)
        more(op)
        val p = JSONObject(uniffi.chorus_ffi.project(ops.toString()))
        val members = p.getJSONObject("rows").optJSONObject("space_member") ?: JSONObject()
        val roles = members.keys().asSequence().mapNotNull { k ->
            members.getJSONObject(k).optJSONObject("fields")?.optString("role")?.takeIf { it.isNotEmpty() }?.let { k to it }
        }.toMap()
        return World(ChannelPermissions.snapshot(p), roles)
    }
    private fun lists(allow: List<String>, deny: List<String>) = JSONObject().put("allow", JSONArray(allow)).put("deny", JSONArray(deny))
    private fun everyone(allow: List<String>, deny: List<String>) = lists(allow, deny).put("target_type", "role").put("target_id", "everyone")
    private fun here(w: World, by: String, channel: ChatChannel? = general, parent: ChatChannel? = null) =
        SpacePerms.here(w.state, w.roles, chatSpace, info, channel, parent, by)

    @Test fun aMemberKeepsWhatItCouldDoAndInviteIsOff() {
        val perms = here(build(), me)!!
        assertTrue(perms.containsAll(listOf("send", "attach", "poll", "aside", "history")))
        assertFalse("invite" in perms)
        assertEquals(SpacePerms.Gates(attach = true, poll = true, aside = true, invite = false), SpacePerms.gates(perms))
    }

    @Test fun theOwnerDoesEverythingWhateverTheOverridesSay() {
        val w = build { op -> op("space.set_permission", space, everyone(emptyList(), listOf("attach", "send")), owner) }
        assertEquals(SpacePerms.Gates(true, true, true, true), SpacePerms.gates(here(w, owner)))
    }

    @Test fun whatEveryoneIsDeniedIsHiddenAndAChannelOverrideBeatsTheSpace() {
        val w = build { op ->
            op("space.set_permission", space, everyone(listOf("invite"), listOf("attach", "poll")), owner)
            op("channel.set_permission", chan, everyone(listOf("poll"), emptyList()), owner)
        }
        assertEquals(SpacePerms.Gates(attach = false, poll = true, aside = true, invite = true), SpacePerms.gates(here(w, me)))
        // asked about the whole space, the channel's allow doesn't count
        val whole = SpacePerms.gates(here(w, me, channel = null))
        assertFalse(whole.attach || whole.poll)
    }

    @Test fun anAccountOverrideBeatsTheRoleAndEveryone() {
        val w = build { op ->
            op("space.set_permission", space, everyone(emptyList(), listOf("aside")), owner)
            op("space.set_permission", space, lists(listOf("aside"), emptyList()).put("target_type", "account").put("target_id", me), owner)
        }
        assertTrue(SpacePerms.gates(here(w, me)).aside)
    }

    @Test fun aReadOnlyMemberCannotSendSoNothingIsOffered() {
        val w = build { op -> op("space.set_role", space, JSONObject().put("account_id", me).put("role", "read_only"), owner) }
        assertEquals(SpacePerms.Gates(false, false, false, false), SpacePerms.gates(here(w, me)))
    }

    @Test fun aThreadAsksAboutItsParentsChannelAndOnlySharedSpacesAndGuestChannelsAreGated() {
        val w = build { op -> op("channel.set_permission", chan, everyone(emptyList(), listOf("attach")), owner) }
        val inThread = ChatChannel(thread, space, "thread", "thread", chan)
        assertFalse(SpacePerms.gates(here(w, me, channel = inThread, parent = general)).attach)
        assertNull(SpacePerms.here(w.state, w.roles, chatSpace.copy(kind = "internal"), info.copy(kind = "internal"), general, null, me))
        // before the directory has loaded nothing is gated
        assertNull(SpacePerms.here(w.state, w.roles, chatSpace, null, general, null, me))
        val guest = info.copy(kind = "internal", guest = true, accounts = emptyList())
        assertNotNull(SpacePerms.here(w.state, w.roles, chatSpace.copy(kind = "internal"), guest, general, null, me))
        assertEquals(SpacePerms.OPEN, SpacePerms.gates(null))
    }

    @Test fun historyIsOffOnlyWhenDeniedAndTheMarkerSitsAtTheOldestMessageWeHold() {
        val w = build { op -> op("space.set_permission", space, everyone(emptyList(), listOf("history")), owner) }
        val perms = here(w, me)
        assertTrue(SpacePerms.historyOff(perms))
        assertFalse(SpacePerms.historyOff(here(build(), me)))
        assertFalse(SpacePerms.historyOff(null))
        fun message(n: Int, at: Long) = ChatMessage(id(n), chan, emptyList(), "hi", at, null, "all", emptySet(), null, null, emptyList())
        fun doc(n: Int, at: Long, channel: String = chan) = SearchDocument(id(n), "message", at, "hi", channelId = channel)
        val shown = listOf(message(1, 10), message(2, 20))
        assertTrue(SpacePerms.historyStarts(perms, chan, shown, listOf(doc(1, 10), doc(2, 20), doc(3, 5, id(999)))))
        // an older one we still hold: the chat window just doesn't reach it
        assertFalse(SpacePerms.historyStarts(perms, chan, shown, listOf(doc(0, 5), doc(1, 10))))
        assertFalse(SpacePerms.historyStarts(here(build(), me), chan, shown, emptyList()))
        assertFalse(SpacePerms.historyStarts(perms, chan, emptyList(), emptyList()))
    }

    @Test fun theSwitchesShowWhatAPlainMemberGetsAndWriteOnlyWhatChanged() {
        val w = build { op -> op("space.set_permission", space, everyone(listOf("invite"), listOf("pin", "react")), owner) }
        val before = SpacePerms.memberSwitches(w.state, space)
        assertEquals(mapOf("send" to true, "attach" to true, "poll" to true, "pin" to false, "invite" to true, "aside" to true, "history" to true), before)
        assertNull(SpacePerms.everyonePayload(w.state, space, before, before))
        val out = SpacePerms.everyonePayload(w.state, space, before, before + ("pin" to true) + ("history" to false))!!
        assertEquals("""["invite","pin"]""", out.getJSONArray("allow").toString())
        // `react` is not drawn by the form: it stays denied
        assertEquals("""["react","history"]""", out.getJSONArray("deny").toString())
        assertEquals("everyone", out.getString("target_id"))
        assertEquals("role", out.getString("target_type"))
    }

    @Test fun theFirstOverrideStartsFromNothingAndANewSpaceDeniesInviteAndHistory() {
        val w = build()
        val before = SpacePerms.memberSwitches(w.state, space)
        val first = SpacePerms.everyonePayload(w.state, space, before, before + ("attach" to false))!!
        assertEquals("[]", first.getJSONArray("allow").toString())
        assertEquals("""["attach"]""", first.getJSONArray("deny").toString())
        val fresh = build { op -> op("space.set_permission", space, SpacePerms.newSpacePayload(), owner) }
        assertEquals(mapOf("invite" to false, "history" to false, "send" to true),
            SpacePerms.memberSwitches(fresh.state, space).filterKeys { it in setOf("invite", "history", "send") })
    }

    @Test fun exceptionsCountOnlyPerPersonAndPerRoleOverridesWithSomethingInThem() {
        val w = build { op ->
            op("space.set_permission", space, everyone(emptyList(), listOf("attach")), owner)
            op("space.set_permission", space, lists(listOf("invite"), emptyList()).put("target_type", "account").put("target_id", me), owner)
            op("space.set_permission", space, lists(emptyList(), emptyList()).put("target_type", "role").put("target_id", "member"), owner)
        }
        assertEquals(1, SpacePerms.exceptions(w.state, space))
        assertEquals(0, SpacePerms.exceptions(build().state, space))
    }

    @Test fun ourSharingDefaultsToNamesOnAndFrontingOffAtFifteenMinutesAndReadsOnlyWhatEachAccountWrote() {
        val w = build { op ->
            op("space.set_sharing", space, JSONObject().put("member_names", false), me)
            op("space.set_sharing", space, JSONObject().put("fronts", true).put("fronts_delay_min", 30), owner)
        }
        assertEquals(Sharing(memberNames = false, fronts = false, frontsDelayMin = 15), Sharing.of(w.state, space, me))
        assertEquals(Sharing(memberNames = true, fronts = true, frontsDelayMin = 30), Sharing.of(w.state, space, owner))
        assertEquals(Sharing(), Sharing.of(w.state, space, id(9)))
    }

    @Test fun sharingSendsOnlyTheChangedFields() {
        assertNull(Sharing.changes(Sharing(), Sharing()))
        assertEquals("""{"fronts":true}""", Sharing.changes(Sharing(), Sharing(fronts = true))!!.toString())
        val all = Sharing.changes(Sharing(), Sharing(false, true, 60))!!
        assertEquals(false, all.getBoolean("member_names"))
        assertEquals(true, all.getBoolean("fronts"))
        assertEquals(60, all.getInt("fronts_delay_min"))
    }

    @Test fun theDelayChoicesKeepAValueAnotherClientSet() {
        assertEquals(listOf("Now", "15 min", "1 hour", "6 hours", "1 day"), Sharing.delayOptions(15).map(Sharing::delayLabel))
        assertEquals(listOf("Now", "15 min", "30 min", "1 hour", "6 hours", "1 day"), Sharing.delayOptions(30).map(Sharing::delayLabel))
        assertEquals("1 h 30 min", Sharing.delayLabel(90))
    }

    @Test fun whoMayOpenWhichPartOfTheForm() {
        assertEquals(listOf(true, true, false), listOf("owner", "admin", "member").map { SpacePerms.canSetPermissions("shared", it) })
        assertFalse(SpacePerms.canSetPermissions("dm", "owner"))
        assertEquals(listOf(true, false, false), listOf(SpacePerms.canShare("shared", "member"), SpacePerms.canShare("shared", null), SpacePerms.canShare("internal", "owner")))
    }
}
