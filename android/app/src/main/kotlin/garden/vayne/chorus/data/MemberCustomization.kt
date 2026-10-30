package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale
import java.util.UUID
import uniffi.chorus_ffi.toMarkup

data class ProfileDetail(val id: String, val name: String, val type: String, val visibility: String)

/** Location and up to three websites are ordinary typed fields, never client-only metadata. */
data class ProfileDetails(val definitions: List<ProfileDetail> = emptyList(),
    val values: Map<String, Map<String, String>> = emptyMap(),
    val visibility: Map<String, String> = emptyMap(),
    val unavailableIds: Set<String> = emptySet()) {
    fun field(account: String, name: String, type: String): ProfileDetail = definitions
        .filter { it.name.equals(name, true) && it.type == type }.minByOrNull { it.id }
        ?: ProfileDetail(UUID.nameUUIDFromBytes("chorus.profile:$account:$name".toByteArray(Charsets.UTF_8)).toString(), name, type, "private")

    fun value(member: String?, field: ProfileDetail): String = values[member]?.get(field.id).orEmpty()

    companion object {
        val slots = listOf("Location" to "text", "Website" to "url", "Website 2" to "url", "Website 3" to "url")
        fun parse(p: JSONObject): ProfileDetails {
            val rows = p.optJSONObject("rows") ?: return ProfileDetails()
            val defs = rows.optJSONObject("field_def") ?: JSONObject()
            val definitions = defs.keys().asSequence().mapNotNull { id ->
                val r = defs.optJSONObject(id)?.takeIf { it.optBoolean("exists") }?.optJSONObject("fields") ?: return@mapNotNull null
                if (!r.isNull("deleted_at")) return@mapNotNull null
                ProfileDetail(id, r.optString("name"), r.optString("type"),
                    r.optJSONObject("default_visibility")?.optString("mode") ?: "private")
            }.toList()
            val values = mutableMapOf<String, MutableMap<String, String>>()
            val v = rows.optJSONObject("field_value") ?: JSONObject()
            for (key in v.keys()) {
                val bar = key.lastIndexOf('|')
                if (bar <= 0) continue
                val f = v.optJSONObject(key)?.takeIf { it.optBoolean("exists") }?.optJSONObject("fields") ?: continue
                val raw = f.opt("value") as? String ?: continue
                values.getOrPut(key.substring(0, bar)) { mutableMapOf() }[key.substring(bar + 1)] = raw
            }
            val members = rows.optJSONObject("member") ?: JSONObject()
            val visibility = members.keys().asSequence().associateWith { id ->
                members.optJSONObject(id)?.optJSONObject("fields")?.optJSONObject("visibility")?.optString("mode") ?: "private"
            }
            return ProfileDetails(definitions, values, visibility, defs.keys().asSequence().toSet() - definitions.map { it.id }.toSet())
        }
    }
}

object MemberCustomization {
    fun markup(member: Member?): String = member?.let {
        toMarkup(JSONObject().put("text", it.description.orEmpty()).put("entities", JSONArray(it.descriptionEntities)).toString())
    }.orEmpty()

    fun validWebsite(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && value.none { it.isWhitespace() || it.isISOControl() }
    }.getOrDefault(false)

    fun validateDetails(values: List<String>, baseline: List<String> = List(ProfileDetails.slots.size) { "" }): List<Int> {
        require(values.size == ProfileDetails.slots.size)
        require(baseline.size == values.size)
        val changed = values.indices.filter { values[it] != baseline[it] && values[it].trim() != baseline[it] }
        require(0 !in changed || values.first().trim().length <= 160) { "Keep the location under 160 characters." }
        for (i in changed.filter { it > 0 }) require(values[i].trim().let { url -> url.isEmpty() || (url.length <= 2048 && validWebsite(url)) }) {
            "Use a complete http or https website without spaces or a login."
        }
        return changed
    }

    /** Opening snapshot is immutable even when sync refreshes the live model. */
    fun patch(baseline: Member?, name: String, display: String, pronouns: String, sigil: String, color: String,
        bio: String, baselineBio: String, avatar: String?, banner: String?, birthday: String, names: String = "", bioDraft: String? = null, bioDirty: Boolean = false,
        parse: ((String, String) -> String)? = null): JSONObject {
        require(name == baseline?.name || (name.trim().isNotEmpty() && name.trim().length <= 100)) { "Use a name of 1–100 characters." }
        require((display == baseline?.displayName.orEmpty() || display.trim().length <= 100) &&
            (pronouns == baseline?.pronouns.orEmpty() || pronouns.trim().length <= 100)) { "Keep names and pronouns under 100 characters." }
        require(color == baseline?.color || Regex("^#[0-9a-fA-F]{6}$").matches(color.trim())) { "Colour is #RRGGBB." }
        require(bio == baselineBio || bio.length <= 10000) { "Keep the bio under 10,000 characters." }
        val date = birthday.trim()
        require(birthday == baseline?.birthday.orEmpty() || date.isEmpty() || runCatching {
            java.time.LocalDate.parse(if (date.startsWith("--")) "2000${date.drop(1)}" else date)
        }.isSuccess) { "Use YYYY-MM-DD or --MM-DD for a birthday without a year." }
        val f = JSONObject()
        fun changed(key: String, new: String?, old: String?) {
            if (new != old || (baseline == null && key == "name")) f.put(key, new ?: JSONObject.NULL)
        }
        fun text(key: String, new: String, old: String?) {
            if (new != old.orEmpty() || (baseline == null && key == "name")) changed(key, new.trim().ifEmpty { null }, old)
        }
        text("name", name, baseline?.name); text("display_name", display, baseline?.displayName)
        text("pronouns", pronouns, baseline?.pronouns); text("birthday", date, baseline?.birthday)
        text("color", color, baseline?.color)
        changed("avatar_blob", avatar, baseline?.avatarBlob); changed("banner_blob", banner, baseline?.bannerBlob)
        if (bio != baselineBio || bioDirty) {
            val rich = if (parse == null) RichTextEdit.parse(bioDraft ?: RichTextEdit.open(
                baseline?.description.orEmpty(), baseline?.descriptionEntities ?: "[]", baselineBio), bio, names)
                else JSONObject(parse(bio, names))
            f.put("description", rich.getString("text").ifEmpty { null } ?: JSONObject.NULL)
                .put("description_entities", rich.getJSONArray("entities"))
        }
        if (sigil.trim() != baseline?.sigils?.firstOrNull().orEmpty()) {
            f.put("sigils", JSONArray(listOf(sigil.trim()).filter(String::isNotEmpty) + baseline?.sigils.orEmpty().drop(1)))
        }
        return f
    }
}
