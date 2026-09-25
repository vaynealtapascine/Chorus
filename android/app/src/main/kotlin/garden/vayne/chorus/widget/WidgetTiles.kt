package garden.vayne.chorus.widget

import garden.vayne.chorus.data.Model

/** One cell of the widget grid. */
internal sealed interface WidgetTile {
    /** A member, or a subsystem switched in as a unit ("Whole subsystem"). */
    data class Subject(val type: String, val id: String, val name: String, val color: String, val glyph: String) : WidgetTile
    data class Folder(val id: String, val name: String, val color: String, val count: Int) : WidgetTile
}

/**
 * What the grid shows (CLIENTS.md §3.1). Root: pins, recents, then top-level subsystem folders, then
 * everyone else A–Z. Folder: "Whole subsystem" first, sub-folders, then its members by most recent
 * front. Pure, so it is unit-tested without a device.
 */
internal fun widgetTiles(model: Model, folder: String?, recentLimit: Int = 8,
    pinned: List<String> = emptyList()): List<WidgetTile> {
    if (model.isPerson) return emptyList()
    val subsystems = model.groups.filter { it.isSubsystem }
    val active = model.active
    val recent = model.recents(Int.MAX_VALUE)
    fun count(id: String) = model.membership[id]?.size ?: 0
    fun member(m: garden.vayne.chorus.data.Member) = WidgetTile.Subject("member", m.id, m.shownName, m.color, m.glyph)

    if (folder != null) {
        val g = model.group(folder) ?: return widgetTiles(model, null, recentLimit, pinned)
        val inside = model.membership[folder].orEmpty()
        val rank = { id: String -> recent.indexOf(id).let { if (it < 0) Int.MAX_VALUE else it } }
        return listOf<WidgetTile>(WidgetTile.Subject("group", g.id, "All of ${g.name}", g.color ?: "#A09184", "◌")) +
            subsystems.filter { it.parentId == folder }.map { WidgetTile.Folder(it.id, it.name, it.color ?: "#A09184", count(it.id)) } +
            active.filter { it.id in inside }.sortedWith(compareBy({ rank(it.id) }, { it.shownName.lowercase() })).map(::member)
    }
    val byId = active.associateBy { it.id }
    val pins = pinned.distinct().mapNotNull { byId[it] }
    val pinnedIds = pins.map { it.id }.toSet()
    val first = recent.filter { it !in pinnedIds }.take(recentLimit).mapNotNull { byId[it] }
    val firstIds = first.map { it.id }.toSet() + pinnedIds
    return (pins + first).map(::member) +
        subsystems.filter { it.parentId == null }.map { WidgetTile.Folder(it.id, it.name, it.color ?: "#A09184", count(it.id)) } +
        active.filter { it.id !in firstIds }.map(::member)
}
