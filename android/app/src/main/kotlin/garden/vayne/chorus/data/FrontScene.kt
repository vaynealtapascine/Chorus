package garden.vayne.chorus.data

import garden.vayne.chorus.data.FrontingDisplay.Style

/** One layer as Fronting draws it: nested in the containment, or listed separately (F3.1). */
data class SceneLayer(val id: String, val name: String, val about: String?, val style: Style, val color: String?,
    val separate: Boolean, val hidden: Boolean, val entries: List<Entry>)

/**
 * What one front looks like right now, from its confirmed presence and the display pref. Layer
 * meaning stays with the core: this only sorts entries into the boxes they are shown in.
 */
data class FrontScene(
    val frontId: String,
    /** Nested layers, inner first; hidden ones are left out. */
    val nested: List<SceneLayer>,
    /** Layers the system lists separately, in layer order; hidden ones are left out. */
    val separate: List<SceneLayer>,
    /** Every live layer, nested and separate, inner first, hidden included (for pickers). */
    val all: List<SceneLayer>,
    /** Entries on a layer this device doesn't know (never invented into a known one). */
    val unlisted: List<Entry>,
    /** Own active members not in this front. */
    val away: List<Member>,
    val awayShown: Boolean,
    val here: Int,
) {
    val innermost: SceneLayer? get() = nested.firstOrNull() ?: all.firstOrNull { !it.separate }

    companion object {
        /** The three original layers, inner to outer, for an unconfigured account. */
        val LEGACY = listOf(FrontLayer("front", "Fronting", 0, false), FrontLayer("cocon", "Co-conscious", 1, false),
            FrontLayer("present", "Present", 2, false))

        fun layers(model: Model): List<FrontLayer> = model.namedFronts?.liveLayers?.ifEmpty { null } ?: LEGACY

        fun build(model: Model, frontId: String, entries: List<Entry>, accountId: String?): FrontScene {
            val display = model.accountPrefs.frontingDisplay
            val view = display.front(frontId)
            var nestedIndex = 0
            val all = layers(model).map { l ->
                val look = display.layer(l.id)
                val style = look.style ?: if (look.separate) Style.Plain else FrontingDisplay.defaultStyle(nestedIndex)
                if (!look.separate) nestedIndex++
                SceneLayer(l.id, l.name, look.about ?: FrontingDisplay.defaultAbout(l.id), style, look.color, look.separate,
                    l.id in view.hiddenLayers, entries.filter { it.level == l.id })
            }
            val known = all.map { it.id }.toSet()
            val present = entries.filter { it.subjectType == "member" }.map { it.subjectId }.toSet()
            val away = model.active.filter { (it.createdByAccountId == null || it.createdByAccountId == accountId) && it.id !in present }
            return FrontScene(frontId, all.filter { !it.separate && !it.hidden }, all.filter { it.separate && !it.hidden }, all,
                entries.filter { it.level !in known }, away, FrontingDisplay.AWAY !in view.hiddenLayers, entries.size)
        }
    }
}
