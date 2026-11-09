package garden.vayne.chorus.data

import org.json.JSONObject

data class RelationshipType(val id: String, val name: String, val inverseName: String?, val symmetric: Boolean)
data class MemberRelationship(val id: String, val fromMemberId: String, val toKind: String,
    val toId: String?, val toLabel: String?, val typeId: String, val note: String?, val visibility: String = "{\"mode\":\"private\"}")
data class ProfileLink(val id: String, val type: String, val target: String, val note: String?)
/** One line of F6.5: who is on the other end of [memberId]'s link, and how. [inside] is one of our own members. */
data class RelationshipRow(val id: String, val otherId: String?, val name: String, val inside: Boolean, val type: String, val note: String?, val outgoing: Boolean)

/** Account-local relationship rows and their profile labels. */
object LocalRelationships {
    fun typePayload(name: String, inverseName: String?, symmetric: Boolean): JSONObject = JSONObject()
        .put("name", name).put("inverse_name", if (symmetric) name else inverseName ?: JSONObject.NULL)
        .put("is_symmetric", symmetric)

    fun linkPayload(fromMemberId: String, typeId: String, toMemberId: String?, toLabel: String?, note: String?, audience: Audience = Audience.PRIVATE): JSONObject =
        JSONObject().put("from_member_id", fromMemberId)
            .put("to_kind", if (toMemberId != null) "member" else "external")
            .put("to_id", toMemberId ?: JSONObject.NULL).put("to_label", toLabel ?: JSONObject.NULL)
            .put("type_id", typeId).put("note", note ?: JSONObject.NULL)
            .put("visibility", audience.toJson())

    fun types(p: JSONObject): List<RelationshipType> = rows(p, "relationship_type").mapNotNull { (id, f) ->
        val name = f.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        RelationshipType(id, name, f.string("inverse_name"), f.optBoolean("is_symmetric") || f.optInt("is_symmetric") == 1)
    }.sortedWith(compareBy<RelationshipType> { it.name.lowercase() }.thenBy { it.id })

    fun links(p: JSONObject): List<MemberRelationship> = rows(p, "relationship").mapNotNull { (id, f) ->
        val from = f.string("from_member_id") ?: return@mapNotNull null
        val type = f.string("type_id") ?: return@mapNotNull null
        val kind = f.string("to_kind") ?: return@mapNotNull null
        val toId = f.string("to_id")
        val toLabel = f.string("to_label")
        if (toId == null && toLabel == null) return@mapNotNull null
        MemberRelationship(id, from, kind, toId, toLabel, type, f.string("note"), f.optJSONObject("visibility")?.toString() ?: "{\"mode\":\"private\"}")
    }

    /** [memberId]'s links, ours first; [others] names members of other accounts by id (the server's targets). */
    fun rows(model: Model, memberId: String, others: Map<String, String> = emptyMap()): List<RelationshipRow> {
        val types = model.relationshipTypes.associateBy { it.id }
        return model.relationships.mapNotNull { link ->
            val outgoing = link.fromMemberId == memberId
            if (!outgoing && !(link.toKind == "member" && link.toId == memberId)) return@mapNotNull null
            val type = types[link.typeId]
            val typeName = if (outgoing || type?.symmetric == true) type?.name ?: "Relationship"
                else type?.inverseName ?: "${type?.name ?: "Relationship"} (incoming)"
            val otherId = if (outgoing) link.toId.takeIf { link.toKind == "member" } else link.fromMemberId
            val mine = otherId?.let { model.member(it) }
            RelationshipRow(link.id, otherId, mine?.shownName ?: otherId?.let { others[it] } ?: link.toLabel ?: "Someone",
                mine != null, typeName, link.note, outgoing)
        }.sortedWith(compareByDescending<RelationshipRow> { it.inside }.thenBy { it.name.lowercase() }.thenBy { it.id })
    }

    fun forMember(model: Model, memberId: String): List<ProfileLink> {
        val types = model.relationshipTypes.associateBy { it.id }
        return model.relationships.mapNotNull { link ->
            val outgoing = link.fromMemberId == memberId
            if (!outgoing && !(link.toKind == "member" && link.toId == memberId)) return@mapNotNull null
            val type = types[link.typeId]
            val name = if (outgoing || type?.symmetric == true) type?.name ?: "Relationship"
                else type?.inverseName ?: "${type?.name ?: "Relationship"} (incoming)"
            val target = if (outgoing) {
                if (link.toKind == "member") model.member(link.toId.orEmpty())?.shownName ?: "Member"
                else link.toLabel ?: link.toId ?: "Someone"
            } else model.member(link.fromMemberId)?.shownName ?: "Member"
            ProfileLink(link.id, name, target, link.note)
        }
    }

    private fun JSONObject.string(key: String): String? =
        optString(key).takeIf { has(key) && !isNull(key) && it.isNotBlank() && it != "null" }

    private fun rows(p: JSONObject, table: String): List<Pair<String, JSONObject>> {
        val all = p.optJSONObject("rows")?.optJSONObject(table) ?: return emptyList()
        return all.keys().asSequence().mapNotNull { id ->
            val row = all.optJSONObject(id)?.takeIf { it.optBoolean("exists") }?.optJSONObject("fields")
                ?: return@mapNotNull null
            if (row.has("deleted_at") && !row.isNull("deleted_at")) return@mapNotNull null
            id to row
        }.toList()
    }
}
