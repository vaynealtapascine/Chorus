package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject
import uniffi.chorus_ffi.stagePlan

/** The core decides selection, context folds, reply bars, names and time overrides (SPEC §7). */
object StagePlan {
    data class Settings(
        val selected: Set<String> = emptySet(),
        val unselected: String = "context",
        val redactNames: Boolean = false,
        val fakeNames: Map<String, String> = emptyMap(),
        val timeMode: String = "real",
        val shiftMinutes: Int = 0,
    )

    data class Row(val id: String?, val selected: Boolean, val at: Long?, val replyShown: Boolean, val contextCount: Int)
    data class Result(val rows: List<Row>, val names: Map<String, String>)

    fun forMessages(messages: List<ChatMessage>, settings: Settings,
        planner: (String, String) -> String = ::stagePlan): Result {
        val items = JSONArray()
        for (message in messages) items.put(JSONObject()
            .put("id", message.id).put("authors", JSONArray(message.authors))
            .put("at", message.occurredAt).put("reply_to", message.replyTo ?: JSONObject.NULL))
        val names = JSONObject()
        for ((id, label) in settings.fakeNames) if (label.isNotBlank())
            names.put(id, JSONObject().put("label", label.trim()))
        val time = when (settings.timeMode) {
            "hide" -> JSONObject().put("mode", "hide")
            "shift" -> JSONObject().put("mode", "shift").put("offset_ms", settings.shiftMinutes.toLong() * 60_000)
            else -> JSONObject().put("mode", "real")
        }
        val definition = JSONObject()
            .put("selected", JSONArray(settings.selected.toList()))
            .put("unselected", settings.unselected)
            .put("redact_names", settings.redactNames)
            .put("fake_names", names).put("time", time)
        val plan = JSONObject(planner(items.toString(), definition.toString()))
        val rows = plan.getJSONArray("rows")
        val resultRows = (0 until rows.length()).map { n ->
            val row = rows.getJSONObject(n)
            if (row.getString("kind") == "context") Row(null, false, null, false, row.getInt("count"))
            else Row(row.getString("id"), row.getBoolean("selected"),
                row.optLong("at").takeUnless { row.isNull("at") }, row.getBoolean("reply_shown"), 0)
        }
        val resultNames = plan.getJSONObject("names")
        return Result(resultRows, resultNames.keys().asSequence().associateWith { resultNames.getJSONObject(it).getString("label") })
    }
}
