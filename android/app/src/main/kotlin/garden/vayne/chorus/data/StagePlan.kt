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
        val onlyMembers: Set<String> = emptySet(),
        val replyDepth: Int? = null,
        val blurAttachments: Boolean = false,
        val hideHeader: Boolean = false,
        val hideReplyBars: Boolean = false,
        val style: String = "chorus",
        val blurAvatars: Boolean = false,
    )

    data class Row(val id: String?, val selected: Boolean, val at: Long?, val replyShown: Boolean, val contextCount: Int)
    data class Result(val rows: List<Row>, val names: Map<String, String>)

    /** A reply bar must not reveal the body of a parent behind its content warning. */
    fun replyPreview(parent: ChatMessage, names: Map<String, String>, realName: (String) -> String): String {
        val authors = parent.authors.map { names[it] ?: realName(it) }.joinToString(" & ").ifBlank { "Someone" }
        val summary = parent.cw?.let { "Content warning: $it" }
            ?: parent.text.replace('\n', ' ').trim().take(60)
        return "↪ $authors: $summary"
    }

    /** Save a definition that the web Stage can load without translating it. */
    fun definition(channelId: String, settings: Settings): JSONObject {
        val fakeNames = JSONObject()
        for ((id, label) in settings.fakeNames) if (label.isNotBlank())
            fakeNames.put(id, JSONObject().put("label", label.trim()))
        val time = when (settings.timeMode) {
            "hide" -> JSONObject().put("mode", "hide")
            "shift" -> JSONObject().put("mode", "shift").put("offset_ms", settings.shiftMinutes.toLong() * 60_000)
            else -> JSONObject().put("mode", "real")
        }
        return JSONObject().put("channel_id", channelId)
            .put("selected", JSONArray(settings.selected.toList()))
            .put("unselected", settings.unselected)
            .put("only_members", if (settings.onlyMembers.isEmpty()) JSONObject.NULL else JSONArray(settings.onlyMembers.toList()))
            .put("reply_depth", settings.replyDepth ?: JSONObject.NULL)
            .put("redact_names", settings.redactNames)
            .put("fake_names", fakeNames).put("time", time)
            .put("render", JSONObject().put("style", settings.style).put("blur_avatars", settings.blurAvatars)
                .put("blur_attachments", settings.blurAttachments)
                .put("hide_header", settings.hideHeader).put("hide_reply_bars", settings.hideReplyBars))
    }

    /** Reject richer saved views until Android can render them faithfully. */
    fun supported(definition: JSONObject): Settings? {
        val replyDepth = definition.optInt("reply_depth").takeUnless { definition.isNull("reply_depth") }
        if (replyDepth != null && replyDepth !in 0..20) return null
        val onlyMembers = definition.optJSONArray("only_members")?.let { a ->
            (0 until a.length()).mapNotNull { n -> a.optString(n).takeIf { it.isNotBlank() } }.toSet()
        }.orEmpty()
        val render = definition.optJSONObject("render")
        if (render != null && (render.optString("style", "chorus") !in setOf("chorus", "discord", "card", "transcript", "minimal") ||
                render.optString("theme", "auto") != "auto" || render.optString("width", "phone") != "phone")) return null
        val mode = definition.optString("unselected", "context")
        if (mode !in setOf("context", "hidden", "visible")) return null
        val time = definition.optJSONObject("time")
        val timeMode = time?.optString("mode", "real") ?: "real"
        if (timeMode !in setOf("real", "hide", "shift")) return null
        val offset = time?.optLong("offset_ms") ?: 0L
        if (timeMode == "shift" && (offset % 60_000 != 0L || offset / 60_000 < Int.MIN_VALUE.toLong() ||
                offset / 60_000 > Int.MAX_VALUE.toLong())) return null
        val fakeNames = definition.optJSONObject("fake_names")
        val labels = fakeNames?.keys()?.asSequence()?.associateWith { id ->
            val fake = fakeNames.optJSONObject(id) ?: return null
            if (!fake.isNull("color")) return null
            fake.optString("label")
        }.orEmpty()
        val selected = definition.optJSONArray("selected")?.let { a ->
            (0 until a.length()).mapNotNull { n -> a.optString(n).takeIf { it.isNotBlank() } }.toSet()
        }.orEmpty()
        return Settings(selected, mode, definition.optBoolean("redact_names"), labels,
            timeMode, (offset / 60_000).toInt(), onlyMembers, replyDepth,
            render?.optBoolean("blur_attachments") == true,
            render?.optBoolean("hide_header") == true, render?.optBoolean("hide_reply_bars") == true,
            render?.optString("style", "chorus") ?: "chorus", render?.optBoolean("blur_avatars") == true)
    }

    fun forMessages(messages: List<ChatMessage>, settings: Settings,
        planner: (String, String) -> String = ::stagePlan): Result {
        val items = JSONArray()
        for (message in messages) items.put(JSONObject()
            .put("id", message.id).put("authors", JSONArray(message.authors))
            .put("at", message.occurredAt).put("reply_to", message.replyTo ?: JSONObject.NULL))
        val definition = definition("", settings)
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
