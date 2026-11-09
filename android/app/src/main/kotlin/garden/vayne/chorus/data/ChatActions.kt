package garden.vayne.chorus.data

/**
 * Which message actions show first (Figma F5.6: Reply, Reply in thread, Copy text, Pin, Forward,
 * Edit, Select, Delete; F8.1 puts Stage… above Delete) and which wait behind More, each in a fixed order.
 */
object ChatActions {
    val PRIMARY = listOf("reply", "thread", "copy", "pin", "forward", "edit", "select", "stage", "delete")
    val MORE = listOf("private", "reply_in", "quote", "link", "history", "unseen")

    data class Layout(val primary: List<String>, val more: List<String>)

    /** [available] split in two; a More holding a single action would cost a tap for nothing, so that
     * one action joins the first rows, just above Delete. */
    fun layout(available: Collection<String>): Layout {
        val primary = PRIMARY.filter { it in available }
        val more = MORE.filter { it in available }
        if (more.size != 1) return Layout(primary, more)
        val at = primary.indexOf("delete").takeIf { it >= 0 } ?: primary.size
        return Layout(primary.take(at) + more + primary.drop(at), emptyList())
    }
}
