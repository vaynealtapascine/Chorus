package garden.vayne.chorus.widget

import garden.vayne.chorus.data.Model

/** One cell of the widget grid. */
internal sealed interface WidgetTile {
    /** A member, or a subsystem switched in as a unit ("Whole subsystem"). */
    data class Subject(val type: String, val id: String, val name: String, val color: String, val glyph: String) : WidgetTile
    data class Folder(val id: String, val name: String, val color: String, val count: Int) : WidgetTile
}

internal data class WidgetScope(val kind: String = "all", val id: String = "")

/** Keep a widget inside its configured subsystem, even if an old folder is still stored. */
internal fun widgetFolder(model: Model, scope: WidgetScope, open: String?): String? {
    return when (scope.kind) {
    "group" -> null
    "subsystem" -> {
        val root = model.group(scope.id)?.takeIf { it.isSubsystem } ?: return null
        var cursor = open?.let(model::group)
        val seen = HashSet<String>()
        while (cursor != null && seen.add(cursor.id)) {
            if (cursor.id == root.id) return open
            cursor = cursor.parentId?.let(model::group)
        }
        root.id
    }
    else -> open
    }
}

/**
 * What the grid shows (CLIENTS.md §3.1). Root: pins, recents, then top-level subsystem folders, then
 * everyone else A–Z. Folder: "Whole subsystem" first, sub-folders, then its members by most recent
 * front. Pure, so it is unit-tested without a device.
 */
internal fun widgetTiles(model: Model, folder: String?, recentLimit: Int = 8,
    pinned: List<String> = emptyList(), accountId: String? = null,
    scope: WidgetScope = WidgetScope()): List<WidgetTile> {
    if (model.isPerson) return emptyList()
    val subsystems = model.groups.filter { it.isSubsystem }
    if (scope.kind == "group" && model.group(scope.id)?.kind != "group") return emptyList()
    if (scope.kind == "subsystem" && model.group(scope.id)?.isSubsystem != true) return emptyList()
    val active = model.active.filter { (it.createdByAccountId == null || it.createdByAccountId == accountId) &&
        (scope.kind != "group" || it.id in model.membership[scope.id].orEmpty()) }
    val recent = model.recents(Int.MAX_VALUE)
    fun count(id: String) = model.membership[id]?.size ?: 0
    fun member(m: garden.vayne.chorus.data.Member) = WidgetTile.Subject("member", m.id, m.shownName, m.color, m.glyph)

    val scopedFolder = widgetFolder(model, scope, folder)
    if (scopedFolder != null) {
        val g = model.group(scopedFolder) ?: return widgetTiles(model, null, recentLimit, pinned, accountId, scope)
        val inside = model.membership[scopedFolder].orEmpty()
        val rank = { id: String -> recent.indexOf(id).let { if (it < 0) Int.MAX_VALUE else it } }
        return listOf<WidgetTile>(WidgetTile.Subject("group", g.id, "All of ${g.name}", g.color ?: "#A09184", "◌")) +
            subsystems.filter { it.parentId == scopedFolder }.map { WidgetTile.Folder(it.id, it.name, it.color ?: "#A09184", count(it.id)) } +
            active.filter { it.id in inside }.sortedWith(compareBy({ rank(it.id) }, { it.shownName.lowercase() })).map(::member)
    }
    val byId = active.associateBy { it.id }
    val pins = pinned.distinct().mapNotNull { byId[it] }
    val pinnedIds = pins.map { it.id }.toSet()
    val first = recent.filter { it !in pinnedIds }.take(recentLimit).mapNotNull { byId[it] }
    val firstIds = first.map { it.id }.toSet() + pinnedIds
    return (pins + first).map(::member) +
        (if (scope.kind == "all") subsystems.filter { it.parentId == null } else emptyList())
            .map { WidgetTile.Folder(it.id, it.name, it.color ?: "#A09184", count(it.id)) } +
        active.filter { it.id !in firstIds }.map(::member)
}
