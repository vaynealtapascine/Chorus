package garden.vayne.chorus.data

/** Used by the real editor before and after staging. Resolve default intent once, at Post; a
 * front change during IO must never change the authors of that send. */
object JournalDraftSend {
    fun snapshot(model: Model, account: String, draft: JournalDraft): JournalDraft {
        val eligible = model.active.filter { it.createdByAccountId == null || it.createdByAccountId == account }
        val fallback = eligible.find { it.id == draft.authorHint } ?: Reply.speaker(model)?.takeIf { it in eligible } ?: eligible.firstOrNull()
        val authors = JournalSelection.authors(model.isPerson, draft.authors, fallback?.id.orEmpty())
        return draft.copy(authors = authors)
    }

    fun validate(model: Model, account: String, draft: JournalDraft, readable: (JournalDraftFile) -> Boolean) {
        require(draft.recovery == null) { "Review the recovered draft details before posting." }
        val authors = draft.authors.orEmpty()
        PostCompose.payload(model, account, draft.kind, authors.firstOrNull().orEmpty(), draft.body, draft.title,
            draft.cw, draft.audience, draft.mood, draft.tags, draft.reply, quotePostId = draft.quote,
            bucketIds = draft.buckets, authorIds = authors, attachmentIds = draft.files.map { it.uri })
        if (draft.poll) Polls.draft(draft.options, draft.multiple, draft.closesAt).getOrThrow()
        val missing = draft.files.filterNot(readable)
        require(missing.isEmpty()) { "Cannot open ${missing.joinToString { it.name }}. Your draft is kept; attach the files again or remove their references." }
    }
}
