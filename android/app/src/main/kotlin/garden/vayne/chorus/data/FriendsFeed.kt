package garden.vayne.chorus.data

/**
 * Friends' posts in pages (owner, 2026-11-07: the feed used to stop at five per account). Each
 * followed account's pages so far are merged newest first, down to the frontier: the newest of the
 * oldest loaded posts among accounts with more to load, so an older post never appears above ones
 * already shown. The web's `friends-feed.ts` does the same.
 */
object FriendsFeed {
    /** Posts asked for per account and page (`GET /posts?account=&before=&limit=`). */
    const val PAGE = 20

    /** One account's pages; [more] when its last page was full. */
    data class Source(val accountId: String, val name: String, val posts: List<SharedPost>, val more: Boolean) {
        val oldest: Long? get() = posts.minOfOrNull { it.occurredAt }
    }

    fun frontier(sources: List<Source>): Long? = sources.filter { it.more }.mapNotNull { it.oldest }.maxOrNull()

    /** The accounts to ask for an older page next: those whose oldest loaded post is the frontier. */
    fun toLoad(sources: List<Source>): List<Int> {
        val frontier = frontier(sources) ?: return emptyList()
        return sources.indices.filter { sources[it].more && sources[it].oldest == frontier }
    }

    /** What to show: each post once, newest first, down to the frontier, with its account's name. A
     * repost has no words of its own and its original may be unreadable here, so it is left out. */
    fun shown(sources: List<Source>): List<Pair<String, SharedPost>> {
        val frontier = frontier(sources) ?: Long.MIN_VALUE
        val seen = HashSet<String>()
        return sources.flatMap { s -> s.posts.map { s.name to it } }
            .filter { (_, post) -> post.repostOf == null && post.occurredAt >= frontier && seen.add(post.id) }
            .sortedWith(compareByDescending<Pair<String, SharedPost>> { it.second.occurredAt }.thenByDescending { it.second.id })
    }
}
