package garden.vayne.chorus.data

/** Member-only messages are a soft local view rule, matching the web client. */
fun memberVisible(message: ChatMessage, spaceKind: String, front: List<Entry>, viewingAs: String?): Boolean {
    if (message.visibilityMode != "members" || spaceKind != "internal") return true
    if (viewingAs != null && viewingAs in message.visibleMemberIds) return true
    return front.any { it.subjectType == "member" && (it.level == "front" || it.level == "cocon") && it.subjectId in message.visibleMemberIds }
}

/** Old replicas may contain a foreign shared-space aside from before server fan-out filtering. */
fun accountVisible(message: ChatMessage, spaceKind: String, accountId: String): Boolean =
    message.visibilityMode != "system_only" || spaceKind == "internal" || message.accountId == null || message.accountId == accountId
