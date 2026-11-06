package garden.vayne.chorus.data

/** One line of Front history's list (F3.7): "10:48 · Amarynthe joined Foxglove · Steering". */
data class StoryLine(val changeId: String, val at: Long, val subjectType: String, val subjectId: String, val text: String, val layerId: String?)

/** One state of a front: when it began and who was where. */
data class FrontState(val id: String, val at: Long, val entries: List<Entry>)

/**
 * Turns successive front states into plain sentences. Layer order decides inward/outward and
 * which layer is the front itself; names come from `name`. Nothing is inferred from a layer's name.
 */
object FrontStory {
    fun lines(states: List<FrontState>, order: List<String>, outermost: String?, layerName: (String) -> String,
        name: (String, String) -> String): List<StoryLine> {
        val inner = order.firstOrNull()
        val out = ArrayList<StoryLine>()
        var prev = emptyList<Entry>()
        for (s in states.sortedBy { it.at }) {
            val before = prev.associateBy { it.subjectType to it.subjectId }
            val after = s.entries.associateBy { it.subjectType to it.subjectId }
            val leftFront = prev.filter { it.level == inner && after[it.subjectType to it.subjectId]?.level != inner }
            val stayedFront = s.entries.filter { it.level == inner && before[it.subjectType to it.subjectId]?.level == inner }
            val mentioned = HashSet<Pair<String, String>>()
            for (e in s.entries) {
                val key = e.subjectType to e.subjectId
                val was = before[key]
                val who = name(e.subjectType, e.subjectId)
                val text = when {
                    was?.level == e.level -> null
                    e.level == inner && leftFront.isNotEmpty() && was == null -> {
                        val gone = leftFront.first(); mentioned += gone.subjectType to gone.subjectId
                        "$who took over from ${name(gone.subjectType, gone.subjectId)}"
                    }
                    e.level == inner && was == null && stayedFront.isNotEmpty() ->
                        "$who joined ${name(stayedFront.first().subjectType, stayedFront.first().subjectId)}"
                    was == null && e.level == inner -> "$who started fronting"
                    was == null && e.level == outermost -> "$who came close"
                    was == null -> "$who is ${layerName(e.level).lowercase()}"
                    else -> {
                        val from = order.indexOf(was.level).let { if (it < 0) Int.MAX_VALUE else it }
                        val to = order.indexOf(e.level).let { if (it < 0) Int.MAX_VALUE else it }
                        if (to < from) "$who moved inward" else "$who moved outward"
                    }
                } ?: continue
                out += StoryLine(s.id, s.at, e.subjectType, e.subjectId, text, e.level)
            }
            for (e in prev) {
                val key = e.subjectType to e.subjectId
                if (key in after || key in mentioned) continue
                out += StoryLine(s.id, s.at, e.subjectType, e.subjectId, "${name(e.subjectType, e.subjectId)} left", null)
            }
            prev = s.entries
        }
        return out
    }
}
