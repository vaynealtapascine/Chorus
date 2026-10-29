package garden.vayne.chorus.data

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class MemberCustomizationTest {
    private val original = Member("m", "Kai", "K", "they", "#123456", listOf("k", "second"), "Hi", false,
        avatarBlob = "a".repeat(64), bannerBlob = "b".repeat(64), birthday = "--02-29")
    private fun patch(bio: String = "Hi", avatar: String? = original.avatarBlob, banner: String? = original.bannerBlob,
        birthday: String = original.birthday.orEmpty()) = MemberCustomization.patch(original, "Kai", "K", "they", "k", "#123456",
        bio, "Hi", avatar, banner, birthday) { text, _ -> JSONObject().put("text", text).put("entities", JSONArray()).toString() }
    @Test fun untouchedConcurrentValuesAndSigilsAreOmitted() {
        assertEquals(0, patch().length())
        val removal = patch(avatar = null)
        assertEquals(setOf("avatar_blob"), removal.keys().asSequence().toSet())
        assertTrue(removal.isNull("avatar_blob"))
        val bio = patch(bio = "Edited")
        assertEquals(setOf("description", "description_entities"), bio.keys().asSequence().toSet())
        assertFalse(bio.has("banner_blob")); assertFalse(bio.has("sigils")); assertFalse(bio.has("sort_key"))
    }
    @Test fun fieldsPersistAndDeterministicDefinitionsNeverClobberExistingPrivacy() {
        val p = JSONObject("""{"rows":{"field_def":{"f":{"exists":true,"fields":{"name":"Location","type":"text","default_visibility":{"mode":"buckets","bucket_ids":["close"]}}}},"field_value":{"m|f":{"exists":true,"fields":{"value":"Garden"}}}}}""")
        val details = ProfileDetails.parse(p)
        val location = details.field("mine", "Location", "text")
        assertEquals("f", location.id); assertEquals("buckets", location.visibility)
        assertEquals("Garden", details.value("m", location))
        assertEquals(ProfileDetails().field("mine", "Website", "url"), ProfileDetails().field("mine", "Website", "url"))
        assertNotEquals(ProfileDetails().field("mine", "Website", "url").id, ProfileDetails().field("other", "Website", "url").id)
    }
    @Test fun websitesAndBirthdayValidateBeforeAnySave() {
        assertTrue(MemberCustomization.validWebsite("https://example.org/about?q=1"))
        for (bad in listOf("javascript:alert(1)", "https://user:pass@example.org", "https://", "https://example.org/has space"))
            assertFalse(bad, MemberCustomization.validWebsite(bad))
        MemberCustomization.validateDetails(listOf("Garden", "https://example.org", "", ""))
        assertTrue(runCatching { MemberCustomization.validateDetails(listOf("Garden", "file:///secret", "", "")) }.isFailure)
        assertTrue(runCatching { patch(birthday = "--02-30") }.isFailure)
        assertTrue(runCatching { patch(birthday = "--02-29") }.isSuccess)
    }
    @Test fun untouchedLegacyFieldsDoNotBlockOrBecomeWrites() {
        val baseline = listOf(" Garden ", "old-domain-without-scheme", "", "")
        assertEquals(emptyList<Int>(), MemberCustomization.validateDetails(baseline, baseline))
        assertEquals(listOf(0), MemberCustomization.validateDetails(baseline.toMutableList().apply { set(0, "New garden") }, baseline))
        assertEquals(listOf(1), MemberCustomization.validateDetails(baseline.toMutableList().apply { set(1, "https://example.org") }, baseline))
        val old = original.copy(name = " Kai ", birthday = "legacy birthday", color = "legacy")
        val patch = MemberCustomization.patch(old, old.name, "K", "they", "k", old.color,
            "Hi", "Hi", old.avatarBlob, old.bannerBlob, old.birthday.orEmpty())
        assertEquals(0, patch.length())
    }
    @Test fun cropRespectsOrientedDimensionsZoomAndEdges() {
        assertEquals(ProfileCrop.Rect(0, 600, 600, 200), ProfileCrop.rect(600, 800, true, 1f, .5f, 1f))
        assertEquals(ProfileCrop.Rect(200, 0, 600, 600), ProfileCrop.rect(800, 600, false, 1f, 1f, 0f))
        assertEquals(ProfileCrop.Rect(300, 200, 600, 200), ProfileCrop.rect(1200, 600, true, 2f, .5f, .5f))
    }
    @Test fun editingBioKeepsExistingMentionAndCustomEmojiIdentity() {
        val old = original.copy(description = "@Kai :wave:", descriptionEntities = """[
            {"type":"mention","offset":0,"length":4,"target_type":"member","target_id":"original-kai"},
            {"type":"custom_emoji","offset":5,"length":6,"emoji_id":"original-wave"}]""")
        val markup = MemberCustomization.markup(old)
        val patch = MemberCustomization.patch(old, "Kai", "K", "they", "k", "#123456", "$markup!", markup,
            old.avatarBlob, old.bannerBlob, old.birthday.orEmpty(),
            names = ChatEdit.names(old.description.orEmpty(), old.descriptionEntities, Model.Empty))
        val entities = patch.getJSONArray("description_entities")
        assertEquals("original-kai", entities.getJSONObject(0).getString("target_id"))
        assertEquals("original-wave", entities.getJSONObject(1).getString("emoji_id"))
    }
}
