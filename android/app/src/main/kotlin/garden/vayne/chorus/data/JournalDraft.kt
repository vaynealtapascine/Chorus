package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject

/** URI references deliberately survive a lost grant. Never drop a file merely because it cannot
 * be read now: the editor offers removal/reselection and send preflights every reference. */
data class JournalDraftFile(val uri: String, val name: String, val mime: String,
    val alt: String = "", val spoiler: Boolean = false) {
    fun json(): JSONObject = JSONObject().put("uri", uri).put("name", name).put("mime", mime)
        .put("alt", alt).put("spoiler", spoiler)
}

data class JournalLaunch(val id: String, val kind: String, val text: String = "",
    val files: List<JournalDraftFile> = emptyList(), val post: String? = null,
    val author: String? = null, val audience: String? = null) {
    fun json(): JSONObject = JSONObject().put("id", id).put("kind", kind).put("text", text)
        .put("files", JSONArray(files.map { it.json() })).put("post", post)
        .put("author", author).put("audience", audience)
}

/** null authors means follow the normal default; [] is an explicit empty selection. The body
 * is source markup, not rendered text, so formatting survives without a second parser. */
data class JournalDraft(val id: String, val revision: Long = 0, val body: String = "",
    val selectionStart: Int = 0, val selectionEnd: Int = 0, val title: String = "",
    val kind: String = "note", val authors: List<String>? = null, val authorHint: String = "",
    val audience: String = "private", val buckets: List<String> = emptyList(),
    val cw: String = "", val tags: String = "", val mood: String = "",
    val reply: String? = null, val quote: String? = null, val poll: Boolean = false,
    val options: List<String> = listOf("", ""), val multiple: Boolean = false,
    val files: List<JournalDraftFile> = emptyList(), val editing: Boolean = false,
    val advanced: Boolean = false, val sendingPost: String? = null,
    val launches: List<JournalLaunch> = emptyList(), val recovery: String? = null,
    val receipts: List<String> = emptyList(),
    /** The writer picked this audience, rather than it being the account's default (D-117). */
    val audienceChosen: Boolean = false,
    /** Who hears about it (D-127): "" as usual, `delayed` (a random 5–60 minutes later) or `none`. */
    val alerts: String = "") {
    val occupied: Boolean get() = body.isNotEmpty() || title.isNotEmpty() || files.isNotEmpty() ||
        authors != null || cw.isNotEmpty() || tags.isNotEmpty() || mood.isNotEmpty() || reply != null ||
        quote != null || poll || options.any { it.isNotEmpty() } || audienceChosen || recovery != null || alerts.isNotEmpty()

    /** A draft nobody has started shows the account's default audience. Once it holds anything,
     * what it shows stays, so a default changed on another device never widens words being written. */
    fun following(default: Audience): JournalDraft =
        if (occupied || (audience == default.mode && buckets == default.buckets)) this
        else copy(audience = default.mode, buckets = default.buckets)

    fun encode(): String = JSONObject().put("version", 1).put("id", id).put("revision", revision)
        .put("body", body).put("start", selectionStart).put("end", selectionEnd).put("title", title)
        .put("kind", kind).put("authors", authors?.let { JSONArray(it) } ?: JSONObject.NULL).put("authorHint", authorHint)
        .put("audience", audience).put("buckets", JSONArray(buckets)).put("audienceChosen", audienceChosen).put("cw", cw).put("tags", tags).put("mood", mood)
        .put("reply", reply).put("quote", quote).put("poll", poll).put("options", JSONArray(options)).put("multiple", multiple)
        .put("files", JSONArray(files.map { it.json() })).put("editing", editing).put("advanced", advanced)
        .put("sendingPost", sendingPost).put("launches", JSONArray(launches.map { it.json() })).put("recovery", recovery)
        .put("receipts", JSONArray(receipts)).put("alerts", alerts).toString()

    companion object {
        /** A post's notification choices besides the usual (D-127). */
        val ALERTS = setOf("delayed", "none")

        /** Salvage fields independently. Any damage preserves the entire original record inside
         * recovery, blocks send, and requires review; defaults must never silently widen privacy
         * or replace an explicit author selection with the current front. */
        fun decode(raw: String, newId: () -> String): JournalDraft {
            var damaged = false
            val j = try { JSONObject(raw) } catch (_: Exception) {
                return JournalDraft(newId(), body = raw, authors = emptyList(), editing = true, recovery = raw)
            }
            fun <T> field(default: T, read: () -> T): T = try { read() } catch (_: Exception) { damaged = true; default }
            fun text(key: String) = field("") { j.getString(key) }
            fun nullable(key: String) = field<String?>(null) { if (!j.has(key) || j.isNull(key)) null else j.getString(key) }
            fun strings(key: String): List<String> = field(emptyList()) { j.getJSONArray(key).let { a -> List(a.length()) { a.getString(it) } } }
            fun files(a: JSONArray): List<JournalDraftFile> = List(a.length()) { i -> a.getJSONObject(i).let {
                JournalDraftFile(it.getString("uri"), it.getString("name"), it.getString("mime"), it.getString("alt"), it.getBoolean("spoiler"))
            } }
            val draft = JournalDraft(
                id = field(newId()) { j.getString("id").also { require(java.util.UUID.fromString(it).toString() == it) } },
                revision = field(0L) { j.getLong("revision") }, body = text("body"),
                selectionStart = field(0) { j.getInt("start") }, selectionEnd = field(0) { j.getInt("end") },
                title = text("title"), kind = text("kind"),
                authors = if (j.has("authors") && j.isNull("authors")) null else strings("authors"), authorHint = text("authorHint"),
                audience = text("audience"), buckets = strings("buckets"),
                // a draft from before D-117 only left Only us if the writer chose to
                audienceChosen = if (j.has("audienceChosen")) j.optBoolean("audienceChosen") else
                    j.optString("audience", "private") != "private" || (j.optJSONArray("buckets")?.length() ?: 0) > 0,
                cw = text("cw"), tags = text("tags"), mood = text("mood"),
                reply = nullable("reply"), quote = nullable("quote"), poll = field(false) { j.getBoolean("poll") },
                options = strings("options"), multiple = field(false) { j.getBoolean("multiple") },
                files = field(emptyList()) { files(j.getJSONArray("files")) },
                editing = field(true) { j.getBoolean("editing") }, advanced = field(false) { j.getBoolean("advanced") },
                sendingPost = nullable("sendingPost"), recovery = nullable("recovery"),
                receipts = if (j.has("receipts")) strings("receipts") else emptyList(),
                alerts = j.optString("alerts").takeIf { it in ALERTS }.orEmpty(),
                launches = field(emptyList()) { j.getJSONArray("launches").let { a -> List(a.length()) { i -> a.getJSONObject(i).let {
                    JournalLaunch(it.getString("id"), it.getString("kind"), it.getString("text"), files(it.getJSONArray("files")),
                        it.optString("post").takeIf(String::isNotEmpty), it.optString("author").takeIf(String::isNotEmpty),
                        it.optString("audience").takeIf(String::isNotEmpty))
                } } } })
            if (j.optInt("version") != 1 || draft.kind !in setOf("note", "entry") ||
                draft.audience !in setOf("private", "friends", "followers", "following", "buckets", "server")) damaged = true
            return draft.copy(recovery = if (damaged) raw else draft.recovery,
                kind = draft.kind.takeIf { it in setOf("note", "entry") } ?: "note",
                audience = draft.audience.takeIf { it in setOf("private", "friends", "followers", "following", "buckets", "server") } ?: "private",
                selectionStart = draft.selectionStart.coerceIn(0, draft.body.length),
                selectionEnd = draft.selectionEnd.coerceIn(0, draft.body.length))
        }
    }
}
