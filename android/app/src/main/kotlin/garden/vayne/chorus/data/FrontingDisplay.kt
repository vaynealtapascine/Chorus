package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject

/** A custom state that can front like a member ("Blurry", "Dissociated"; ENTITIES §2). */
data class CustomState(val id: String, val name: String, val description: String?, val color: String?, val icon: String?)

/** What a member is up to while here ("Tired", or their own words; F3.10). One account pref per
 * member, `member_status:<id>` = `{text, at}`; cleared with null. */
data class MemberStatus(val text: String, val at: Long)

/**
 * How Fronting shows each front (Figma F3.1, F3.4, F3.9, F3.11; D-122). One account pref,
 * `fronting.display`, shared by the system's devices like `chat_folders`. Display only: layer
 * meaning, order and names stay in `front.layer`/`front.define` (D-080), and nothing here
 * changes who counts for what.
 */
object FrontingDisplay {
    const val PREF = "fronting.display"
    const val STATUS = "member_status:"
    /** The pseudo-layer of everyone not in a front. Never a real layer ID (those are system-local). */
    const val AWAY = "away"

    enum class Style(val key: String, val label: String) {
        Solid("solid", "Solid"), Dashed("dashed", "Dashed"), Faint("faint", "Faint"), Plain("plain", "Plain");
        companion object { fun of(key: String?): Style? = entries.firstOrNull { it.key == key } }
    }

    enum class Preset(val key: String, val label: String, val about: String) {
        Simple("simple", "Simple", "Front and\nco-conscious"),
        Detailed("detailed", "Detailed", "Layers + custom\nfront statuses"),
        Complete("complete", "Complete", "All layers, status, & front notes");
        companion object { fun of(key: String?): Preset? = entries.firstOrNull { it.key == key } }
    }

    /** Front icons a system can pick; the key is also the web icon name. */
    val ICONS = listOf("target", "message", "book", "briefcase", "monitor", "layers", "globe")

    /** New-front templates (F3.4) and the icon and blurb each starts with. */
    /** `followsBody`: the template starts by following Body (Figma F3.4's Chorus front). */
    data class Template(val name: String, val icon: String, val about: String, val followsBody: Boolean = false)
    val TEMPLATES = listOf(
        Template("Online", "message", "Who can post in chats. Follows Body until changed by hand.", followsBody = true),
        Template("Work", "briefcase", "Who handles work."),
        Template("Inner world", "book", "Inner world. Layers are rooms you named yourselves."),
        Template("Subsystem", "layers", "A subsystem's own front."),
    )

    data class LayerLook(val style: Style?, val about: String?, val separate: Boolean, val color: String?)

    data class FrontView(
        val icon: String? = null, val about: String? = null, val hidden: Boolean = false,
        val preset: Preset = Preset.Detailed, val hiddenLayers: Set<String> = emptySet(),
        val hideEmpty: Boolean = true, val collapseAway: Boolean = true, val showTime: Boolean = true,
        val showOthers: Boolean = true,
    )

    data class Settings(
        val layers: Map<String, LayerLook> = emptyMap(),
        val fronts: Map<String, FrontView> = emptyMap(),
        val order: List<String> = emptyList(),
        val hintDismissed: Boolean = false,
    ) {
        fun front(id: String): FrontView = fronts[id] ?: FrontView()
        fun layer(id: String): LayerLook = layers[id] ?: LayerLook(null, null, false, null)
    }

    private fun str(o: JSONObject, key: String): String? = o.optString(key, "").takeIf { o.has(key) && !o.isNull(key) && it.isNotBlank() }

    fun parse(value: Any?): Settings {
        val o = value as? JSONObject ?: return Settings()
        val layers = o.optJSONObject("layers")?.let { l -> l.keys().asSequence().mapNotNull { id ->
            val v = l.optJSONObject(id) ?: return@mapNotNull null
            id to LayerLook(Style.of(str(v, "style")), str(v, "about"), v.optBoolean("separate"),
                str(v, "color")?.takeIf { Regex("#[0-9A-Fa-f]{6}").matches(it) })
        }.toMap() }.orEmpty()
        val fronts = o.optJSONObject("fronts")?.let { f -> f.keys().asSequence().mapNotNull { id ->
            val v = f.optJSONObject(id) ?: return@mapNotNull null
            val d = FrontView()
            id to FrontView(str(v, "icon")?.takeIf { it in ICONS }, str(v, "about"), v.optBoolean("hidden"),
                Preset.of(str(v, "preset")) ?: d.preset, strings(v.optJSONArray("hidden_layers")).toSet(),
                v.optBoolean("hide_empty", d.hideEmpty), v.optBoolean("collapse_away", d.collapseAway),
                v.optBoolean("show_time", d.showTime), v.optBoolean("show_others", d.showOthers))
        }.toMap() }.orEmpty()
        return Settings(layers, fronts, strings(o.optJSONArray("order")).distinct(), o.optBoolean("hint_dismissed"))
    }

    private fun strings(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) }

    fun toJson(s: Settings): JSONObject = JSONObject()
        .put("layers", JSONObject().also { o -> s.layers.forEach { (id, l) -> o.put(id, JSONObject()
            .also { v -> l.style?.let { v.put("style", it.key) } }.also { v -> l.about?.let { v.put("about", it) } }
            .also { v -> if (l.separate) v.put("separate", true) }.also { v -> l.color?.let { v.put("color", it) } }) } })
        .put("fronts", JSONObject().also { o -> s.fronts.forEach { (id, f) -> o.put(id, JSONObject()
            .also { v -> f.icon?.let { v.put("icon", it) } }.also { v -> f.about?.let { v.put("about", it) } }
            .put("hidden", f.hidden).put("preset", f.preset.key).put("hidden_layers", JSONArray(f.hiddenLayers.sorted()))
            .put("hide_empty", f.hideEmpty).put("collapse_away", f.collapseAway).put("show_time", f.showTime)
            .put("show_others", f.showOthers)) } })
        .put("order", JSONArray(s.order))
        .put("hint_dismissed", s.hintDismissed)

    fun payload(s: Settings): JSONObject = AccountPrefs.payload(PREF, toJson(s))

    fun statusPayload(memberId: String, text: String?, at: Long): JSONObject =
        AccountPrefs.payload(STATUS + memberId, text?.trim()?.takeIf { it.isNotEmpty() }?.let { JSONObject().put("text", it).put("at", at) } ?: JSONObject.NULL)

    fun status(value: Any?): MemberStatus? = (value as? JSONObject)?.let { o ->
        str(o, "text")?.let { MemberStatus(it.take(80), o.optLong("at")) }
    }

    /** The default look of the layer at `index` among the nested (not separately listed) ones. */
    fun defaultStyle(index: Int): Style = when (index) { 0 -> Style.Solid; 1 -> Style.Dashed; else -> Style.Faint }

    /** The built-in descriptions of the three original layers (F3.9); custom layers have none. */
    fun defaultAbout(layerId: String): String? = when (layerId) {
        "front" -> "In control · counts as fronting"
        "cocon" -> "Present · counts as co-fronting"
        "present" -> "Around · shown, not counted"
        else -> null
    }

    /** The short line under a layer's name on the front card: the first part of its description. */
    fun shortAbout(about: String?): String? = about?.substringBefore(" · ")?.trim()?.takeIf { it.isNotEmpty() }
        ?.replaceFirstChar { it.lowercase() }

    fun withFront(s: Settings, id: String, f: (FrontView) -> FrontView) = s.copy(fronts = s.fronts + (id to f(s.front(id))))
    fun withLayer(s: Settings, id: String, f: (LayerLook) -> LayerLook) = s.copy(layers = s.layers + (id to f(s.layer(id))))

    /** Fronts in the system's chosen order: arranged ones first, then Body, then by name. */
    fun ordered(fronts: List<NamedFront>, bodyId: String, s: Settings): List<NamedFront> {
        val rank = s.order.withIndex().associate { (i, id) -> id to i }
        return fronts.sortedWith(compareBy<NamedFront> { rank[it.id] ?: Int.MAX_VALUE }
            .thenBy { it.id != bodyId }.thenBy { it.name.lowercase() }.thenBy { it.id })
    }

    /** The icon a front shows: its own, else Body's target, else a template's by name, else layers. */
    fun icon(frontId: String, name: String, bodyId: String, s: Settings): String = s.front(frontId).icon
        ?: if (frontId == bodyId) "target" else TEMPLATES.firstOrNull { it.name.equals(name, true) }?.icon ?: "layers"

    /** "1h 24m", "32m", "just now". */
    fun duration(ms: Long): String {
        val m = (ms / 60_000).coerceAtLeast(0)
        return when {
            m < 1 -> "now"
            m < 60 -> "${m}m"
            m < 24 * 60 -> "${m / 60}h ${m % 60}m"
            else -> "${m / (24 * 60)}d ${(m / 60) % 24}h"
        }
    }
}
