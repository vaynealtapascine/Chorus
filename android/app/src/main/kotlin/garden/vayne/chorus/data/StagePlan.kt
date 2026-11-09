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
        val startAt: Long = 0L,
        val theme: String = "auto",
        val hideReactions: Boolean = false,
        val hideThreadPreviews: Boolean = false,
        val hideDates: Boolean = false,
        /** "phone", "square" or "wide" (the web's 390, 600 and 820 px). */
        val width: String = "phone",
        /** A custom theme's `bg`, `ink` and `accent`; core derives the rest. */
        val palette: Map<String, String> = emptyMap(),
        /** A fake name's colour, by member. */
        val fakeColors: Map<String, String> = emptyMap(),
        /** Time mode "manual" (By hand): each listed message's shown time. */
        val manualAt: Map<String, Long> = emptyMap(),
        /** Blacked-out `[start, end)` ranges of each message's text, in UTF-16 units. */
        val blackout: Map<String, List<IntRange>> = emptyMap(),
    )

    /** One planned row: a message ([id]) or, with [id] null, [contextIds] folded into one line. */
    data class Row(val id: String?, val selected: Boolean, val at: Long?, val replyShown: Boolean, val contextCount: Int,
        val contextIds: List<String> = emptyList(), val blackout: List<IntRange> = emptyList())
    /** A custom theme's colours by name (`bg`, `surface`, `ink2`, `accent_soft`…) and whether it is dark. */
    data class Palette(val dark: Boolean, val colors: Map<String, String>)
    data class Result(val rows: List<Row>, val names: Map<String, String>, val nameColors: Map<String, String> = emptyMap(),
        val palette: Palette? = null)

    val WIDTHS = mapOf("phone" to 390, "square" to 600, "wide" to 820)

    /** A saved selection must not be captured while some picked rows are outside the loaded window. */
    fun missingSelected(selected: Set<String>, messages: List<ChatMessage>): Set<String> =
        selected - messages.mapTo(HashSet()) { it.id }

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
            fakeNames.put(id, JSONObject().put("label", label.trim()).put("color", settings.fakeColors[id] ?: JSONObject.NULL))
        val time = when (settings.timeMode) {
            "hide" -> JSONObject().put("mode", "hide")
            "manual" -> JSONObject().put("mode", "manual").put("at", JSONObject().apply { settings.manualAt.forEach { (id, at) -> put(id, at) } })
            "shift" -> JSONObject().put("mode", "shift").put("offset_ms", settings.shiftMinutes.toLong() * 60_000)
            "start" -> JSONObject().put("mode", "start").put("start", settings.startAt)
            else -> JSONObject().put("mode", "real")
        }
        return JSONObject().put("channel_id", channelId)
            .put("selected", JSONArray(settings.selected.toList()))
            .put("unselected", settings.unselected)
            .put("only_members", if (settings.onlyMembers.isEmpty()) JSONObject.NULL else JSONArray(settings.onlyMembers.toList()))
            .put("reply_depth", settings.replyDepth ?: JSONObject.NULL)
            .put("redact_names", settings.redactNames)
            .put("fake_names", fakeNames).put("time", time)
            .put("blackout", JSONObject().apply {
                for ((id, ranges) in settings.blackout) if (ranges.isNotEmpty())
                    put(id, JSONArray(ranges.map { JSONArray().put(it.first).put(it.last + 1) }))
            })
            .put("render", JSONObject().put("style", settings.style).put("theme", settings.theme)
                .put("width", settings.width)
                .apply { if (settings.theme == "custom") put("palette", JSONObject(settings.palette)) }
                .put("blur_avatars", settings.blurAvatars)
                .put("blur_attachments", settings.blurAttachments)
                .put("hide_header", settings.hideHeader).put("hide_reply_bars", settings.hideReplyBars)
                .put("hide_reactions", settings.hideReactions).put("hide_thread_previews", settings.hideThreadPreviews)
                .put("hide_dates", settings.hideDates))
    }

    /** A saved view as settings, or null when it holds something Android can't draw faithfully. */
    fun supported(definition: JSONObject): Settings? {
        val replyDepth = definition.optInt("reply_depth").takeUnless { definition.isNull("reply_depth") }
        if (replyDepth != null && replyDepth !in 0..20) return null
        val onlyMembers = definition.optJSONArray("only_members")?.let { a ->
            (0 until a.length()).mapNotNull { n -> a.optString(n).takeIf { it.isNotBlank() } }.toSet()
        }.orEmpty()
        val render = definition.optJSONObject("render")
        val palette = render?.optJSONObject("palette")?.let { o -> o.keys().asSequence().associateWith { o.optString(it) } }.orEmpty()
        if (render != null && (render.optString("style", "chorus") !in setOf("chorus", "discord", "bubbles", "card", "transcript", "minimal") ||
                render.optString("theme", "auto") !in setOf("auto", "light", "dark", "custom") ||
                render.optString("theme") == "custom" && listOf("bg", "ink", "accent").any { palette[it].isNullOrBlank() } ||
                render.optString("width", "phone") !in WIDTHS)) return null
        val mode = definition.optString("unselected", "context")
        if (mode !in setOf("context", "hidden", "visible")) return null
        val time = definition.optJSONObject("time")
        val timeMode = time?.optString("mode", "real") ?: "real"
        if (timeMode !in setOf("real", "hide", "shift", "start", "manual")) return null
        val manual = time?.optJSONObject("at")?.takeIf { timeMode == "manual" }
        val manualAt = manual?.keys()?.asSequence()?.associateWith { id -> (manual.opt(id) as? Number ?: return null).toLong() }.orEmpty()
        if (timeMode == "start" && time?.opt("start") !is Number) return null
        val offset = time?.optLong("offset_ms") ?: 0L
        if (timeMode == "shift" && (offset % 60_000 != 0L || offset / 60_000 < Int.MIN_VALUE.toLong() ||
                offset / 60_000 > Int.MAX_VALUE.toLong())) return null
        val fakeNames = definition.optJSONObject("fake_names")
        val labels = fakeNames?.keys()?.asSequence()?.associateWith { id ->
            (fakeNames.optJSONObject(id) ?: return null).optString("label")
        }.orEmpty()
        val colors = fakeNames?.keys()?.asSequence()?.mapNotNull { id ->
            fakeNames.optJSONObject(id)?.takeIf { !it.isNull("color") }?.optString("color")?.takeIf { it.isNotBlank() }?.let { id to it }
        }?.toMap().orEmpty()
        val blackoutJson = definition.optJSONObject("blackout")
        val blackout = blackoutJson?.keys()?.asSequence()?.associateWith { id ->
            val a = blackoutJson.optJSONArray(id) ?: return null
            (0 until a.length()).map { n ->
                val r = a.optJSONArray(n) ?: return null
                r.optInt(0) until r.optInt(1)
            }.filter { !it.isEmpty() }
        }.orEmpty()
        val selected = definition.optJSONArray("selected")?.let { a ->
            (0 until a.length()).mapNotNull { n -> a.optString(n).takeIf { it.isNotBlank() } }.toSet()
        }.orEmpty()
        return Settings(selected, mode, definition.optBoolean("redact_names"), labels,
            timeMode, (offset / 60_000).toInt(), onlyMembers, replyDepth,
            render?.optBoolean("blur_attachments") == true,
            render?.optBoolean("hide_header") == true, render?.optBoolean("hide_reply_bars") == true,
            render?.optString("style", "chorus") ?: "chorus", render?.optBoolean("blur_avatars") == true,
            time?.optLong("start") ?: 0L, render?.optString("theme", "auto") ?: "auto",
            render?.optBoolean("hide_reactions") == true, render?.optBoolean("hide_thread_previews") == true,
            render?.optBoolean("hide_dates") == true, render?.optString("width", "phone") ?: "phone",
            palette.filterKeys { it in setOf("bg", "ink", "accent") }, colors, manualAt, blackout)
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
            if (row.getString("kind") == "context") Row(null, false, null, false, row.getInt("count"),
                row.optJSONArray("ids")?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty())
            else Row(row.getString("id"), row.getBoolean("selected"),
                row.optLong("at").takeUnless { row.isNull("at") }, row.getBoolean("reply_shown"), 0,
                blackout = row.optJSONArray("blackout")?.let { a -> (0 until a.length()).map { i ->
                    a.getJSONArray(i).let { r -> r.getInt(0) until r.getInt(1) } } }.orEmpty())
        }
        val resultNames = plan.getJSONObject("names")
        val palette = plan.optJSONObject("palette")?.let { o ->
            Palette(o.optBoolean("dark"), o.keys().asSequence().filter { it != "dark" }.associateWith { o.getString(it) })
        }
        return Result(resultRows, resultNames.keys().asSequence().associateWith { resultNames.getJSONObject(it).getString("label") },
            resultNames.keys().asSequence().mapNotNull { id ->
                resultNames.getJSONObject(id).let { n -> n.optString("color").takeIf { !n.isNull("color") && it.isNotBlank() } }?.let { id to it }
            }.toMap(), palette)
    }
}
