package garden.vayne.chorus.ui

/**
 * Which panes the Android chat shows at a window width, and how wide they are (F5.21).
 *
 * Wide means 840 dp and up; below that the phone layout applies and nothing here is consulted. The
 * app shell shows a [RAIL] dp icon rail from the same breakpoint, so the chat gets the width that is
 * left. Figma's 1280 frame is rail 76 + list 344 + chat 560 + info 300; the rail is 56 dp here (icons
 * only) and the list and info widths step down on narrower windows so the conversation never drops
 * below [MIN_CHAT] dp. The info pane stays open at every wide width, as it always has.
 */
data class WideLayout(val listDp: Int, val infoDp: Int, val chatDp: Int) {
    companion object {
        const val BREAKPOINT = 840
        const val RAIL = 56
        const val LIST_FULL = 340
        const val LIST_COMPACT = 240
        const val INFO_FULL = 300
        const val INFO_COMPACT = 260
        /** Both of the roomier widths are used once the chat would still keep this much. */
        const val MIN_CHAT = 320
        private const val DIVIDERS = 2

        fun isWide(widthDp: Int) = widthDp >= BREAKPOINT

        /** [widthDp] is the whole window; the rail is taken off before the panes are sized. */
        fun of(widthDp: Int): WideLayout {
            val room = widthDp - RAIL - DIVIDERS
            val full = room - LIST_FULL - INFO_FULL
            val list: Int
            val info: Int
            if (full >= 460) { list = LIST_FULL; info = INFO_FULL }
            else if (room - LIST_FULL - INFO_COMPACT >= MIN_CHAT) { list = LIST_FULL; info = INFO_COMPACT }
            else { list = LIST_COMPACT; info = INFO_COMPACT }
            return WideLayout(list, info, room - list - info)
        }
    }
}
