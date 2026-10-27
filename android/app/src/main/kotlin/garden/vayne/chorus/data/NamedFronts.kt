package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject

/** Own-account DTOs from core's current projection. Following and purpose eligibility are
 * already resolved in Rust; Kotlin only reads the resulting pools. */
data class FrontLayer(val id: String, val name: String, val level: Int, val deleted: Boolean)

data class FrontPresence(val subjectType: String, val subjectId: String, val layerId: String, val isPrimary: Boolean) {
    fun toJson(): JSONObject = JSONObject().put("subject_type", subjectType).put("subject_id", subjectId)
        .put("layer_id", layerId).put("is_primary", isPrimary)
    fun displayEntry() = Entry(subjectType, subjectId, layerId, isPrimary)
}

data class NamedFront(
    val id: String, val name: String, val own: List<FrontPresence>, val followSource: String?,
    val overridden: Boolean, val deleted: Boolean, val authorshipLayers: Set<String>?,
    val notificationLayers: Set<String>?, val purposeLayers: Map<String, Set<String>?>,
)

data class ResolvedFront(
    val id: String, val name: String, val own: List<FrontPresence>, val effective: List<FrontPresence>,
    val source: String?, val state: String, val issue: String?, val deleted: Boolean,
    val authorship: List<FrontPresence>, val notifications: List<FrontPresence>,
    val purposeCandidates: Map<String, List<FrontPresence>>,
) {
    /** Recovery data is available for display/repair, but is not confirmed current presence. */
    val confirmed: List<FrontPresence> get() = if (deleted || state == "unavailable") emptyList() else effective
}

data class NamedFronts(
    val systemJson: String, val layers: List<FrontLayer>, val fronts: Map<String, NamedFront>,
    val resolved: Map<String, ResolvedFront>,
    /** Body's stable front ID: the account ID. */
    val bodyId: String = "",
) {
    /** Live layers, inner to outer (display level, then ID). */
    val liveLayers: List<FrontLayer> get() = layers.filter { !it.deleted }

    /** Confirmed presence of one front as display entries; empty when unknown/unavailable. */
    fun entries(frontId: String): List<Entry> = resolved[frontId]?.confirmed?.map { it.displayEntry() }.orEmpty()

    /** Live fronts a channel can select, Body first. */
    val channelChoices: List<NamedFront> get() = fronts.values.filter { !it.deleted }
        .sortedWith(compareBy<NamedFront> { it.id != bodyId }.thenBy { it.name.lowercase() }.thenBy { it.id })

    companion object {
        private fun optionalString(o: JSONObject, key: String): String? =
            if (o.has(key) && !o.isNull(key)) o.getString(key) else null

        private fun selection(o: JSONObject, key: String): Set<String>? =
            o.optJSONArray(key)?.let { a -> List(a.length()) { a.getString(it) }.toSet() }

        fun presence(a: JSONArray?): List<FrontPresence> = if (a == null) emptyList() else List(a.length()) { i ->
            val e = a.getJSONObject(i)
            FrontPresence(e.getString("subject_type"), e.getString("subject_id"), e.getString("layer_id"), e.optBoolean("is_primary"))
        }

        fun parse(current: JSONObject?, bodyId: String = ""): NamedFronts? {
            val system = current?.optJSONObject("system") ?: return null
            val layerRows = system.optJSONObject("layers") ?: JSONObject()
            val layers = layerRows.keys().asSequence().map { id ->
                val l = layerRows.getJSONObject(id)
                FrontLayer(id, l.getString("name"), l.getInt("level"), l.optBoolean("deleted"))
            }.sortedWith(compareBy<FrontLayer> { it.level }.thenBy { it.id }).toList()
            val definitions = system.optJSONObject("fronts") ?: JSONObject()
            val fronts = definitions.keys().asSequence().associateWith { id ->
                val f = definitions.getJSONObject(id)
                val purposes = f.optJSONObject("purpose_layers") ?: JSONObject()
                NamedFront(id, f.getString("name"), presence(f.optJSONArray("own")),
                    f.optJSONObject("follow")?.optString("source"), f.optBoolean("overridden"), f.optBoolean("deleted"),
                    selection(f, "authorship_layers"), selection(f, "notification_layers"),
                    purposes.keys().asSequence().associateWith { selection(purposes, it) })
            }
            val results = current.optJSONObject("resolved") ?: JSONObject()
            val resolved = results.keys().asSequence().associateWith { id ->
                val f = results.getJSONObject(id)
                val purposes = f.optJSONObject("purpose_candidates") ?: JSONObject()
                ResolvedFront(id, f.getString("name"), presence(f.optJSONArray("own")), presence(f.optJSONArray("effective")),
                    optionalString(f, "source"), f.getString("state"), optionalString(f, "issue"), f.optBoolean("deleted"),
                    presence(f.optJSONArray("authorship")), presence(f.optJSONArray("notifications")),
                    purposes.keys().asSequence().associateWith { presence(purposes.optJSONArray(it)) })
            }
            return NamedFronts(system.toString(), layers, fronts, resolved, bodyId)
        }
    }
}
