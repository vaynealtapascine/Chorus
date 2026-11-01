package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject

/** Field-level channel writes: an image change must not rewrite settings, colour or icon. */
object ChannelCompose {
    private val hash = Regex("[0-9a-f]{64}")
    fun fields(name: String, topic: String, avatar: String?): JSONObject {
        require(name.isNotBlank()) { "Give this channel a name." }
        require(name.trim().length <= 80) { "Keep the channel name within 80 characters." }
        require(avatar == null || hash.matches(avatar)) { "Choose the image again." }
        return JSONObject().put("name", name.trim()).put("topic", topic.trim().ifBlank { null } ?: JSONObject.NULL)
            .put("avatar_blob", avatar ?: JSONObject.NULL)
    }
    fun create(spaceId: String, name: String, topic: String, avatar: String?, color: String? = null,
        icon: String? = null, memberIds: List<String> = emptyList()): JSONObject =
        fields(name, topic, avatar).apply {
            // Older servers reject unknown fields even when null. Absence conveys no image.
            if (avatar == null) remove("avatar_blob")
            if (color != null) put("color", color)
            icon?.trim()?.takeIf { it.isNotEmpty() }?.let { put("icon", it) }
            // kept to these members (D-097: hidden while none of them is here)
            if (memberIds.isNotEmpty()) put("member_ids", JSONArray(memberIds.distinct().sorted()))
        }.put("space_id", spaceId).put("kind", "text")

    fun supportsImages(server: JSONObject): Boolean = server.optJSONArray("capabilities")?.let { flags ->
        (0 until flags.length()).any { flags.optString(it) == "channel_images" }
    } == true

    fun update(channel: ChatChannel, name: String, topic: String, avatar: String?, color: String = channel.color ?: "#42A397",
        icon: String? = channel.icon, memberIds: List<String> = channel.memberIds): JSONObject {
        val all = fields(name, topic, avatar)
        if (name.trim() == channel.name) all.remove("name")
        if (topic.trim() == channel.topic.orEmpty()) all.remove("topic")
        if (avatar == channel.avatarBlob) all.remove("avatar_blob")
        if (color != (channel.color ?: "#42A397")) all.put("color", color)
        val newIcon = icon?.trim()?.takeIf { it.isNotEmpty() }
        if (newIcon != channel.icon?.takeIf { it.isNotBlank() }) all.put("icon", newIcon ?: JSONObject.NULL)
        val ids = memberIds.distinct().sorted()
        if (ids != channel.memberIds.distinct().sorted()) all.put("member_ids", if (ids.isEmpty()) JSONObject.NULL else JSONArray(ids))
        return all
    }

    fun glyph(channel: ChatChannel): String = channel.icon?.takeIf { it.isNotBlank() }
        ?: if (channel.name.equals("general", ignoreCase = true)) "#"
        else channel.name.trim().codePoints().findFirst().orElse('#'.code).let { String(Character.toChars(it)).uppercase() }
}
