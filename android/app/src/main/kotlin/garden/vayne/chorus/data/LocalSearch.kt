package garden.vayne.chorus.data

import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

data class SearchDocument(val id: String, val kind: String, val occurredAt: Long, val text: String,
    val title: String? = null, val cw: String? = null, val authors: List<String> = emptyList(),
    val channelId: String? = null, val tags: List<String> = emptyList(),
    val hasImage: Boolean = false, val hasFile: Boolean = false, val hasAttachment: Boolean = false,
    val authorNames: List<String> = emptyList())

data class LocalSearchQuery(val terms: List<String>, val from: String? = null, val inChannel: String? = null,
    val has: String? = null, val before: Long? = null, val after: Long? = null)

/** Prefix search over the locally permitted replica rows. Built only while Search is open. */
class LocalSearch private constructor(private val docs: List<SearchDocument>, private val names: Map<String, String>,
    private val channelNames: Map<String, String>) {
    private val wordDocs = HashMap<String, MutableSet<Int>>()
    private val words: List<String>
    private val newestFirst: List<Int>

    init {
        for ((i, doc) in docs.withIndex()) {
            val source = listOfNotNull(doc.title, doc.text, doc.cw, doc.tags.joinToString(" ")).joinToString(" ")
            for (word in tokenize(source).toSet()) wordDocs.getOrPut(word) { HashSet() }.add(i)
        }
        words = wordDocs.keys.sorted()
        newestFirst = docs.indices.sortedWith(compareByDescending<Int> { docs[it].occurredAt }
            .thenByDescending { docs[it].id })
    }

    fun search(raw: String, kind: String, limit: Int = 100, zone: ZoneId = ZoneId.systemDefault()): List<SearchDocument> {
        val q = parse(raw, zone)
        if (q.terms.isEmpty() && q.from == null && q.inChannel == null && q.has == null && q.before == null && q.after == null)
            return emptyList()
        var ids: Set<Int>? = null
        for (term in q.terms) {
            val matches = prefix(term)
            ids = if (ids == null) matches else ids.intersect(matches)
            if (ids.isEmpty()) return emptyList()
        }
        val from = q.from?.let(::fold)
        val channel = q.inChannel?.let(::fold)
        return newestFirst.asSequence().filter { ids == null || it in ids }.map { docs[it] }
            .filter { doc -> doc.kind == kind &&
                (q.before == null || doc.occurredAt < q.before) &&
                (q.after == null || doc.occurredAt > q.after) &&
                (kind == "Switches" || from == null || doc.authors.any { it == q.from || fold(names[it].orEmpty()).contains(from) }) &&
                (kind != "Messages" || channel == null || doc.channelId == q.inChannel ||
                    fold(channelNames[doc.channelId].orEmpty()).contains(channel)) &&
                (kind != "Messages" || q.has == null || when (q.has) {
                    "image" -> doc.hasImage
                    "file" -> doc.hasFile
                    "attachment" -> doc.hasAttachment
                    else -> false
                })
            }.take(limit).toList()
    }

    private fun prefix(term: String): Set<Int> {
        var low = 0
        var high = words.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (words[mid] < term) low = mid + 1 else high = mid
        }
        val found = HashSet<Int>()
        var i = low
        while (i < words.size && words[i].startsWith(term)) {
            found.addAll(wordDocs[words[i]].orEmpty()); i++
        }
        return found
    }

    companion object {
        private val PARTS = Regex("(?:[^\\s\"]|\"[^\"]*\")+")
        private val TOKENS = Regex("[\\p{L}\\p{N}]+")

        private fun fold(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
        private fun tokenize(s: String): List<String> = TOKENS.findAll(fold(s)).map { it.value }.toList()

        fun parse(raw: String, zone: ZoneId = ZoneId.systemDefault()): LocalSearchQuery {
            val terms = ArrayList<String>()
            var from: String? = null
            var channel: String? = null
            var has: String? = null
            var before: Long? = null
            var after: Long? = null
            for (part in PARTS.findAll(raw).map { it.value }) {
                val bar = part.indexOf(':')
                if (bar < 1) { terms.addAll(tokenize(part)); continue }
                val key = part.substring(0, bar).lowercase(Locale.ROOT)
                val value = part.substring(bar + 1).trim('"')
                when (key) {
                    "from" -> from = value.takeIf { it.isNotBlank() }
                    "in" -> channel = value.takeIf { it.isNotBlank() }
                    "has" -> has = value.takeIf { it.isNotBlank() }
                    "before" -> before = timestamp(value, zone)
                    "after" -> after = timestamp(value, zone)
                    else -> terms.addAll(tokenize(part))
                }
            }
            return LocalSearchQuery(terms, from, channel, has, before, after)
        }

        private fun timestamp(raw: String, zone: ZoneId): Long? = raw.toLongOrNull()
            ?: runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDate.parse(raw).atStartOfDay(zone).toInstant().toEpochMilli() }.getOrNull()

        fun fromModel(model: Model): LocalSearch {
            val names = model.members.associate { it.id to it.shownName } + model.groups.associate { it.id to it.name }
            val channels = model.channels.associate { it.id to it.name }
            val posts = model.posts.map { post -> SearchDocument(post.id, "Posts", post.occurredAt, post.text,
                post.title, post.cw, post.authors, tags = post.tags) }
            val switches = model.switches.filter { !it.retracted }.map { row ->
                val subjects = row.entries.map { names[it.subjectId] ?: "Someone" }
                SearchDocument(row.id, "Switches", row.occurredAt,
                    listOf(subjects.joinToString(" & "), row.note).filterNotNull().joinToString(" · "))
            }
            return LocalSearch(model.searchMessages + posts + switches, names, channels)
        }
    }
}
