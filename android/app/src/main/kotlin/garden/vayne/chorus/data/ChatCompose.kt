package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject
import uniffi.chorus_ffi.compose

/** Compose in chorus-core so Android follows the same sigil, proxy tag, segment and markup rules. */
object ChatCompose {
    data class Preview(val source: String, val text: String, val authors: List<String>)

    fun members(model: Model, accountId: String) = model.active.filter {
        it.createdByAccountId == null || it.createdByAccountId == accountId
    }

    private fun composed(
        model: Model, draft: String, selectedAuthor: String?, accountId: String,
        composer: (String, String, String, String, String) -> String,
    ): JSONObject {
        val own = members(model, accountId)
        val speakers = JSONArray()
        for (member in own) {
            val tags = JSONArray()
            for (tag in member.proxyTags) tags.put(JSONObject().put("prefix", tag.prefix).put("suffix", tag.suffix))
            speakers.put(JSONObject().put("member_id", member.id).put("sigils", JSONArray(member.sigils)).put("proxy_tags", tags))
        }
        val defaults = JSONArray(own.filter { it.id == selectedAuthor }.map { it.id })
        val options = JSONObject().put("segments", model.accountPrefs.segmentParsing)
        return JSONObject(composer(draft, speakers.toString(), options.toString(), defaults.toString(), ChatMentions.names(model).toString()))
    }

    fun preview(model: Model, draft: String, selectedAuthor: String?, accountId: String): Preview {
        val result = composed(model, draft, selectedAuthor, accountId, ::compose)
        val authors = result.getJSONArray("authors")
        return Preview(draft, result.getJSONObject("rich").getString("text"),
            (0 until authors.length()).map { authors.getString(it) })
    }

    fun payload(
        model: Model, channelId: String, selectedAuthor: String?, draft: String,
        cw: String, audience: String, visibleTo: Set<String>, spaceKind: String,
        composer: (String, String, String, String, String) -> String = ::compose,
        replyTo: String? = null,
        attachmentIds: List<String> = emptyList(),
        accountId: String = "",
        quote: JSONObject? = null,
    ): JSONObject {
        require(draft.isNotBlank() || attachmentIds.isNotEmpty()) { "Write a message first." }
        require(audience in setOf("all", "members", "system_only")) { "Choose who can see this message." }
        require(audience != "members" || (spaceKind == "internal" && visibleTo.isNotEmpty())) { "Choose who can see this message." }
        require(audience != "system_only" || spaceKind != "internal") { "Asides belong in a shared space." }
        val own = members(model, accountId).map { it.id }.toSet()
        val payload = if (draft.isBlank()) {
            // only attachments: no text to parse for speakers or markup
            require(selectedAuthor in own) { "Choose one of your members to speak." }
            JSONObject().put("channel_id", channelId).put("authors", JSONArray().put(selectedAuthor))
                .put("text", "").put("entities", JSONArray())
        } else {
            val composed = composed(model, draft, selectedAuthor, accountId, composer)
            val rich = composed.getJSONObject("rich")
            require(rich.getString("text").isNotBlank() || attachmentIds.isNotEmpty()) { "Write a message first." }
            val authors = composed.getJSONArray("authors")
            require(authors.length() > 0) { "Choose who is speaking." }
            require((0 until authors.length()).all { authors.getString(it) in own }) { "Choose one of your members to speak." }
            JSONObject().put("channel_id", channelId).put("authors", authors)
                .put("text", rich.getString("text")).put("entities", rich.getJSONArray("entities"))
                .put("segments", composed.getJSONArray("segments"))
        }
        if (attachmentIds.isNotEmpty()) payload.put("attachments", JSONArray(attachmentIds))
        if (replyTo != null) payload.put("reply_to", replyTo)
        if (quote != null) payload.put("quote", quote)
        if (cw.isNotBlank()) payload.put("cw", cw.trim())
        if (audience == "members") payload.put("visibility", JSONObject().put("mode", "members")
            .put("member_ids", JSONArray(visibleTo.sorted())))
        if (audience == "system_only") payload.put("visibility", JSONObject().put("mode", "system_only"))
        return payload
    }
}
