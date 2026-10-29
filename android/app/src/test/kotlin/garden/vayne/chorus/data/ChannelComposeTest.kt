package garden.vayne.chorus.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChannelComposeTest {
    @Test fun colourOnlySavePreservesConcurrentMetadataAndUntouchedColourIsNotWritten() {
        val baseline = ChatChannel("c", "s", "text", "Garden", null, topic = "Quiet", color = "#42A397")
        val payload = ChannelCompose.update(baseline, "Garden", "Quiet", null, "#6D9ED2")
        assertEquals(setOf("color"), payload.keys().asSequence().toSet())
        val live = baseline.copy(name = "Evening", topic = "Remote edit", avatarBlob = "a".repeat(64))
        val saved = live.copy(color = payload.getString("color"))
        assertEquals(live.copy(color = "#6D9ED2"), saved)
        assertFalse(ChannelCompose.update(baseline, "Renamed", "Quiet", null, baseline.color!!).has("color"))
        assertFalse(ChannelCompose.update(baseline.copy(color = null), "Renamed", "Quiet", null, "#42A397").has("color"))
        assertEquals("#6D9ED2", ChannelCompose.create("s", "Garden", "", null, "#6D9ED2").getString("color"))
    }
    @Test fun imageOnlySavePreservesConcurrentMetadataEdits() {
        val baseline = ChatChannel("c", "s", "text", "Garden", null, topic = "Quiet")
        val live = baseline.copy(name = "Evening garden", topic = "Changed on another device")
        val payload = ChannelCompose.update(baseline, baseline.name, baseline.topic.orEmpty(), "b".repeat(64))
        assertEquals(setOf("avatar_blob"), payload.keys().asSequence().toSet())
        // Apply exactly the emitted fields to the model that arrived while the sheet was open.
        val saved = live.copy(avatarBlob = payload.getString("avatar_blob"))
        assertEquals("Evening garden", saved.name)
        assertEquals("Changed on another device", saved.topic)
        assertEquals("b".repeat(64), saved.avatarBlob)
    }
    @Test fun imageLessCreateWorksOnOlderServersAndImageFeatureNeedsCapability() {
        assertFalse(ChannelCompose.create("s", "Garden", "", null).has("avatar_blob"))
        assertFalse(ChannelCompose.supportsImages(JSONObject("""{"version":"0.1.0"}""")))
        assertTrue(ChannelCompose.supportsImages(JSONObject("""{"capabilities":["channel_images"]}""")))
    }
    @Test fun schemaMemberDmUsesTheDirectFolderAndPeerFallback() {
        val p = JSONObject("""{"rows":{"space":{"s":{"exists":true,"fields":{"kind":"internal"}}},"channel":{"c":{"exists":true,"fields":{"space_id":"s","kind":"member_dm","name":"Kai and Moss","member_ids":["kai","moss"]}}}}}""")
        val model = Model.parse(p, "acct")
        val channel = model.channels.single()
        assertTrue(channel.isMemberDm)
        assertEquals(listOf("kai", "moss"), channel.memberIds)
        assertEquals(ChatPresentation.Folder.Direct, ChatPresentation.folder(channel, model.spaces.single()))
    }
    @Test fun imageRemovalIsExplicitAndDoesNotRewriteOtherFields() {
        val channel = ChatChannel("c", "s", "text", "Garden", null, topic = "Quiet", avatarBlob = "a".repeat(64))
        val changed = ChannelCompose.update(channel, "Garden", "Quiet", null)
        assertEquals(setOf("avatar_blob"), changed.keys().asSequence().toSet())
        assertTrue(changed.isNull("avatar_blob"))
        assertEquals(0, ChannelCompose.update(channel, "Garden", "Quiet", channel.avatarBlob).length())
    }
    @Test fun replicaKeepsImageAndFallbackAcrossOfflineRoundTripAndDelta() {
        val p = JSONObject("""{"rows":{"space":{"s":{"exists":true,"fields":{"kind":"internal"}}},
          "channel":{"c":{"exists":true,"fields":{"space_id":"s","name":"Garden","avatar_blob":"${"a".repeat(64)}","icon":"🌿","color":"#42A397"}}}}}""")
        val first = Model.parse(JSONObject(p.toString()), "acct").channels.single()
        assertEquals("a".repeat(64), first.avatarBlob)
        assertEquals("🌿", ChannelCompose.glyph(first))
        val delta = JSONObject("""{"rows":{"channel":{"c":{"exists":true,"fields":{"space_id":"s","name":"Garden","avatar_blob":null,"icon":"🌿","color":"#42A397"}}}}}""")
        Model.applyDelta(p, delta)
        val removed = Model.parse(p, "acct").channels.single()
        assertNull(removed.avatarBlob)
        assertEquals("🌿", ChannelCompose.glyph(removed))
        assertEquals("🌌", ChannelCompose.glyph(removed.copy(icon = null, name = "🌌 stars")))
    }
    @Test fun invalidNameOrImageNeverBuildsAnOperation() {
        for ((name, avatar) in listOf(" " to null, "a".repeat(81) to null, "Garden" to "https://example.org/image")) {
            assertTrue(runCatching { ChannelCompose.create("s", name, "", avatar) }.isFailure)
        }
        assertEquals("Garden", ChannelCompose.create("s", " Garden ", "", null).getString("name"))
    }
}
