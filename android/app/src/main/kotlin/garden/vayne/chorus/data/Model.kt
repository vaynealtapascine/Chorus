package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject

/** Typed views over the core projection (DATA_MODEL.md §4), mirroring web/src/lib/data.ts. */

data class Member(
    val id: String,
    val name: String,
    val displayName: String?,
    val pronouns: String?,
    val color: String,
    val sigils: List<String>,
    val description: String?,
    val archived: Boolean,
    val avatarBlob: String? = null,
) {
    val shownName: String get() = displayName ?: name
    val glyph: String get() = sigils.firstOrNull() ?: name.take(1).uppercase()
}

data class Group(val id: String, val name: String, val kind: String, val parentId: String?, val color: String?) {
    val isSubsystem: Boolean get() = kind == "subsystem"
}

data class Entry(val subjectType: String, val subjectId: String, val level: String, val isPrimary: Boolean) {
    fun toJson(): JSONObject = JSONObject()
        .put("subject_type", subjectType).put("subject_id", subjectId).put("level", level).put("is_primary", isPrimary)
}

data class SwitchRow(
    val id: String,
    val kind: String,
    val occurredAt: Long,
    val entries: List<Entry>,
    val resultingFront: List<Entry>,
    val note: String?,
    val retracted: Boolean,
)

/** One subject that can front: a member or a subsystem acting as one. */
data class Subject(val type: String, val id: String, val name: String, val color: String, val glyph: String, val avatarBlob: String? = null)

class Model(
    val members: List<Member>,
    val groups: List<Group>,
    /** group id → member ids */
    val membership: Map<String, Set<String>>,
    val current: List<Entry>,
    val since: Long?,
    val switches: List<SwitchRow>,
) {
    private val memberById = members.associateBy { it.id }
    private val groupById = groups.associateBy { it.id }

    fun member(id: String) = memberById[id]
    fun group(id: String) = groupById[id]

    fun subject(type: String, id: String): Subject? = when (type) {
        "member" -> memberById[id]?.let { Subject("member", it.id, it.shownName, it.color, it.glyph, it.avatarBlob) }
        "group" -> groupById[id]?.let { Subject("group", it.id, it.name, it.color ?: "#A09184", "◌") }
        else -> null
    }

    val active: List<Member> get() = members.filter { !it.archived }

    /** Members most recently switched in, newest first (for the quick-switch grid). */
    fun recents(limit: Int = 12): List<String> {
        val out = LinkedHashSet<String>()
        for (s in switches.asReversed()) {
            if (s.retracted) continue
            for (e in s.entries) if (e.subjectType == "member" && memberById[e.subjectId]?.archived == false) out.add(e.subjectId)
            if (out.size >= limit) break
        }
        return out.toList()
    }

    /** "Stars / Inner" */
    fun groupPath(g: Group): String {
        val names = ArrayList<String>()
        var cur: Group? = g
        val seen = HashSet<String>()
        while (cur != null && seen.add(cur.id)) {
            names.add(0, cur.name)
            cur = cur.parentId?.let { groupById[it] }
        }
        return names.joinToString(" / ")
    }

    fun frontLabel(entries: List<Entry> = current): String {
        val names = entries.filter { it.level == "front" }.mapNotNull { subject(it.subjectType, it.subjectId)?.name }
        return when (names.size) {
            0 -> "No one"
            1 -> names[0]
            2 -> "${names[0]} & ${names[1]}"
            else -> names.dropLast(1).joinToString(", ") + " & " + names.last()
        }
    }

    companion object {
        val Empty = Model(emptyList(), emptyList(), emptyMap(), emptyList(), null, emptyList())

        private fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) optString(k).ifEmpty { null } else null
        private fun JSONObject.present(k: String) = has(k) && !isNull(k)

        private fun rows(p: JSONObject, table: String): List<Pair<String, JSONObject>> {
            val t = p.optJSONObject("rows")?.optJSONObject(table) ?: return emptyList()
            return t.keys().asSequence().mapNotNull { id ->
                val r = t.getJSONObject(id)
                if (r.optBoolean("exists")) id to r.getJSONObject("fields") else null
            }.toList()
        }

        private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else List(a.length()) { a.getString(it) }

        private fun entries(a: JSONArray?): List<Entry> = if (a == null) emptyList() else List(a.length()) {
            val e = a.getJSONObject(it)
            Entry(e.getString("subject_type"), e.getString("subject_id"), e.optString("level", "front"), e.optBoolean("is_primary"))
        }

        fun parse(json: String, accountId: String): Model {
            val p = JSONObject(json)
            val members = rows(p, "member")
                .filter { (_, f) -> !f.present("deleted_at") }
                .map { (id, f) ->
                    Member(
                        id, f.str("name") ?: "Unnamed", f.str("display_name"), f.str("pronouns"),
                        f.str("color") ?: "#A09184", strings(f.optJSONArray("sigils")), f.str("description"),
                        f.present("archived_at"),
                        f.str("avatar_blob"),
                    )
                }
                .sortedBy { it.shownName.lowercase() }
            val groups = rows(p, "member_group")
                .filter { (_, f) -> !f.present("deleted_at") }
                .map { (id, f) -> Group(id, f.str("name") ?: "Untitled", f.str("kind") ?: "subsystem", f.str("parent_id"), f.str("color")) }
                .sortedBy { it.name.lowercase() }
            val membership = HashMap<String, MutableSet<String>>()
            p.optJSONObject("sets")?.optJSONObject("group_membership")?.let { set ->
                for (key in set.keys()) {
                    if (!set.optBoolean(key)) continue
                    val bar = key.indexOf('|')
                    val member = JSONObject(key.substring(bar + 1)).getString("member_id")
                    membership.getOrPut(key.substring(0, bar)) { HashSet() }.add(member)
                }
            }
            val fold = p.optJSONObject("fronts")?.optJSONObject(accountId)
            val current = entries(fold?.optJSONArray("current"))
            var since: Long? = null
            fold?.optJSONArray("intervals")?.let { iv ->
                for (i in 0 until iv.length()) {
                    val o = iv.getJSONObject(i)
                    if (o.isNull("end_at")) {
                        val s = o.getLong("start_at")
                        since = since?.let { minOf(it, s) } ?: s
                    }
                }
            }
            val switches = fold?.optJSONArray("switches")?.let { a ->
                List(a.length()) {
                    val s = a.getJSONObject(it)
                    SwitchRow(
                        s.getString("id"), s.optString("kind"), s.getLong("occurred_at"),
                        entries(s.optJSONArray("entries")), entries(s.optJSONArray("resulting_front")),
                        s.str("note"), s.optBoolean("retracted"),
                    )
                }
            } ?: emptyList()
            return Model(members, groups, membership, current, since, switches)
        }
    }
}
