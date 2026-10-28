package garden.vayne.chorus.data

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
    fun create(spaceId: String, name: String, topic: String, avatar: String?): JSONObject =
        fields(name, topic, avatar).put("space_id", spaceId).put("kind", "text")

    fun update(channel: ChatChannel, name: String, topic: String, avatar: String?): JSONObject {
        val all = fields(name, topic, avatar)
        if (name.trim() == channel.name) all.remove("name")
        if (topic.trim() == channel.topic.orEmpty()) all.remove("topic")
        if (avatar == channel.avatarBlob) all.remove("avatar_blob")
        return all
    }

    fun glyph(channel: ChatChannel): String = channel.icon?.takeIf { it.isNotBlank() }
        ?: if (channel.name.equals("general", ignoreCase = true)) "#"
        else channel.name.trim().codePoints().findFirst().orElse('#'.code).let { String(Character.toChars(it)).uppercase() }
}
