package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * The words a system uses for itself and for being in control (F1.2, D-004; `system.terminology`,
 * DATA_MODEL §4.3): `system` is ["system", "systems"] or the chosen word, `front` is
 * ["front", "fronting"] or the chosen pair. Shown lowercase inside sentences; [cap] starts one.
 */
data class Words(val system: String = "system", val systems: String = "systems", val front: String = "front",
    val fronting: String = "fronting") {
    companion object {
        val Default = Words()

        /** "We call ourselves" (F1.2): the word and its plural. */
        val SYSTEM_CHOICES = listOf("system" to "systems", "collective" to "collectives", "household" to "households",
            "home" to "homes", "family" to "families")

        /** "When someone is in control, they are" (F1.2): what they are, and the thing they're in. */
        val FRONT_CHOICES = listOf("fronting" to "front", "present" to "presence", "out" to "out", "steering" to "steer")

        fun parse(terminology: JSONObject?): Words {
            val t = terminology ?: return Default
            fun pair(key: String): Pair<String, String>? = t.optJSONArray(key)?.let { a ->
                val one = a.optString(0).trim().lowercase()
                if (one.isEmpty()) null else one to a.optString(1).trim().lowercase().ifEmpty { one }
            }
            val (system, systems) = pair("system") ?: ("system" to "systems")
            val (front, fronting) = pair("front") ?: ("front" to "fronting")
            return Words(system, systems, front, fronting)
        }

        /** [base] (the stored terminology, other words kept) with these two choices. */
        fun payload(base: JSONObject?, words: Words): JSONObject = JSONObject(base?.toString() ?: "{}")
            .put("system", JSONArray().put(words.system).put(words.systems))
            .put("front", JSONArray().put(words.front).put(words.fronting))

        /** A word the person typed for "We call ourselves": one word, its plural made the plain English way. */
        fun customSystem(word: String): Pair<String, String>? {
            val w = word.trim().lowercase().take(24).ifEmpty { return null }
            val plural = when {
                w.endsWith("s") || w.endsWith("x") || w.endsWith("ch") || w.endsWith("sh") -> "${w}es"
                w.endsWith("y") && w.length > 1 && w[w.length - 2] !in "aeiou" -> "${w.dropLast(1)}ies"
                else -> "${w}s"
            }
            return w to plural
        }

        /** A typed word for being in control ("driving"): what they are, and the same word for the thing they're in. */
        fun customFront(word: String): Pair<String, String>? {
            val w = word.trim().lowercase().take(24).ifEmpty { return null }
            return w to w
        }
    }
}

/** "Fronting" from "fronting". */
fun String.cap(): String = replaceFirstChar { it.uppercase() }
