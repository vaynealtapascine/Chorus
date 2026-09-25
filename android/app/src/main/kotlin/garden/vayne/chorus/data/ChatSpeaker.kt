package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject
import uniffi.chorus_ffi.defaultSpeaker

data class SpeakerDefault(val mode: String = "front", val memberId: String? = null)

/** Account-scoped channel preference (D-074) and the shared core's default-speaker rule. */
object ChatSpeaker {
    fun preferences(projection: JSONObject, accountId: String, channels: List<ChatChannel>): Map<String, SpeakerDefault> {
        val prefs = projection.optJSONObject("rows")?.optJSONObject("pref")
        return channels.associate { channel ->
            val key = "autoproxy:${channel.id}"
            val pending = prefs?.optJSONObject("||$key")?.takeIf { it.optBoolean("exists") }
            val stamped = prefs?.optJSONObject("$accountId||$key")?.takeIf { it.optBoolean("exists") }
            val value = (pending ?: stamped)?.optJSONObject("fields")?.optJSONObject("value")
            val mode = value?.optString("mode")?.takeIf { it in setOf("off", "front", "latch", "member") } ?: "front"
            channel.id to SpeakerDefault(mode, value?.optString("member")?.ifEmpty { null })
        }
    }

    fun pick(model: Model, channelId: String, core: (String) -> String = ::defaultSpeaker): String? {
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
        return JSONArray(core(context.toString())).optString(0).ifEmpty { null }
    }

    fun payload(channelId: String, mode: String, memberId: String? = null): JSONObject {
        require(mode in setOf("off", "front", "latch", "member"))
        require(mode != "member" || !memberId.isNullOrBlank())
        val value = JSONObject().put("mode", mode)
        if (mode == "member") value.put("member", memberId)
        return AccountPrefs.payload("autoproxy:$channelId", value)
    }
}
