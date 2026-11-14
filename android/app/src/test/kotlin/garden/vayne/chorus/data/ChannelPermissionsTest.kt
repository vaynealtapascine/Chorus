package garden.vayne.chorus.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelPermissionsTest {
    private val channel = ChatChannel("c", "s", "text", "plans", null)
    private fun state(rows: String, sets: String = "{}") = ChannelPermissions.snapshot(JSONObject("""{"rows":$rows,"sets":$sets}"""))
    // as the core projects them: joins are a set, `space.set_role` a row that never "exists"
    private val joins = """{"space_member":{"s|{\"account_id\":\"a\"}":true,"s|{\"account_id\":\"m\"}":true,"s|{\"account_id\":\"x\"}":false}}"""

    @Test fun onlyManagersSeeTheControlsAndTheOwnerComesFromTheDirectory() {
        val s = state("""{"space":{"s":{"exists":true,"fields":{"kind":"shared"}}},
            "space_member":{"s|a":{"exists":false,"fields":{"role":"admin"}},"s|x":{"exists":false,"fields":{"role":"admin"}}}}""", joins)
        assertTrue(ChannelPermissions.canManage(s, channel, "a"))
        assertFalse(ChannelPermissions.canManage(s, channel, "m"))
        assertTrue(ChannelPermissions.canManage(s, channel, "o", owner = "o"))
        assertFalse(ChannelPermissions.canManage(s, channel.copy(kind = "thread"), "a"))
        // someone who left keeps a role row but not the role
        assertFalse(ChannelPermissions.canManage(s, channel, "x"))
    }

    @Test fun membersSeeChatsAndTheCreatorOwnsTheSpace() {
        val s = state("""{"space":{"s":{"exists":true,"fields":{"kind":"shared","created_by_account_id":"o"}}},
            "channel_permission":{"c|role|everyone":{"exists":true,"fields":{"allow":[],"deny":["view"]}}}}""", joins)
        assertFalse(ChannelPermissions.canView(s, channel, "m"))
        assertTrue(ChannelPermissions.canView(s, channel, "o"))
        assertTrue(ChannelPermissions.canView(s, channel.copy(id = "open"), "m"))
        assertFalse(ChannelPermissions.canView(s, channel.copy(id = "open"), "stranger"))
        assertFalse(ChannelPermissions.canView(s, channel.copy(id = "open"), "x"))
    }

    @Test fun savingRebasesOnlyTheEditedPermissions() {
        val target = ChannelPermissions.Target("role", "member", "Members")
        val latest = JSONObject("""{"allow":["view","x_future"],"deny":["pin"]}""")
        val baseline = ChannelPermissions.choices(JSONObject("""{"allow":["view"]}"""))
        assertNull(ChannelPermissions.patch(latest, target, baseline, baseline))
        val patch = ChannelPermissions.patch(latest, target, baseline, baseline + ("send" to ChannelPermissions.Choice.Deny))!!
        assertEquals("""["view","x_future"]""", patch.getJSONArray("allow").toString())
        assertEquals("""["pin","send"]""", patch.getJSONArray("deny").toString())
        assertEquals(30, ChannelPermissions.seconds("30"))
        assertNull(ChannelPermissions.seconds("99999"))
    }

    @Test fun theSpacesOwnOverridesKnowTheNewNamesAndKeepWhatTheFormDoesNotDraw() {
        val target = ChannelPermissions.Target("account", "m", "Mo")
        val latest = JSONObject("""{"allow":["invite","x_future"],"deny":["poll"]}""")
        val keys = ChannelPermissions.spaceLabels.keys
        assertTrue(keys.containsAll(listOf("attach", "poll", "aside", "invite", "history")))
        assertFalse("invite" in ChannelPermissions.labels.keys)
        val baseline = ChannelPermissions.choices(latest, keys)
        assertEquals(ChannelPermissions.Choice.Allow, baseline["invite"])
        assertEquals(ChannelPermissions.Choice.Deny, baseline["poll"])
        val patch = ChannelPermissions.patch(latest, target,
            baseline, baseline + ("poll" to ChannelPermissions.Choice.Allow) + ("history" to ChannelPermissions.Choice.Deny))!!
        assertEquals("""["invite","x_future","poll"]""", patch.getJSONArray("allow").toString())
        assertEquals("""["history"]""", patch.getJSONArray("deny").toString())
    }

    @Test fun theSpaceEditorListsPeopleAndExistingTargetsOfThatSpaceOnly() {
        val s = state("""{"space":{"s":{"exists":true,"fields":{"kind":"shared","roles":[{"id":"mod","name":"Mods"}]}}},
            "space_permission":{"s|account|z":{"exists":true,"fields":{"allow":[],"deny":["pin"]}},"t|account|y":{"exists":true,"fields":{"allow":[],"deny":[]}}}}""")
        val ids = ChannelPermissions.targets(s, "s", "s", "space_permission", listOf(SpaceAccount("m", null, "Mo"))).map { "${it.type}|${it.id}" }
        assertEquals(listOf("role|everyone", "role|member", "role|read_only", "role|mod", "account|m", "account|z"), ids)
    }

    @Test fun managingTheWholeSpaceIsTheOwnerAnAdminOrADmParticipant() {
        val s = state("""{"space":{"s":{"exists":true,"fields":{"kind":"shared"}}},
            "space_member":{"s|a":{"exists":false,"fields":{"role":"admin"}}}}""", joins)
        assertTrue(ChannelPermissions.manages(s, "s", "a"))
        assertFalse(ChannelPermissions.manages(s, "s", "m"))
        assertTrue(ChannelPermissions.manages(s, "s", "o", owner = "o"))
        assertFalse(ChannelPermissions.manages(s, "s", ""))
    }
}
