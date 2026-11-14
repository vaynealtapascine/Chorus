package garden.vayne.chorus.data

import org.json.JSONObject

/** One other account's shared front entry in a space, already revealed (`GET /spaces/{id}/fronts`, D-133). */
data class SpaceFrontEntry(val kind: String, val level: String, val isPrimary: Boolean, val name: String, val color: String?, val glyph: String?)
data class SpaceFront(val accountId: String, val entries: List<SpaceFrontEntry>)

/**
 * Who is fronting in a shared space, as the other accounts share it. Nothing else feeds this: no follower
 * view, no profile lookups. Authors whose member card a space withholds (`member_names` off) fall back
 * to their account here too (web's space-fronts.ts).
 */
object SpaceFronts {
    /** How often an open member list asks again: the status changes only at reveal times, minutes after a switch. */
    const val REFRESH_MS = 3 * 60_000L

    private fun text(j: JSONObject, key: String): String? = if (j.has(key) && !j.isNull(key)) j.optString(key).takeIf { it.isNotBlank() } else null

    fun parse(j: JSONObject): List<SpaceFront> {
        val items = j.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val item = items.optJSONObject(i) ?: return@mapNotNull null
            val account = text(item, "account_id") ?: return@mapNotNull null
            val entries = item.optJSONArray("entries")
            SpaceFront(account, (0 until (entries?.length() ?: 0)).mapNotNull { k ->
                val e = entries?.optJSONObject(k) ?: return@mapNotNull null
                SpaceFrontEntry(e.optString("t"), e.optString("level"), e.optBoolean("is_primary", false),
                    text(e, "name") ?: return@mapNotNull null, text(e, "color"), text(e, "glyph"))
            })
        }
    }

    private val RANK = mapOf("front" to 0, "cocon" to 1, "present" to 2)

    /** The entries to show, the primary fronter first, then by level. */
    fun ordered(entries: List<SpaceFrontEntry>): List<SpaceFrontEntry> =
        entries.withIndex().sortedWith(compareBy<IndexedValue<SpaceFrontEntry>>({ if (it.value.isPrimary) 0 else 1 },
            { RANK[it.value.level] ?: 3 }, { it.index })).map { it.value }

    /** Each account's entries to show, by account id; accounts with nobody to show are left out. */
    fun byAccount(items: List<SpaceFront>): Map<String, List<SpaceFrontEntry>> =
        items.filter { it.entries.isNotEmpty() }.associate { it.accountId to ordered(it.entries) }

    /** "Kairos & Someone". */
    fun line(entries: List<SpaceFrontEntry>): String = entries.joinToString(" & ") { it.name }

    /** The glyph of a disc: the entry's own, else the first letter of its name. */
    fun glyph(e: SpaceFrontEntry): String = e.glyph ?: e.name.trim().take(1).uppercase().ifEmpty { "?" }

    /**
     * Stand-ins for message authors with no card (another account's members withheld by `member_names`
     * off): the account's own name and picture, never "Unknown member". Only for authors nobody else knows.
     */
    fun standIns(messages: List<ChatMessage>, known: (String, ChatMessage) -> Boolean, accounts: List<SpaceAccount>, me: String): Map<String, ForeignAuthor> {
        val byId = accounts.associateBy { it.id }
        val out = LinkedHashMap<String, ForeignAuthor>()
        for (m in messages) {
            val account = m.accountId?.takeIf { it != me }?.let(byId::get) ?: continue
            for (id in m.authors) if (id !in out && !known(id, m))
                out[id] = ForeignAuthor(id, account.id, account.shownName, avatarBlob = account.avatarBlob)
        }
        return out
    }
}
