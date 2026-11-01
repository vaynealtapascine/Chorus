package garden.vayne.chorus.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PushTest {
    @Test
    fun fcmIsPreferredByDefaultEvenWhenNtfyIsInstalled() {
        val got = Push.selectTarget(
            fcmToken = "fcm-token-123",
            distributors = listOf("io.heckel.ntfy"),
            currentDistributor = "io.heckel.ntfy",
            choice = null,
        )
        assertEquals(Push.Target.Fcm("fcm-token-123"), got)
    }

    @Test
    fun explicitDistributorChoiceOverridesFcmWhenInstalled() {
        val got = Push.selectTarget(
            fcmToken = "fcm-token-123",
            distributors = listOf("io.heckel.ntfy"),
            currentDistributor = Fcm.DISTRIBUTOR,
            choice = "distributor:io.heckel.ntfy",
        )
        assertEquals(Push.Target.Distributor("io.heckel.ntfy"), got)

        // if that distributor was uninstalled, fall back to FCM rather than breaking push
        val uninstalled = Push.selectTarget(
            fcmToken = "fcm-token-123",
            distributors = emptyList(),
            currentDistributor = "io.heckel.ntfy",
            choice = "distributor:io.heckel.ntfy",
        )
        assertEquals(Push.Target.Fcm("fcm-token-123"), uninstalled)
    }

    @Test
    fun fallsBackToUnifiedPushOrPollingWhenFcmIsUnavailable() {
        val ntfy = Push.selectTarget(
            fcmToken = null,
            distributors = listOf("org.example.push", "io.heckel.ntfy"),
            currentDistributor = null,
            choice = null,
        )
        assertEquals(Push.Target.Distributor("io.heckel.ntfy"), ntfy)

        val none = Push.selectTarget(
            fcmToken = null,
            distributors = emptyList(),
            currentDistributor = "io.heckel.ntfy",
            choice = null,
        )
        assertEquals(Push.Target.None, none)
    }

    @Test
    fun stateRoundTripsThroughJson() {
        val keys = WebPushCrypto.generate()
        val s = Push.State("tok-1", Fcm.DISTRIBUTOR, "fcm:abc123", keys)
        val parsed = Push.State.parse(s.json())
        assertEquals("tok-1", parsed.token)
        assertEquals(Fcm.DISTRIBUTOR, parsed.distributor)
        assertEquals("fcm:abc123", parsed.endpoint)
        assertArrayEquals(keys.privatePkcs8, parsed.keys.privatePkcs8)
        assertArrayEquals(keys.publicRaw, parsed.keys.publicRaw)
        assertArrayEquals(keys.auth, parsed.keys.auth)

        val empty = Push.State.parse(s.copy(endpoint = null).json())
        assertNull(empty.endpoint)
    }

    @Test
    fun notificationIconsResolveServerPayloadAndLocalModelFallbacks() {
        val chanAvatar = "c".repeat(64)
        val payload = org.json.JSONObject()
            .put("t", "message")
            .put("kind", "mention")
            .put("title", "Kai \u00b7 Book club")
            .put("text", "hi!")
            .put("channel_id", "ch-1")
            .put("scope", "space:sp-1")
            .put(
                "chat",
                org.json.JSONObject()
                    .put("title", "Book club")
                    .put("glyph", "B")
                    .put("color", "#B99CE8")
                    .put("avatar_blob", chanAvatar)
                    .put("direct", false),
            )
            .put(
                "sender",
                org.json.JSONObject()
                    .put("name", "Kai")
                    .put("glyph", "\u2726")
                    .put("color", "#EEAA62"),
            )

        val spec = NotificationIcons.resolveSpec(payload, model = null)
        assertEquals("ch-1", spec.channelId)
        assertEquals("sp-1", spec.spaceId)
        assertEquals("Book club", spec.chatTitle)
        assertEquals("Kai", spec.senderName)
        assertEquals("B", spec.glyph)
        assertEquals("#B99CE8", spec.color)
        assertEquals(chanAvatar, spec.avatarBlob)
        assertEquals(false, spec.direct)
        assertEquals("\u2726", spec.senderGlyph)

        //NotifyCheck.asPush preserves chat and sender visual metadata from GET /notifications
        val listed = org.json.JSONObject()
            .put("id", "n-1")
            .put("kind", "mention")
            .put("title", "Kai \u00b7 Book club")
            .put("text", "hi!")
            .put("channel_id", "ch-1")
            .put("message_id", "m-1")
            .put("scope", "space:sp-1")
            .put("chat", payload.getJSONObject("chat"))
            .put("sender", payload.getJSONObject("sender"))
        val converted = NotifyCheck.asPush(listed)!!
        val fromList = NotificationIcons.resolveSpec(converted, model = null)
        assertEquals("Book club", fromList.chatTitle)
        assertEquals(chanAvatar, fromList.avatarBlob)
        assertEquals("Kai", fromList.senderName)

        // Local Model fallback when payload lacks `chat` (e.g. older server or debug broadcast)
        val kai = Member("m-kai", "Kai", null, null, "#EEAA62", listOf("\u2726"), null, false, avatarBlob = "a".repeat(64))
        val june = Member("m-june", "June", null, null, "#8FB8E8", emptyList(), null, false)
        val model = Model(
            members = listOf(kai, june),
            groups = emptyList(),
            membership = emptyMap(),
            current = emptyList(),
            since = null,
            switches = emptyList(),
            spaces = listOf(ChatSpace("sp-home", "internal", "Home")),
            channels = listOf(
                ChatChannel("ch-gen", "sp-home", "text", "general", null),
                ChatChannel("ch-dm", "sp-home", "member_dm", "dm", null, memberIds = listOf("m-kai", "m-june")),
            ),
        )
        val internalGeneral = NotificationIcons.resolveSpec(
            org.json.JSONObject().put("t", "message").put("title", "Kai \u00b7 #general").put("channel_id", "ch-gen"),
            model,
        )
        assertEquals("#general", internalGeneral.chatTitle)
        assertEquals("#", internalGeneral.glyph)
        assertNull(internalGeneral.color) // uncoloured internal channel uses theme accentSoft/accent

        val memberDm = NotificationIcons.resolveSpec(
            org.json.JSONObject().put("t", "message").put("kind", "member_dm").put("title", "Kai").put("channel_id", "ch-dm").put("reply_as", "m-june"),
            model,
        )
        assertEquals(true, memberDm.direct)
        assertEquals("\u2726", memberDm.glyph)
        assertEquals("#EEAA62", memberDm.color)
        assertEquals("a".repeat(64), memberDm.avatarBlob)

        val remoteInternalGeneral = NotificationIcons.resolveSpec(
            org.json.JSONObject()
                .put("t", "message")
                .put("kind", "channel")
                .put("scope", "space:sp-home")
                .put("channel_id", "ch-gen")
                .put("title", "June \u00b7 #general")
                .put("chat", org.json.JSONObject().put("title", "#general").put("glyph", "#").put("direct", false)),
            model = null,
        )
        assertEquals("#general", remoteInternalGeneral.chatTitle)
        assertEquals("#", remoteInternalGeneral.glyph)
        assertNull(remoteInternalGeneral.color)
    }
}
