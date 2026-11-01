package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject
import uniffi.chorus_ffi.defaultSpeaker
import uniffi.chorus_ffi.defaultSpeakerForFront

/** [frontId] is the channel's selected front (`authorship_front:<channel>`); null = Body. */
data class SpeakerDefault(val mode: String = "front", val memberId: String? = null, val frontId: String? = null,
    /** "members" mode (D-103): the chosen speakers, the first being the default. */
    val members: List<String> = emptyList())

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
            val mode = value?.optString("mode")?.takeIf { it in MODES } ?: "front"
            val front = (value(frontKey(channel.id)) as? String)?.ifEmpty { null }
            val chosen = value?.optJSONArray("members")?.let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? String } }.orEmpty().distinct()
            channel.id to SpeakerDefault(mode, value?.optString("member")?.ifEmpty { null }, front, chosen)
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
        val setting = model.speakerDefaults[channelId] ?: SpeakerDefault()
        // several chosen speakers: the first one we may write as is the default (core knows one sticky member)
        val first = setting.members.firstOrNull { id -> model.active.any { it.id == id } }
        val choice = if (setting.mode != "members") setting else if (first != null) setting.copy(mode = "member", memberId = first) else setting.copy(mode = "front")
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

    private val MODES = setOf("off", "front", "latch", "member", "members")

    fun payload(channelId: String, mode: String, memberId: String? = null, members: List<String> = emptyList()): JSONObject {
        require(mode in MODES)
        require(mode != "member" || !memberId.isNullOrBlank())
        val value = JSONObject().put("mode", mode)
        if (mode == "member") value.put("member", memberId)
        if (mode == "members") value.put("members", JSONArray(members.distinct()))
        return AccountPrefs.payload("autoproxy:$channelId", value)
    }

    /**
     * The room's speakers (D-103; web's room-speakers.ts): everyone fronting or co-conscious (primary
     * first) in front mode, the chosen members, the one member, or our latest message's authors;
     * only those in [writable]. The composer offers each; their messages sit on our side.
     */
    fun speakers(model: Model, channelId: String, writable: Set<String>): List<String> {
        val s = model.speakerDefaults[channelId] ?: SpeakerDefault()
        val ids = when (s.mode) {
            "front" -> ChatPresentation.recorded(model, channelId).filter { it.subjectType == "member" && (it.level == "front" || it.level == "cocon") }
                .sortedByDescending { it.isPrimary }.map { it.subjectId }
            "members" -> s.members
            "member" -> listOfNotNull(s.memberId)
            "latch" -> model.lastAuthorsByChannel[channelId].orEmpty()
            else -> emptyList()
        }
        return ids.distinct().filter { it in writable }
    }

    /** Separate from [payload] so changing the front never overwrites the autoproxy mode. */
    fun frontPayload(channelId: String, frontId: String): JSONObject {
        require(frontId.isNotBlank())
        return AccountPrefs.payload(frontKey(channelId), frontId)
    }
}
