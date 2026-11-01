package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale
import java.util.UUID
import uniffi.chorus_ffi.toMarkup

data class ProfileDetail(val id: String, val name: String, val type: String, val visibility: String,
    /** `default_visibility` as JSON text; choices for select fields (D-101). */
    val defaultVisibility: String = JSONObject().put("mode", visibility).toString(), val choices: List<String> = emptyList(),
    val options: String = "{}", val sortKey: String? = null)

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
        /** Field types the editor can show and edit. */
        val EDITABLE = setOf("text", "long_text", "url", "number", "date", "boolean", "select", "multi_select", "color")

        /** A stored value as the editor's text: strings as they are, choice lists as JSON, numbers and yes/no as text. */
        fun valueText(v: Any?): String? = when (v) {
            null, JSONObject.NULL -> null
            is String -> v
            is JSONArray -> v.toString()
            is Boolean, is Number -> v.toString()
            else -> null
        }
        fun parse(p: JSONObject): ProfileDetails {
            val rows = p.optJSONObject("rows") ?: return ProfileDetails()
            val defs = rows.optJSONObject("field_def") ?: JSONObject()
            val definitions = defs.keys().asSequence().mapNotNull { id ->
                val r = defs.optJSONObject(id)?.takeIf { it.optBoolean("exists") }?.optJSONObject("fields") ?: return@mapNotNull null
                if (!r.isNull("deleted_at")) return@mapNotNull null
                val options = r.optJSONObject("options") ?: JSONObject()
                val audience = r.optJSONObject("default_visibility")
                ProfileDetail(id, r.optString("name"), r.optString("type"), audience?.optString("mode") ?: "private",
                    (audience ?: JSONObject().put("mode", "private")).toString(),
                    options.optJSONArray("choices")?.let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? String } }.orEmpty(),
                    options.toString(), r.optString("sort_key").ifEmpty { null })
            }.sortedWith(compareBy({ it.sortKey ?: it.name }, { it.id })).toList()
            val values = mutableMapOf<String, MutableMap<String, String>>()
            val v = rows.optJSONObject("field_value") ?: JSONObject()
            for (key in v.keys()) {
                val bar = key.lastIndexOf('|')
                if (bar <= 0) continue
                val f = v.optJSONObject(key)?.takeIf { it.optBoolean("exists") }?.optJSONObject("fields") ?: continue
                val raw = valueText(f.opt("value")) ?: continue
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
        return validateValues(ProfileDetails.slots, values, baseline)
    }

    /** The indices of changed custom field values (by `(name, type)` row), each checked for its type. */
    fun validateValues(rows: List<Pair<String, String>>, values: List<String>, baseline: List<String>): List<Int> {
        require(rows.size == values.size && baseline.size == values.size)
        val changed = values.indices.filter { values[it] != baseline[it] && values[it].trim() != baseline[it] }
        for (i in changed) {
            val (name, type) = rows[i]
            val v = values[i].trim()
            if (v.isEmpty()) continue
            when (type) {
                "url" -> require(v.length <= 2048 && validWebsite(v)) { "Use a complete http or https website without spaces or a login." }
                "text" -> if (name.equals("Location", true)) require(v.length <= 160) { "Keep the location under 160 characters." }
                    else require(v.length <= 500) { "Keep $name under 500 characters." }
                "long_text" -> require(v.length <= 10000) { "Keep $name under 10,000 characters." }
                "number" -> require(v.toDoubleOrNull()?.isFinite() == true) { "$name is a number." }
                "date" -> require(runCatching { java.time.LocalDate.parse(v) }.isSuccess) { "Use YYYY-MM-DD for $name." }
                "boolean" -> require(v == "true" || v == "false") { "$name is yes or no." }
                "color" -> require(Regex("^#[0-9a-fA-F]{6}$").matches(v)) { "$name is a colour like #RRGGBB." }
                "select" -> require(v.length <= 100) { "Keep $name under 100 characters." }
                "multi_select" -> require(runCatching { JSONArray(v) }.getOrNull()?.let { a ->
                    (0 until a.length()).all { (a.opt(it) as? String)?.length in 1..100 } } == true) { "Choose $name again." }
            }
        }
        return changed
    }

    /** The JSON value a field's editor text is stored as; `NULL` clears it. */
    fun jsonValue(type: String, text: String): Any {
        val v = text.trim()
        if (v.isEmpty()) return JSONObject.NULL
        return when (type) {
            "number" -> v.toDouble().let { d -> if (d % 1.0 == 0.0 && kotlin.math.abs(d) < 1e15) d.toLong() else d }
            "boolean" -> v == "true"
            "multi_select" -> JSONArray(v).takeIf { it.length() > 0 } ?: JSONObject.NULL
            else -> v
        }
    }

    /** Opening snapshot is immutable even when sync refreshes the live model. */
    fun patch(baseline: Member?, name: String, display: String, pronouns: String, sigil: String, color: String,
        bio: String, baselineBio: String, avatar: String?, banner: String?, birthday: String, names: String = "", bioDraft: String? = null, bioDirty: Boolean = false,
        visibility: String? = null, fieldVisibility: String? = null, parse: ((String, String) -> String)? = null): JSONObject {
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
        // audiences (D-101): only when they differ from what was opened, so other devices' edits merge
        fun audience(key: String, new: String?, old: String?) {
            val next = ProfileAudience.parse(new) ?: return
            if (next.toString() != ProfileAudience.parse(old)?.toString()) f.put(key, next)
        }
        audience("visibility", visibility, baseline?.visibility)
        audience("field_visibility", fieldVisibility, baseline?.fieldVisibility)
        if (sigil.trim() != baseline?.sigils?.firstOrNull().orEmpty()) {
            f.put("sigils", JSONArray(listOf(sigil.trim()).filter(String::isNotEmpty) + baseline?.sigils.orEmpty().drop(1)))
        }
        return f
    }
}
