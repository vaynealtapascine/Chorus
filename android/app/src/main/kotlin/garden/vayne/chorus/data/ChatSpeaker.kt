package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject
import uniffi.chorus_ffi.defaultSpeaker
import uniffi.chorus_ffi.defaultSpeakerForFront

/** [frontId] is the channel's selected front (`authorship_front:<channel>`); null = Body. */
data class SpeakerDefault(val mode: String = "front", val memberId: String? = null, val frontId: String? = null)

/** Account-scoped channel preferences (D-074, D-080/D-085) and the shared core's default-speaker rule. */
object ChatSpeaker {
    fun frontKey(channelId: String) = "authorship_front:$channelId"

    fun preferences(projection: JSONObject, accountId: String, channels: List<ChatChannel>): Map<String, SpeakerDefault> {
        val prefs = projection.optJSONObject("rows")?.optJSONObject("pref")
        fun value(key: String): Any? {
            val pending = prefs?.optJSONObject("||$key")?.takeIf { it.optBoolean("exists") }
            val stamped = prefs?.optJSONObject("$accountId||$key")?.takeIf { it.optBoolean("exists") }
            return (pending ?: stamped)?.optJSONObject("fields")?.opt("value")
        }
        return channels.associate { channel ->
            val value = value("autoproxy:${channel.id}") as? JSONObject
            val mode = value?.optString("mode")?.takeIf { it in setOf("off", "front", "latch", "member") } ?: "front"
            val front = (value(frontKey(channel.id)) as? String)?.ifEmpty { null }
            channel.id to SpeakerDefault(mode, value?.optString("member")?.ifEmpty { null }, front)
        }
    }

    /** The channel's front for a configured account (Body when unset); null when unconfigured. */
    fun frontOf(model: Model, channelId: String): String? =
        model.namedFronts?.let { named -> model.speakerDefaults[channelId]?.frontId ?: named.bodyId }

    fun pick(
        model: Model,
        channelId: String,
        core: (String) -> String = ::defaultSpeaker,
        forFront: (String, String, String, String) -> String = ::defaultSpeakerForFront,
    ): String? {
        val choice = model.speakerDefaults[channelId] ?: SpeakerDefault()
        val context = JSONObject().put("mode", choice.mode).put("member", choice.memberId ?: JSONObject.NULL)
            .put("self_member", model.members.firstOrNull { it.isSelf && !it.archived }?.id ?: JSONObject.NULL)
            .put("fronting", JSONArray().apply {
                model.current.filter { it.subjectType == "member" }.forEach { entry ->
                    put(JSONObject().put("member_id", entry.subjectId).put("is_primary", entry.isPrimary).put("level", entry.level))
                }
            })
            .put("last_authors", JSONArray(model.lastAuthorsByChannel[channelId].orEmpty()))
            .put("members", JSONArray(model.active.map { it.id }))
        val named = model.namedFronts
        val result = if (named == null) core(context.toString())
            else forFront(context.toString(), named.systemJson, named.bodyId, frontOf(model, channelId).orEmpty())
        return JSONArray(result).optString(0).ifEmpty { null }
    }

    fun payload(channelId: String, mode: String, memberId: String? = null): JSONObject {
        require(mode in setOf("off", "front", "latch", "member"))
        require(mode != "member" || !memberId.isNullOrBlank())
        val value = JSONObject().put("mode", mode)
        if (mode == "member") value.put("member", memberId)
        return AccountPrefs.payload("autoproxy:$channelId", value)
    }

    /** Separate from [payload] so changing the front never overwrites the autoproxy mode. */
    fun frontPayload(channelId: String, frontId: String): JSONObject {
        require(frontId.isNotBlank())
        return AccountPrefs.payload(frontKey(channelId), frontId)
    }
}
