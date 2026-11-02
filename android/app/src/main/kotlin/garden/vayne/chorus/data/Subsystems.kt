package garden.vayne.chorus.data

import org.json.JSONObject

/** Members who are subsystems (PluralSpec §5.3 collectives, D-092): a member linked to a subsystem
 * group, whose members are "inside" them. The link is an account pref, `member_subsystem:<member
 * id>` = group id, so it reaches this account's devices without a server change; the parts are
 * ordinary group membership. Fronting is recorded as switched: only the UI reads the members of a
 * fronting subsystem as fronting with it ([expand]). */
object Subsystems {
    fun payload(memberId: String, groupId: String?): JSONObject =
        AccountPrefs.payload(AccountPrefs.MEMBER_SUBSYSTEM + memberId, groupId ?: JSONObject.NULL)

    /** The new subsystem group for [member]: named and coloured after them, nested under the one
     * subsystem they're already in (if exactly one), and not a front subject of its own, since the
     * member fronts for it. */
    fun groupPayload(model: Model, member: Member): JSONObject {
        val parent = model.membership.filter { member.id in it.value }.keys.mapNotNull(model::group)
            .filter { it.isSubsystem }.singleOrNull()
        return JSONObject().put("name", member.shownName).put("kind", "subsystem").put("color", member.color)
            .put("can_front", false).apply { parent?.let { put("parent_id", it.id) } }
    }

    /** The live subsystem group [memberId] is, if any. */
    fun groupOf(model: Model, memberId: String): Group? =
        model.accountPrefs.memberSubsystems[memberId]?.let(model::group)?.takeIf { it.isSubsystem }

    /** The member who is subsystem [groupId], if any. */
    fun memberOf(model: Model, groupId: String): Member? = model.accountPrefs.memberSubsystems.entries
        .firstOrNull { it.value == groupId }?.key?.let(model::member)?.takeIf { !it.archived && groupOf(model, it.id) != null }

    /** Subsystem groups no member is yet, which [memberId] could become. */
    fun unlinkedGroups(model: Model): List<Group> {
        val linked = model.accountPrefs.memberSubsystems.filterKeys { model.member(it)?.archived == false }.values.toSet()
        return model.groups.filter { it.isSubsystem && it.id !in linked }
    }

    /** Active members directly inside [memberId] (their group's members), never themselves. */
    fun parts(model: Model, memberId: String): List<Member> {
        val group = groupOf(model, memberId) ?: return emptyList()
        return model.active.filter { it.id != memberId && it.id in model.membership[group.id].orEmpty() }
    }

    /** [ids] followed by everyone inside any of them who is a subsystem, recursively, each once: who a
     * members' chat with a subsystem includes (owner, 2026-11-02). Never upward: a chat with
     * someone inside doesn't include their subsystem. The server keeps D-098 chats the same way. */
    fun including(model: Model, ids: List<String>): List<String> {
        val out = ids.distinct().toMutableList()
        var i = 0
        while (i < out.size) {
            for (part in parts(model, out[i])) if (part.id !in out) out.add(part.id)
            i++
        }
        return out
    }

    /** Subsystem members [memberId] is directly inside, for "Part of Garden". */
    fun containers(model: Model, memberId: String): List<Member> = model.accountPrefs.memberSubsystems.keys
        .filter { it != memberId }.mapNotNull(model::member).filter { !it.archived && parts(model, it.id).any { p -> p.id == memberId } }

    /** Whether putting [candidate] inside [memberId] would make [memberId] (or the candidate) end
     * up inside themselves. */
    fun wouldLoop(model: Model, memberId: String, candidate: String): Boolean {
        if (candidate == memberId) return true
        val seen = HashSet<String>()
        val queue = ArrayDeque(listOf(candidate))
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (!seen.add(next)) continue
            for (part in parts(model, next)) { if (part.id == memberId) return true; queue.add(part.id) }
        }
        return false
    }

    /** Front [entries] plus everyone inside a fronting subsystem, recursively: the parts of a
     * subsystem member, and the members of a subsystem group fronting as a unit (with its nested
     * subsystems). Added members share their subsystem's layer and are never primary; an explicit
     * entry always wins. [Expanded.via] says which subsystem brought each added member in. */
    fun expand(model: Model, entries: List<Entry>): Expanded {
        val out = entries.toMutableList()
        val present = entries.filter { it.subjectType == "member" }.map { it.subjectId }.toMutableSet()
        val via = LinkedHashMap<String, Via>()
        val queue = ArrayDeque(entries)
        val seenGroups = HashSet<String>()
        while (queue.isNotEmpty()) {
            val entry = queue.removeFirst()
            val (name, inside) = when (entry.subjectType) {
                "member" -> model.member(entry.subjectId)?.shownName to parts(model, entry.subjectId)
                "group" -> model.group(entry.subjectId)?.takeIf { it.isSubsystem && seenGroups.add(it.id) }
                    ?.let { it.name to groupMembers(model, it.id) } ?: (null to emptyList())
                else -> null to emptyList()
            }
            for (part in inside) {
                if (!present.add(part.id)) continue
                val added = Entry("member", part.id, entry.level, false)
                out += added
                via[part.id] = Via(entry.subjectId, name ?: "a subsystem")
                queue.add(added)
            }
        }
        return Expanded(out, via)
    }

    /** [via]: added member id → the subsystem (member or group) whose front they share. */
    data class Expanded(val entries: List<Entry>, val via: Map<String, Via>)
    data class Via(val subjectId: String, val name: String)

    /** Active members of a subsystem group and of the subsystems nested under it. */
    private fun groupMembers(model: Model, groupId: String): List<Member> {
        val ids = HashSet<String>()
        val seen = HashSet<String>()
        val queue = ArrayDeque(listOf(groupId))
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!seen.add(id)) continue
            ids += model.membership[id].orEmpty()
            model.groups.filter { it.parentId == id && it.isSubsystem }.forEach { queue.add(it.id) }
        }
        val self = memberOf(model, groupId)?.id
        return model.active.filter { it.id in ids && it.id != self }
    }
}
