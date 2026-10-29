package garden.vayne.chorus.data

/** Sparse fractional string positions. Unpositioned members retain name order; IDs break ties.
 * Only Members uses this order. Search relevance, fronts and recents keep their own ordering.
 * Printable ASCII positions have no terminal space, so there is always room between positions.
 */
object MemberOrdering {
    private const val PREFIX = "~ui6:"
    private fun hex(s: String) = s.map { it.code.toString(16).padStart(4, '0') }.joinToString("")
    // D-091: ASCII case folding only; preserve every other UTF-16 unit verbatim.
    private fun fold(s: String) = s.map { if (it in 'A'..'Z') it + 32 else it }.joinToString("")
    fun key(m: Member): String {
        val stored = m.sortKey
        val position = if (stored?.startsWith(PREFIX) == true && stored.length > PREFIX.length &&
            stored.drop(PREFIX.length).all { it.code in 32..126 } && !stored.endsWith(' ')) stored.drop(PREFIX.length)
            else hex(stored ?: fold(m.shownName))
        return "$position!${hex(m.id)}"
    }
    fun ordered(members: List<Member>): List<Member> {
        val keys = members.associate { it.id to key(it) }
        return members.sortedWith(compareBy<Member> { keys.getValue(it.id) }.thenBy { it.id })
    }
    fun eligible(members: List<Member>, account: String?, archived: Boolean = false): List<Member> =
        ordered(members.filter { it.archived == archived && (it.createdByAccountId == null || it.createdByAccountId == account) })

    internal fun between(lower: String?, upper: String?): String {
        require(lower == null || upper == null || lower < upper)
        val out = StringBuilder()
        var high = upper
        var i = 0
        while (true) {
            val a = lower?.getOrNull(i)?.code ?: 32
            val b = high?.getOrNull(i)?.code ?: 126
            if (b - a > 1) return out.append(((a + b) / 2).toChar()).toString()
            out.append(a.toChar())
            if (a < b) high = null
            i++
        }
    }

    /** Neighbours are resolved against the latest full eligible list, never a filtered index. */
    fun move(members: List<Member>, account: String?, id: String, beforeId: String?): String? {
        val list = eligible(members, account)
        val from = list.indexOfFirst { it.id == id }
        if (from < 0 || beforeId == id) return null
        val remaining = list.filter { it.id != id }
        val to = if (beforeId == null) remaining.size else remaining.indexOfFirst { it.id == beforeId }
        if (to < 0) return null
        if (remaining.toMutableList().apply { add(to, list[from]) }.map { it.id } == list.map { it.id }) return null
        return PREFIX + between(remaining.getOrNull(to - 1)?.let(::key), remaining.getOrNull(to)?.let(::key))
    }

    /** Plan at Apply only. The drag preview and cancelled gestures never write operations. */
    fun patches(members: List<Member>, account: String?, target: List<String>, moved: Set<String>): List<Pair<String, String>> {
        val eligible = eligible(members, account)
        require(target.size == eligible.size && target.toSet() == eligible.map { it.id }.toSet()) { "Members changed. Cancel and reopen Reorder." }
        var current = members
        val out = mutableListOf<Pair<String, String>>()
        for (index in target.indices.reversed()) {
            val id = target[index]
            if (id !in moved) continue
            val key = move(current, account, id, target.getOrNull(index + 1)) ?: continue
            out.add(id to key)
            current = current.map { if (it.id == id) it.copy(sortKey = key) else it }
        }
        return out
    }
}
