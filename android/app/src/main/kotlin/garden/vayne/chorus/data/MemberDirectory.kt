package garden.vayne.chorus.data

import org.json.JSONObject

/**
 * What the Members screens count and say (UI21, Figma F4.1 and F4.4): the chips' counts, the
 * groups' lines, and who can join a group. Display only; the writes are ordinary group ops
 * (`group.add_member` / `group.remove_member`, DATA_MODEL §5), so plain groups fill offline too.
 */
object MemberDirectory {
    data class Counts(val all: Int, val fronting: Int, val archived: Int)

    /** Someone who could join a group, and whether they can't because the group is inside them. */
    data class Candidate(val member: Member, val inside: Boolean, val loops: Boolean)

    /** Members in the front as switched (D-102): a fronting subsystem's parts aren't counted. */
    fun frontingIds(model: Model): Set<String> =
        model.current.filter { it.subjectType == "member" }.mapTo(LinkedHashSet()) { it.subjectId }

    fun counts(model: Model, accountId: String?): Counts {
        val active = MemberOrdering.eligible(model.members, accountId, false)
        val fronting = frontingIds(model)
        return Counts(active.size, active.count { it.id in fronting }, MemberOrdering.eligible(model.members, accountId, true).size)
    }

    /** A group's active members, in the members' own order (D-090). */
    fun members(model: Model, groupId: String): List<Member> {
        val ids = model.membership[groupId].orEmpty()
        return MemberOrdering.ordered(model.members.filter { !it.archived && it.id in ids })
    }

    /** "Foxglove, Amarynthe, Nairon" or "Foxglove, Kai, Wren +2" (F4.1 group rows). */
    fun names(members: List<Member>, shown: Int = 3): String {
        val head = members.take(shown).joinToString(", ") { it.shownName }
        return if (members.size > shown) "$head +${members.size - shown}" else head
    }

    /** "3 members", and who can see the group once a write has chosen it (F4.4, UI21 default:
     * nothing reads group visibility yet, so an unset one says nothing). */
    fun line(model: Model, group: Group, buckets: Map<String, String> = model.sharingBuckets.toMap()): String {
        val n = members(model, group.id).size
        val count = "$n ${if (n == 1) "member" else "members"}"
        val seen = group.visibility?.let { runCatching { JSONObject(it) }.getOrNull() }?.let(ProfileAudience::of) ?: return count
        val who = if (seen.isPrivate(buckets.keys)) "only our system" else "${seen.label(buckets).lowercase()} can see it"
        return "$count · $who"
    }

    /** The groups [memberId] is in, by path ("Day-to-day, Stars / Inner"). */
    fun groupsOf(model: Model, memberId: String): List<Group> = model.groups.filter { memberId in model.membership[it.id].orEmpty() }

    /**
     * This account's active members who could be in [group], those already in it first. A
     * subsystem's own member can't be inside it, nor can anyone the subsystem is already inside.
     */
    fun candidates(model: Model, group: Group, accountId: String?): List<Candidate> {
        val inside = model.membership[group.id].orEmpty()
        val owner = Subsystems.memberOf(model, group.id)
        return MemberOrdering.eligible(model.members, accountId, false)
            .filter { it.id != owner?.id }
            .map { m -> Candidate(m, m.id in inside, m.id !in inside && owner != null && Subsystems.wouldLoop(model, owner.id, m.id)) }
            .sortedBy { !it.inside }
    }

    /** The op that puts [memberId] in [groupId] or takes them out. */
    fun membershipOp(inside: Boolean): String = if (inside) "group.remove_member" else "group.add_member"
}
