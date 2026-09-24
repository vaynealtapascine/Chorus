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
    val isSelf: Boolean = false,
    val proxyTags: List<ProxyTag> = emptyList(),
    val createdByAccountId: String? = null,
    val bannerBlob: String? = null,
    val pinnedPostId: String? = null,
) {
    val shownName: String get() = displayName ?: name
    val glyph: String get() = sigils.firstOrNull() ?: name.take(1).uppercase()
}

data class ProxyTag(val prefix: String, val suffix: String)

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

data class ChatSpace(val id: String, val kind: String, val name: String)
data class ChatChannel(val id: String, val spaceId: String, val kind: String, val name: String, val parentMessageId: String?)
data class ChatAttachment(
    val id: String, val blobHash: String, val thumbHash: String?, val filename: String,
    val mime: String, val size: Long, val altText: String, val spoiler: Boolean,
)
data class ChatMessage(
    val id: String, val channelId: String, val authors: List<String>, val text: String,
    val occurredAt: Long, val cw: String?, val visibilityMode: String,
    val visibleMemberIds: Set<String>, val accountId: String?, val replyTo: String?,
    val attachments: List<ChatAttachment>,
)

data class JournalPost(
    val id: String, val kind: String, val authors: List<String>, val title: String?, val text: String,
    val occurredAt: Long, val cw: String?, val visibility: String, val replyTo: String?,
    val mood: String?, val tags: List<String>, val reactions: List<PostReaction> = emptyList(),
)

data class MemberList(val id: String, val name: String, val description: String?, val memberIds: Set<String>)
data class SavedFeed(val id: String, val name: String, val description: String?, val query: String, val visibility: String)

class Model(
    val members: List<Member>,
    val groups: List<Group>,
    /** group id → member ids */
    val membership: Map<String, Set<String>>,
    val current: List<Entry>,
    val since: Long?,
    val switches: List<SwitchRow>,
    val spaces: List<ChatSpace> = emptyList(),
    val channels: List<ChatChannel> = emptyList(),
    /** Newest 100 non-deleted messages per channel, oldest first for display. */
    val chatMessages: Map<String, List<ChatMessage>> = emptyMap(),
    /** Own account's per-follower ceiling overrides; absent means inherit the account default. */
    val followCeilings: Map<String, JSONObject> = emptyMap(),
    val posts: List<JournalPost> = emptyList(),
    val postReactions: Map<String, List<PostReaction>> = emptyMap(),
    val memberLists: List<MemberList> = emptyList(),
    val savedFeeds: List<SavedFeed> = emptyList(),
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
    /** The server creates one self member for a person account. */
    val isPerson: Boolean get() = members.any { it.isSelf }

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

        private fun stringSet(a: JSONArray?): Set<String> = strings(a).toSet()

        private fun entries(a: JSONArray?): List<Entry> = if (a == null) emptyList() else List(a.length()) {
            val e = a.getJSONObject(it)
            Entry(e.getString("subject_type"), e.getString("subject_id"), e.optString("level", "front"), e.optBoolean("is_primary"))
        }

        fun parse(json: String, accountId: String): Model = parse(JSONObject(json), accountId)

        /**
         * Apply a projection delta (core projector.rs `Delta`) to a cached projection object in
         * place: only changed rows/sets/fronts travel across the FFI (SPEC §9).
         */
        fun applyDelta(p: JSONObject, d: JSONObject) {
            for (section in listOf("rows", "sets")) {
                val changes = d.optJSONObject(section) ?: continue
                val target = p.optJSONObject(section) ?: JSONObject().also { p.put(section, it) }
                for (table in changes.keys()) {
                    val c = changes.getJSONObject(table)
                    val t = target.optJSONObject(table) ?: JSONObject().also { target.put(table, it) }
                    for (k in c.keys()) if (c.isNull(k)) t.remove(k) else t.put(k, c.get(k))
                    if (t.length() == 0) target.remove(table)
                }
            }
            for (section in listOf("fronts", "reviews")) {
                val changes = d.optJSONObject(section) ?: continue
                val target = p.optJSONObject(section) ?: JSONObject().also { p.put(section, it) }
                for (k in changes.keys()) if (changes.isNull(k)) target.remove(k) else target.put(k, changes.get(k))
            }
            p.put("opaque", d.optInt("opaque"))
        }

        fun parse(p: JSONObject, accountId: String): Model {
            val members = rows(p, "member")
                .filter { (_, f) -> !f.present("deleted_at") }
                .map { (id, f) ->
                    Member(
                        id, f.str("name") ?: "Unnamed", f.str("display_name"), f.str("pronouns"),
                        f.str("color") ?: "#A09184", strings(f.optJSONArray("sigils")), f.str("description"),
                        f.present("archived_at"),
                        f.str("avatar_blob"),
                        f.optBoolean("is_self") || f.optInt("is_self") == 1,
                        f.optJSONArray("proxy_tags")?.let { tags -> (0 until tags.length()).mapNotNull { i ->
                            tags.optJSONObject(i)?.let { ProxyTag(it.optString("prefix"), it.optString("suffix")) }
                        } } ?: emptyList(),
                        f.str("created_by_account_id"), f.str("banner_blob"), f.str("pinned_post_id"),
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
            val spaces = rows(p, "space").filter { (_, f) -> !f.present("deleted_at") }
                .map { (id, f) -> ChatSpace(id, f.str("kind") ?: "shared", f.str("name") ?: "Space") }
                .sortedWith(compareBy<ChatSpace> { if (it.kind == "internal") 0 else if (it.kind == "shared") 1 else 2 }.thenBy { it.name })
            val channels = rows(p, "channel").filter { (_, f) -> !f.present("deleted_at") && !f.present("archived_at") }
                .map { (id, f) -> ChatChannel(id, f.str("space_id") ?: "", f.str("kind") ?: "text",
                    f.str("name") ?: "Channel", f.str("parent_message_id")) }
                .filter { channel -> spaces.any { it.id == channel.spaceId } }
                .sortedBy { it.name }
            val channelIds = channels.map { it.id }.toSet()
            val attachments = rows(p, "attachment").associate { it.first to it.second }
            val selected = rows(p, "message")
                .filter { (_, f) -> !f.present("deleted_at") && f.str("channel_id") in channelIds }
                .groupBy { (_, f) -> f.str("channel_id").orEmpty() }
                .mapValues { (_, values) -> values.sortedWith(compareBy<Pair<String, JSONObject>> { it.second.optLong("occurred_at") }.thenBy { it.first }).takeLast(100) }
            val chatMessages = selected.mapValues { (_, values) -> values.map { (id, f) ->
                val links = f.optJSONArray("attachments")
                val files = if (links == null) emptyList() else (0 until links.length()).mapNotNull { i ->
                    val attachmentId = links.optString(i)
                    val a = attachments[attachmentId] ?: return@mapNotNull null
                    val hash = a.str("blob_hash") ?: return@mapNotNull null
                    ChatAttachment(attachmentId, hash, a.str("thumb_blob_hash"), a.str("filename") ?: "file",
                        a.str("mime") ?: "application/octet-stream", a.optLong("size"), a.str("alt_text").orEmpty(),
                        a.optBoolean("is_spoiler"))
                }
                val visibility = f.optJSONObject("visibility")
                ChatMessage(id, f.str("channel_id").orEmpty(), strings(f.optJSONArray("authors")), f.str("text").orEmpty(),
                    f.optLong("occurred_at"), f.str("cw"), visibility?.optString("mode")?.ifEmpty { "all" } ?: "all",
                    stringSet(visibility?.optJSONArray("member_ids")), f.str("account_id"), f.str("reply_to"), files)
            } }
            val followCeilings = rows(p, "follow").associate { (id, f) -> id to (f.optJSONObject("ceiling") ?: JSONObject()) }
            val postReactions = PostReactions.fromProjection(p.optJSONObject("sets"), members.associate { it.id to it.shownName })
            val posts = rows(p, "post").filter { (_, f) -> !f.present("deleted_at") }
                .map { (id, f) -> JournalPost(id, f.str("kind") ?: "note", strings(f.optJSONArray("authors")),
                    f.str("title"), f.str("text").orEmpty(), f.optLong("occurred_at"), f.str("cw"),
                    f.optJSONObject("visibility")?.str("mode") ?: "private", f.str("reply_to"),
                    f.str("mood"), strings(f.optJSONArray("tags")), postReactions[id].orEmpty()) }
                .sortedWith(compareByDescending<JournalPost> { it.occurredAt }.thenByDescending { it.id })
            val listItems = HashMap<String, MutableSet<String>>()
            p.optJSONObject("sets")?.optJSONObject("member_list_item")?.let { set ->
                for (key in set.keys()) {
                    if (!set.optBoolean(key)) continue
                    val bar = key.indexOf('|')
                    if (bar < 0) continue
                    val memberId = runCatching { JSONObject(key.substring(bar + 1)).optString("member_id") }.getOrNull()
                        ?.takeIf { it.isNotBlank() && it != "null" } ?: continue
                    listItems.getOrPut(key.substring(0, bar)) { HashSet() }.add(memberId)
                }
            }
            val memberLists = rows(p, "member_list").filter { (_, f) -> !f.present("deleted_at") }
                .map { (id, f) -> MemberList(id, f.str("name") ?: "Untitled", f.str("description"), listItems[id].orEmpty()) }
                .sortedWith(compareBy<MemberList> { it.name.lowercase() }.thenBy { it.id })
            val savedFeeds = rows(p, "feed").filter { (_, f) -> !f.present("deleted_at") && f.present("query_ast") && !f.str("query").isNullOrBlank() }
                .map { (id, f) -> SavedFeed(id, f.str("name") ?: "Untitled", f.str("description"), f.str("query").orEmpty(),
                    f.optJSONObject("visibility")?.str("mode") ?: "private") }
                .sortedWith(compareBy<SavedFeed> { it.name.lowercase() }.thenBy { it.id })
            return Model(members, groups, membership, current, since, switches, spaces, channels, chatMessages,
                followCeilings, posts, postReactions, memberLists, savedFeeds)
        }
    }
}
