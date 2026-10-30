package garden.vayne.chorus.data

/** Counts and lookups behind the Journal action row, all from the local replica (offline-first). */
object JournalSocial {
    /** Live (not deleted) reposts of [postId]; the model already drops tombstoned posts. */
    fun reposts(posts: List<JournalPost>, postId: String): List<JournalPost> = posts.filter { it.repostOf == postId }

    fun replyCount(posts: List<JournalPost>, postId: String): Int = posts.count { it.replyTo == postId }

    /** The acting member's own repost of [postId], if any: what "Undo repost" deletes. */
    fun repostBy(posts: List<JournalPost>, postId: String, memberId: String): JournalPost? =
        reposts(posts, postId).firstOrNull { memberId in it.authors }

    /** Only a post written by one of this account's members can have its audience changed here. */
    fun canEditAudience(post: JournalPost, model: Model, accountId: String?): Boolean = post.authors.any { id ->
        model.member(id)?.let { it.createdByAccountId == null || it.createdByAccountId == accountId } == true
    }

    /** Newest first, the order of the Saved view. Reposts are never saved in place of the original. */
    fun saved(posts: List<JournalPost>, saved: Set<String>): List<JournalPost> =
        posts.filter { it.id in saved && it.repostOf == null }
            .sortedWith(compareByDescending<JournalPost> { it.occurredAt }.thenByDescending { it.id })

    /** Plain text for the Android share sheet: the content warning comes first so nothing is
     * shared without it, then the title and the body. */
    fun shareText(title: String?, cw: String?, text: String): String =
        listOfNotNull(cw?.takeIf { it.isNotBlank() }?.let { "Content warning: $it" },
            title?.takeIf { it.isNotBlank() }, text.takeIf { it.isNotBlank() }).joinToString("\n\n")
}
