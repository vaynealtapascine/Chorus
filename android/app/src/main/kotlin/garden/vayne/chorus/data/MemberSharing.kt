package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * The "Who can see a member" screen's model (UI21, Figma F4.3): the profile's choices, its parts
 * gathered as Figma groups them, and who hears when the member fronts. Each group writes every
 * part key it holds (D-101), so a group's pill is one audience only when all of them agree.
 */
object MemberSharing {
    /** A custom field, with the audience it has when a member's profile doesn't choose one. */
    data class Field(val id: String, val name: String, val default: Audience)

    data class PartGroup(val title: String, val subtitle: String?, val keys: List<String>)

    /** `everyone`, `nobody` or `buckets` with the chosen bucket ids (NOTIFICATIONS §2.4). */
    data class Announce(val mode: String, val buckets: List<String> = emptyList())

    fun partName(key: String): String = ProfileAudience.PARTS.firstOrNull { it.first == key }?.second ?: key

    fun partGroups(fields: List<Field>): List<PartGroup> = listOf(
        PartGroup("Name and picture", "Full name, banner", listOf("name", "banner")),
        PartGroup("Pronouns", null, listOf("pronouns")),
        PartGroup("Description and fields", (listOf("Bio", "Birthday") + fields.map { it.name }).joinToString(", "),
            listOf("description", "birthday") + fields.map { it.id }),
    )

    /** What a part reaches before the profile's own limit: its own audience, else the field's
     *  default, else the profile (the full name is private unless chosen, D-126). */
    fun effective(profile: Audience, parts: Any?, key: String, field: Field?): Audience =
        ProfileAudience.own(parts, key) ?: field?.default ?: if (key == "name") Audience.PRIVATE else profile

    /** The group's one audience as a label, or null when its parts differ. */
    fun summary(profile: Audience, parts: Any?, g: PartGroup, bucketNames: Map<String, String>, fields: List<Field> = emptyList()): String? {
        val each = g.keys.map { k -> effective(profile, parts, k, fields.find { it.id == k }) }
        return each.first().takeIf { a -> each.all { it.same(a) } }?.label(bucketNames)
    }

    /** Every part of the group follows its default. */
    fun inherits(parts: Any?, g: PartGroup): Boolean = g.keys.all { ProfileAudience.own(parts, it) == null }

    /** The audience every part of the group has chosen, when they all chose the same one. */
    fun summaryAudience(parts: Any?, g: PartGroup): Audience? {
        val own = g.keys.map { ProfileAudience.own(parts, it) }
        val first = own.first() ?: return null
        return first.takeIf { own.all { o -> o != null && o.same(first) } }
    }

    /** The parts map with every key of a group set to [a] (null: back to each one's default). */
    fun withAll(parts: Any?, keys: List<String>, a: Audience?): JSONObject =
        keys.fold(parts) { acc, k -> ProfileAudience.with(acc, k, a) } as? JSONObject ?: JSONObject()

    /** Only us, each bucket, Friends, Everyone here; and the current choice when it is none of them. */
    fun profileChoices(current: Audience, buckets: List<Pair<String, String>>): List<Audience> {
        val base = listOf(Audience.PRIVATE) + buckets.map { Audience("buckets", listOf(it.first)) } + Audience("friends")
        val live = current.live(buckets.map { it.first }.toSet())
        val extra = live.takeIf { c -> base.none { it.same(c) } && c.mode != "server" }
        return base + listOfNotNull(extra) + Audience("server")
    }

    /** "Kit, Ren" or "Kit, Ren +3"; nothing for nobody. */
    fun names(all: List<String>, shown: Int = 2): String? = when {
        all.isEmpty() -> null
        all.size > shown -> all.take(shown).joinToString(", ") + " +${all.size - shown}"
        else -> all.joinToString(", ")
    }

    /** Friends: active followers we follow back (D-129). */
    fun friendNames(f: FollowList): List<String> {
        val followed = f.following.filter { it.status == "active" }.map { it.account.id }.toSet()
        return f.followers.filter { it.status == "active" && it.account.id in followed }.map { it.account.shownName }.distinct()
    }

    /** The active followers assigned to any of [bucketIds] (assignments hold follower account ids). */
    fun bucketNames(f: FollowList, assignments: Map<String, Set<String>>, bucketIds: List<String>): List<String> {
        val ids = bucketIds.flatMap { assignments[it].orEmpty() }.toSet()
        return f.followers.filter { it.status == "active" && it.account.id in ids }.map { it.account.shownName }.distinct()
    }

    fun announce(policyText: String?): Announce {
        val v = policyText?.let { runCatching { JSONObject(it) }.getOrNull() }?.opt("announce")
        return when {
            v == "nobody" -> Announce("nobody")
            v is JSONObject -> Announce("buckets", v.optJSONArray("buckets")?.let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? String } }.orEmpty())
            else -> Announce("everyone")
        }
    }

    fun announceLabel(a: Announce, bucketNames: Map<String, String>): String = when (a.mode) {
        "nobody" -> "Nobody"
        "buckets" -> a.buckets.filter { it in bucketNames }.let { live ->
            when (live.size) { 0 -> "Nobody"; 1 -> bucketNames.getValue(live[0]); else -> "${live.size} buckets" }
        }
        else -> "Every follower"
    }

    /** The member's policy with `announce` replaced; its other keys are kept. */
    fun announcePolicy(policyText: String?, value: Any): JSONObject =
        (policyText?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()).put("announce", value)

    /** The custom fields shown in a profile, with their defaults, from the editor's field rows. */
    fun fields(rows: JSONArray, gone: Set<String>): List<Field> = (0 until rows.length()).mapNotNull { i ->
        val r = rows.getJSONObject(i)
        val id = r.getString("id")
        if (id in gone || !r.optBoolean("exists", true)) null
        else Field(id, r.getString("name"), ProfileAudience.of(r.optJSONObject("default_visibility")))
    }
}
