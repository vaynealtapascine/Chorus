package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject
import uniffi.chorus_ffi.memberHandles

/** A member's handles (D-126), without the `@`: the reserved default `member.subsystem.system`,
 *  the ones they claim, and the one shown everywhere (the first claim, else the default). */
data class MemberHandles(val default: String, val claimed: List<String>, val shown: String)

/**
 * Member handles (D-126): defaults worked out by the core (`chorus_core::handles`) from this
 * device's projection, plus the handles members claim (`member.handles`). Another account's member
 * arrives as a card carrying the handle we may see. Mirrors web/src/lib/handles.ts.
 */
object Handles {
    const val MAX_CLAIMS = 5
    private val CLAIM = Regex("^[a-z0-9_]{2,32}$")

    /** A claim (and an account handle): 2–32 of a-z, 0-9 and _; never a dot, so never a default. */
    fun valid(h: String): Boolean = CLAIM.matches(h)

    /** What the claim box turns typing into (core `normalize_claim`). */
    fun normalize(h: String): String = h.trim().trimStart('@').lowercase().replace(' ', '_').replace('-', '_')

    /** member id → handles for this account's members, archived and deleted ones included (so a
     *  default never moves when someone else leaves). Empty if the core can't be reached. */
    fun fromProjection(p: JSONObject, accountId: String): Map<String, MemberHandles> = runCatching {
        fun rows(table: String): List<Pair<String, JSONObject>> {
            val all = p.optJSONObject("rows")?.optJSONObject(table) ?: return emptyList()
            return all.keys().asSequence().mapNotNull { id ->
                all.optJSONObject(id)?.takeIf { it.optBoolean("exists") }?.optJSONObject("fields")?.let { id to it }
            }.toList()
        }
        fun JSONObject.text(key: String): Any = optString(key).takeIf { has(key) && !isNull(key) && it.isNotBlank() } ?: JSONObject.NULL
        val account = rows("account").firstOrNull { it.first == accountId }?.second ?: JSONObject()
        val system = rows("system").firstOrNull { it.first == accountId }?.second ?: JSONObject()
        val members = JSONArray()
        for ((id, f) in rows("member")) members.put(JSONObject().put("id", id).put("name", f.text("name"))
            .put("display_name", f.text("display_name")).put("short_id", f.text("short_id"))
            .put("is_self", f.optBoolean("is_self") || f.optInt("is_self") == 1)
            .put("handles", f.optJSONArray("handles") ?: JSONArray()))
        val groups = JSONArray()
        for ((id, f) in rows("member_group")) if (f.isNull("deleted_at") || !f.has("deleted_at"))
            groups.put(JSONObject().put("id", id).put("kind", f.text("kind")).put("parent_id", f.text("parent_id")).put("name", f.text("name")))
        val membership = JSONArray()
        p.optJSONObject("sets")?.optJSONObject("group_membership")?.let { set ->
            for (key in set.keys()) {
                if (!set.optBoolean(key)) continue
                val bar = key.indexOf('|')
                membership.put(JSONArray().put(key.substring(0, bar)).put(JSONObject(key.substring(bar + 1)).getString("member_id")))
            }
        }
        val name = system.text("name").takeIf { it != JSONObject.NULL } ?: account.text("display_name")
        val input = JSONObject()
            .put("account", JSONObject().put("id", accountId).put("handle", account.text("handle")).put("name", name))
            .put("members", members).put("groups", groups).put("membership", membership)
        val out = JSONObject(memberHandles(input.toString()))
        out.keys().asSequence().associateWith { id ->
            val h = out.getJSONObject(id)
            val claimed = h.optJSONArray("claimed")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()
            MemberHandles(h.getString("default"), claimed, h.getString("shown"))
        }
    }.getOrDefault(emptyMap())

    /** Every handle that names a member (ours: default and claims; another account's: what its card
     *  carries), lowercased; one two members share names neither. */
    fun mentions(own: Map<String, MemberHandles>, others: Map<String, Pair<String?, List<String>>> = emptyMap()): Map<String, String> {
        val seen = HashMap<String, String?>()
        fun add(handle: String?, id: String) {
            val key = handle?.lowercase()?.takeIf { it.isNotBlank() } ?: return
            seen[key] = if (key in seen && seen[key] != id) null else id
        }
        for ((id, h) in own) { add(h.default, id); h.claimed.forEach { add(it, id) } }
        for ((id, card) in others) { add(card.first, id); card.second.forEach { add(it, id) } }
        return seen.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
    }

    /** Who else here already has [handle]: our account, or another of our members (by name). */
    fun claimedHere(model: Model, handle: String, member: String): String? {
        if (model.accountHandle?.lowercase() == handle) return "your account"
        return model.members.firstOrNull { it.id != member && handle in it.handles }?.shownName
    }
}
