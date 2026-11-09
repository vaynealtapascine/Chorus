package garden.vayne.chorus.data

/**
 * The feed editor's rule rows (F6.7) over the same query string a feed is saved with (SPEC §6.4):
 * `(from:@a or from:@b) tag:calm kind:entry since:30d reply:false`. A query that uses anything else
 * (parentheses of another shape, `-`, `has:`, free text…) is not representable: the editor then
 * shows the text itself and never rewrites it.
 */
data class FeedRule(val key: String, val values: List<String>)
data class FeedRules(val rules: List<FeedRule> = emptyList(), val replies: Boolean = true) {
    fun values(key: String): List<String> = rules.find { it.key == key }?.values.orEmpty()
    fun with(key: String, values: List<String>): FeedRules = copy(rules = (rules.filter { it.key != key } +
        listOfNotNull(FeedRule(key, values).takeIf { values.isNotEmpty() })).sortedBy { ORDER.indexOf(it.key) })
    fun without(key: String) = with(key, emptyList())

    /** The query string these rules save as. */
    fun query(): String = (rules.map { rule ->
        val terms = rule.values.map { term(rule.key, it) }
        if (terms.size == 1) terms[0] else "(" + terms.joinToString(" or ") + ")"
    } + listOfNotNull("reply:false".takeIf { !replies })).joinToString(" ")

    /** Does one of our own posts match? Names resolve as the evaluator does: a member, or a group. */
    fun matches(post: JournalPost, model: Model, now: Long): Boolean {
        if (post.repostOf != null) return false
        if (!replies && post.replyTo != null) return false
        for (rule in rules) {
            val any = rule.values.any { v ->
                when (rule.key) {
                    "from" -> fromIds(model, v).any { it in post.authors }
                    "tag" -> post.tags.any { it.trim().trimStart('#').equals(v, ignoreCase = true) }
                    "kind" -> post.kind == v
                    "mood" -> post.mood?.equals(v, ignoreCase = true) == true
                    "since" -> age(v)?.let { post.occurredAt >= now - it } == true
                    else -> false
                }
            }
            if (!any) return false
        }
        return true
    }

    /** "12 posts this week": our own posts only; what other systems share isn't on this device. */
    fun preview(model: Model, now: Long): Int =
        model.posts.count { it.occurredAt >= now - WEEK && matches(it, model, now) }

    companion object {
        val ORDER = listOf("from", "tag", "kind", "mood", "since")
        private const val WEEK = 7 * 86_400_000L

        private fun fromIds(model: Model, name: String): Set<String> {
            val n = name.trim().trimStart('@')
            val members = model.members.filter { it.name.equals(n, true) || it.displayName.equals(n, true) || model.handle(it.id).equals(n, true) }
                .map { it.id }
            val groups = model.groups.filter { it.name.equals(n, true) }.flatMap { model.membership[it.id].orEmpty() }
            return (members + groups).toSet()
        }

        fun age(v: String): Long? {
            val n = v.dropLast(1).toLongOrNull() ?: return null
            val unit = when (v.lastOrNull()) { 'm' -> 60_000L; 'h' -> 3_600_000L; 'd' -> 86_400_000L; 'w' -> 7 * 86_400_000L; 'y' -> 365 * 86_400_000L; else -> return null }
            return n * unit
        }

        private fun term(key: String, value: String): String {
            val plain = value.none { it.isWhitespace() || it in "()\"" }
            return if (key == "from") (if (plain) "from:@$value" else "from:\"$value\"") else (if (plain) "$key:$value" else "$key:\"$value\"")
        }

        /** Top-level pieces split at spaces outside quotes and parentheses; null if brackets don't pair. */
        private fun split(q: String): List<String>? {
            val out = ArrayList<String>(); val cur = StringBuilder(); var depth = 0; var quoted = false
            for (c in q) {
                when {
                    c == '"' -> { quoted = !quoted; cur.append(c) }
                    quoted -> cur.append(c)
                    c == '(' -> { depth++; cur.append(c) }
                    c == ')' -> { depth--; if (depth < 0) return null; cur.append(c) }
                    c.isWhitespace() && depth == 0 -> if (cur.isNotEmpty()) { out.add(cur.toString()); cur.clear() }
                    else -> cur.append(c)
                }
            }
            if (quoted || depth != 0) return null
            if (cur.isNotEmpty()) out.add(cur.toString())
            return out
        }

        private fun termOf(t: String): Pair<String, String>? {
            val i = t.indexOf(':').takeIf { it > 0 } ?: return null
            val key = t.substring(0, i).lowercase()
            var value = t.substring(i + 1)
            if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) value = value.substring(1, value.length - 1)
            if (key == "from") value = value.trimStart('@')
            if (key == "tag") value = value.trimStart('#')
            return if (value.isBlank()) null else key to value
        }

        /** The rows for [query], or null when it says more than rows can. */
        fun parse(query: String): FeedRules? {
            val tokens = split(query.trim()) ?: return null
            var rules = FeedRules(); var replies = true
            for (token in tokens) {
                val terms: List<Pair<String, String>> = if (token.startsWith("(") && token.endsWith(")")) {
                    val inner = split(token.substring(1, token.length - 1)) ?: return null
                    if (inner.size % 2 == 0 || inner.filterIndexed { i, _ -> i % 2 == 1 }.any { it != "or" }) return null
                    inner.filterIndexed { i, _ -> i % 2 == 0 }.map { termOf(it) ?: return null }
                } else listOf(termOf(token) ?: return null)
                val key = terms.first().first
                if (terms.any { it.first != key }) return null
                if (key == "reply") {
                    if (terms.size != 1 || terms[0].second.lowercase() !in setOf("false", "no", "0")) return null
                    replies = false; continue
                }
                if (key !in ORDER || rules.values(key).isNotEmpty()) return null
                if (key == "kind" && (terms.size != 1 || terms[0].second.lowercase() !in setOf("note", "entry"))) return null
                if (key == "since" && (terms.size != 1 || age(terms[0].second) == null)) return null
                rules = rules.with(key, terms.map { if (key == "kind") it.second.lowercase() else it.second })
            }
            return rules.copy(replies = replies)
        }
    }
}
