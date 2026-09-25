package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject
import uniffi.chorus_ffi.searchFilter
import uniffi.chorus_ffi.searchParse

data class MessageSearchResult(val items: List<SearchDocument>, val raw: String = "", val error: String? = null,
    val validQuery: Boolean = false)

/** The replica's message candidates, serialized once per model; core owns all search semantics. */
class CoreMessageSearch(model: Model) {
    private val docs = model.searchMessages
    private val candidates: String

    init {
        val members = model.members.associate { it.id to it.name }
        val channels = model.channels.associate { it.id to it.name }
        candidates = JSONArray().apply {
            for (doc in docs) put(JSONObject()
                .put("text", doc.text).put("cw", doc.cw)
                .put("authors", JSONArray().apply { for (id in doc.authors)
                    put(JSONArray().put(id).put(members[id].orEmpty())) })
                .put("channel", JSONArray().put(doc.channelId.orEmpty()).put(channels[doc.channelId].orEmpty()))
                .put("at", doc.occurredAt).put("mimes", JSONArray(doc.mimes))
                .put("link", doc.hasLink).put("pinned", doc.pinned))
        }.toString()
    }

    fun search(raw: String, now: Long, tzOffsetMin: Int, limit: Int = 100,
        parse: (String) -> String = ::searchParse,
        filter: (String, String, String) -> String = ::searchFilter): MessageSearchResult {
        if (raw.isBlank()) return MessageSearchResult(emptyList(), raw)
        val query = try { parse(raw) } catch (e: Exception) {
            val detail = e.message.orEmpty()
            val message = runCatching { JSONObject(detail).getString("message") }.getOrDefault(detail)
            return MessageSearchResult(emptyList(), raw, message.ifBlank { "Invalid search" })
        }
        return try {
            val ctx = JSONObject().put("now", now).put("tz_offset_min", tzOffsetMin).toString()
            val indexes = JSONArray(filter(query, candidates, ctx))
            val hits = (0 until indexes.length()).mapNotNull { docs.getOrNull(indexes.optInt(it, -1)) }
                .sortedWith(compareByDescending<SearchDocument> { it.occurredAt }.thenByDescending { it.id })
                .take(limit)
            MessageSearchResult(hits, raw, validQuery = true)
        } catch (e: Exception) {
            MessageSearchResult(emptyList(), raw, e.message ?: "Search failed")
        }
    }
}
