package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject
import uniffi.chorus_ffi.compose

/** Compose in chorus-core so Android follows the same sigil, proxy tag, segment and markup rules. */
object ChatCompose {
    fun payload(
        model: Model, channelId: String, selectedAuthor: String, draft: String,
        cw: String, audience: String, visibleTo: Set<String>, spaceKind: String,
        composer: (String, String, String, String, String) -> String = ::compose,
        replyTo: String? = null,
        attachmentIds: List<String> = emptyList(),
    ): JSONObject {
        require(model.active.any { it.id == selectedAuthor }) { "Choose one of your members to speak." }
        require(draft.isNotBlank() || attachmentIds.isNotEmpty()) { "Write a message first." }
        require(audience in setOf("all", "members", "system_only")) { "Choose who can see this message." }
        require(audience != "members" || (spaceKind == "internal" && visibleTo.isNotEmpty())) { "Choose who can see this message." }
        require(audience != "system_only" || spaceKind != "internal") { "Asides belong in a shared space." }
        val speakers = JSONArray()
        for (member in model.active) {
            val tags = JSONArray()
            for (tag in member.proxyTags) tags.put(JSONObject().put("prefix", tag.prefix).put("suffix", tag.suffix))
            speakers.put(JSONObject().put("member_id", member.id).put("sigils", JSONArray(member.sigils)).put("proxy_tags", tags))
        }
        val payload = if (draft.isBlank()) {
            // only attachments: no text to parse for speakers or markup
            JSONObject().put("channel_id", channelId).put("authors", JSONArray().put(selectedAuthor))
                .put("text", "").put("entities", JSONArray())
        } else {
            val composed = JSONObject(composer(draft, speakers.toString(), "{}", JSONArray().put(selectedAuthor).toString(), ""))
            val rich = composed.getJSONObject("rich")
            require(rich.getString("text").isNotBlank() || attachmentIds.isNotEmpty()) { "Write a message first." }
            val authors = composed.getJSONArray("authors")
            require(authors.length() > 0) { "Choose who is speaking." }
            JSONObject().put("channel_id", channelId).put("authors", authors)
                .put("text", rich.getString("text")).put("entities", rich.getJSONArray("entities"))
                .put("segments", composed.getJSONArray("segments"))
        }
        if (attachmentIds.isNotEmpty()) payload.put("attachments", JSONArray(attachmentIds))
        if (replyTo != null) payload.put("reply_to", replyTo)
        if (cw.isNotBlank()) payload.put("cw", cw.trim())
        if (audience == "members") payload.put("visibility", JSONObject().put("mode", "members")
            .put("member_ids", JSONArray(visibleTo.sorted())))
        if (audience == "system_only") payload.put("visibility", JSONObject().put("mode", "system_only"))
        return payload
    }
}
