package garden.vayne.chorus.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelPermissionsTest {
    private val channel = ChatChannel("c", "s", "text", "plans", null)
    private fun state(rows: String) = ChannelPermissions.snapshot(JSONObject("""{"rows":$rows}"""))

    @Test fun onlyManagersSeeTheControlsAndTheOwnerComesFromTheDirectory() {
        val s = state("""{"space":{"s":{"exists":true,"fields":{"kind":"shared"}}},
            "space_member":{"s|a":{"exists":true,"fields":{"role":"admin"}},"s|m":{"exists":true,"fields":{"role":"member"}}}}""")
        assertTrue(ChannelPermissions.canManage(s, channel, "a"))
        assertFalse(ChannelPermissions.canManage(s, channel, "m"))
        assertTrue(ChannelPermissions.canManage(s, channel, "o", owner = "o"))
        assertFalse(ChannelPermissions.canManage(s, channel.copy(kind = "thread"), "a"))
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
}
