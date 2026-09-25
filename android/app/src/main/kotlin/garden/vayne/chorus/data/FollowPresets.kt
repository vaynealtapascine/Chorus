package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject
import uniffi.chorus_ffi.notifyPreset

/** Basic sharing choices are produced by chorus_core, as on the web. */
object FollowPresets {
    val choices = listOf("inherit", "close", "gentle", "private", "digest", "off")

    fun ceiling(choice: String, previous: JSONObject = JSONObject(), core: (String) -> String = { notifyPreset(it) }): JSONObject {
        require(choice in choices)
        val next = if (choice == "inherit") JSONObject() else JSONObject(core(choice))
        for (key in listOf("share_history", "share_stats")) {
            if (previous.has(key) && !previous.isNull(key)) next.put(key, previous.get(key))
        }
        return next
    }

    fun choiceOf(override: JSONObject, core: (String) -> String = { notifyPreset(it) }): String {
        val base = JSONObject(override.toString())
        base.remove("share_history")
        base.remove("share_stats")
        if (base.length() == 0) return "inherit"
        return choices.drop(1).firstOrNull { canonical(base) == canonical(JSONObject(core(it))) } ?: "custom"
    }

    /** Advanced follower permissions remain separate from the Basic notification preset. */
    fun withSharing(previous: JSONObject, key: String, enabled: Boolean): JSONObject {
        require(key == "share_history" || key == "share_stats")
        return JSONObject(previous.toString()).put(key, enabled)
    }

    private fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
            "${JSONObject.quote(it)}:${canonical(value.get(it))}"
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
}
