package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject

/** Current-front view bound once per model, reused for each match and each neighbor. */
class SearchVisibility(private val model: Model, private val account: String, private val channel: String? = null) {
    private val byId=model.searchMessages.associateBy {it.id}
    private val own=model.members.mapTo(HashSet()) {it.id}
    private val shown = ChatOrganize.shown(model)
    private val channels = model.channels.associateBy { it.id }
    private val spaces = model.spaces.associateBy { it.id }
    fun document(d: SearchDocument): Boolean = (channel == null || d.channelId == channel) &&
        message(d.card ?: ChatMessage(d.id,d.channelId.orEmpty(),d.authors,d.text,d.occurredAt,d.cw,
            d.visibilityMode,d.visibleMemberIds,d.accountId,null,emptyList()))
    fun message(m: ChatMessage): Boolean {
        if(m.id in model.deletedMessageIds) return false
        val local=byId[m.id]
        val current=local?.let { ChatMessage(it.id,it.channelId.orEmpty(),it.authors,it.text,it.occurredAt,it.cw,it.visibilityMode,it.visibleMemberIds,it.accountId,null,emptyList()) } ?: m
        return visible(current,HashSet())
    }
    private fun visible(m: ChatMessage, seen: MutableSet<String>): Boolean {
        val c = channels[m.channelId] ?: return false
        val s = spaces[c.spaceId] ?: return false
        if (seen.size > 256 || !seen.add(c.id) || !shown(c.id)) return false
        if (c.kind == "thread") {
            val parent = byId[c.parentMessageId] ?: return false
            val p = parent.card ?: ChatMessage(parent.id,parent.channelId.orEmpty(),parent.authors,parent.text,parent.occurredAt,parent.cw,
                parent.visibilityMode,parent.visibleMemberIds,parent.accountId,null,emptyList())
            if (!visible(p,seen)) return false
        } else if (!ChatOrganize.privateVisible(c,s,model.current,null,true,own)) return false
        if(c.kind != "thread" && !ChannelPermissions.canView(model.channelPermissionState,c,account)) return false
        if (model.accountPrefs.hiddenChats[c.id]?.let { m.occurredAt <= it } == true) return false
        if (m.visibilityMode !in listOf("all","system_only","members")) return false
        if (m.visibilityMode != "all" && m.accountId != null && m.accountId != account) return false
        return memberVisible(m,"internal",model.current,null) && accountVisible(m,s.kind,account)
    }
    private val order=compareBy<SearchDocument> {it.occurredAt}.thenBy {it.id}
    private val grouped by lazy { model.searchMessages.filter(::document).groupBy {it.channelId}.mapValues {it.value.sortedWith(order)} }
    fun context(hit: SearchDocument, extra: List<ChatMessage> = emptyList()): List<String> {
        if (!document(hit)) return emptyList()
        val local=grouped[hit.channelId].orEmpty()
        var low=0; var high=local.size
        while(low<high) { val mid=(low+high) ushr 1; if(order.compare(local[mid],hit)<0) low=mid+1 else high=mid }
        // Only the closest local candidates can beat the server's two neighbors.
        val nearest=local.subList((low-1).coerceAtLeast(0),(low+2).coerceAtMost(local.size))
        val candidates = (nearest
            .map { it.id to it.occurredAt } + extra.filter { it.channelId == hit.channelId && message(it) }.map { it.id to it.occurredAt } + (hit.id to hit.occurredAt))
            .distinctBy { it.first }.sortedWith(compareBy<Pair<String,Long>> {it.second}.thenBy {it.first})
        val i=candidates.indexOfFirst {it.first==hit.id}
        return candidates.subList((i-1).coerceAtLeast(0),(i+2).coerceAtMost(candidates.size)).map {it.first}
    }
    companion object {
        fun strings(a: JSONArray?): List<String> = if (a==null) emptyList() else (0 until a.length()).mapNotNull {a.opt(it) as? String}
        fun parseCard(j: JSONObject): ChatMessage {
            fun text(key:String)=j.optString(key).takeIf {j.has(key) && !j.isNull(key)}
            val v=j.optJSONObject("visibility")
            val files=j.optJSONArray("attachments") ?: JSONArray()
            val attachments=(0 until files.length()).mapNotNull {i -> files.optJSONObject(i)?.let {a ->
                val hash=a.optString("blob_hash").takeIf {it.isNotEmpty() && it!="null"} ?: return@let null
                ChatAttachment(a.optString("id"),hash,a.optString("thumb_blob_hash").takeIf {!a.isNull("thumb_blob_hash")},
                    a.optString("filename","file"),a.optString("mime","application/octet-stream"),a.optLong("size"),a.optString("alt_text"),a.optBoolean("is_spoiler"))
            }}
            val seg=j.optJSONArray("segments") ?: JSONArray()
            return ChatMessage(j.getString("id"),j.getString("channel_id"),strings(j.optJSONArray("authors")),j.optString("text"),j.optLong("occurred_at"),text("cw"),
                (if(!j.has("visibility") || j.isNull("visibility")) "all" else v?.optString("mode") ?: "invalid"),strings(v?.optJSONArray("member_ids")).toSet(),text("account_id"),text("reply_to"),attachments,
                edited=text("edited_at")!=null, entities=j.optJSONArray("entities")?.toString() ?: "[]",
                segments=(0 until seg.length()).mapNotNull {i -> seg.optJSONObject(i)?.let {ChatSegment(it.optInt("offset"),it.optInt("length"),strings(it.optJSONArray("authors"))) }},
                pinned=text("pinned_at")!=null, forward=ChatForward.parse(j.optJSONArray("forward_snapshot")), quote=ChatForward.quoted(j.optJSONObject("quote")),
                authorCards=j.optJSONArray("author_cards")?.let {a -> (0 until a.length()).mapNotNull {a.optJSONObject(it)?.let(ForeignAuthor::fromJson)}.associateBy {it.id}}.orEmpty(),
                poll=Polls.parse(j.optJSONObject("poll")),pollTally=Polls.tally(j.optJSONObject("poll_tally")))
        }
    }
}
