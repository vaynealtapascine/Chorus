package garden.vayne.chorus.data

/** Display-only profile lines (Figma F6.1): the "Steering · 2h 04m" front chip and the counts
 * under the name. Nothing here writes; the front itself still comes from core's projection. */
object ProfilePresentation {
    /** [layer] is the shown layer name ("Fronting", "Co-conscious" or a configured layer such as
     * "Steering"); [since] is when this member's current stretch began, when known. */
    data class FrontStatus(val layer: String, val since: Long?)

    data class Counts(val posts: Int, val entries: Int, val relationships: Int)

    /** Where [memberId] is in the body's confirmed front right now, or null when they aren't. */
    fun frontStatus(model: Model, memberId: String): FrontStatus? {
        val entry = ChatPresentation.presence(model, null)
            .filter { it.subjectType == "member" && it.subjectId == memberId }
            .minByOrNull { layerRank(model, it.level) } ?: return null
        val layer = ChatPresentation.layer(model, entry) ?: return null
        val since = model.frontIntervals
            .filter { it.subjectType == "member" && it.subjectId == memberId && it.endAt == null }
            .minOfOrNull { it.startAt } ?: model.since
        return FrontStatus(layer.replaceFirstChar { it.titlecase() }, since)
    }

    /** Innermost first: a member both fronting and nearby reads as fronting. */
    private fun layerRank(model: Model, level: String): Int = model.namedFronts?.liveLayers?.indexOfFirst { it.id == level }
        ?.takeIf { it >= 0 } ?: when (level) { "front" -> 0; "cocon" -> 1; "present" -> 2; else -> 3 }

    /** "just now", "45m", "2h 04m", "3d 2h": how long a front stretch has run. */
    fun duration(ms: Long): String {
        val minutes = (ms / 60_000).coerceAtLeast(0)
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "${minutes}m"
            minutes < 24 * 60 -> "${minutes / 60}h ${"%02d".format(minutes % 60)}m"
            else -> "${minutes / (24 * 60)}d ${(minutes % (24 * 60)) / 60}h"
        }
    }

    /** The chip under the bio saying who can open this profile. */
    fun audienceLine(mode: String, person: Boolean): String = when (mode) {
        "private" -> if (person) "Only you see this profile" else "Only our system sees this profile"
        "followers" -> "Followers see this profile"
        "server" -> "Everyone on the server sees this profile"
        else -> "Chosen people see this profile"
    }

    /** Posts (notes and replies, not reposts), journal entries, and relationships this member keeps. */
    fun counts(model: Model, memberId: String): Counts {
        val own = model.posts.filter { memberId in it.authors && it.repostOf == null }
        return Counts(own.count { it.kind != "entry" }, own.count { it.kind == "entry" },
            model.relationships.count { it.fromMemberId == memberId })
    }
}
