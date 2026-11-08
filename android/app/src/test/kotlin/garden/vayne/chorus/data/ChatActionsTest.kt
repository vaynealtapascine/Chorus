package garden.vayne.chorus.data

import org.junit.Assert.*
import org.junit.Test

class ChatActionsTest {
    @Test fun theFigmaEightComeFirstInTheirOrderAndTheRestWaitBehindMore() {
        val all = (ChatActions.PRIMARY + ChatActions.MORE).shuffled(java.util.Random(4))
        val layout = ChatActions.layout(all)
        assertEquals(listOf("reply", "thread", "copy", "pin", "forward", "edit", "select", "delete"), layout.primary)
        assertEquals(listOf("private", "reply_in", "quote", "link", "history", "stage", "unseen"), layout.more)
    }

    @Test fun onlyWhatIsAvailableShows() {
        val layout = ChatActions.layout(setOf("delete", "reply", "copy", "quote", "stage"))
        assertEquals(listOf("reply", "copy", "delete"), layout.primary)
        assertEquals(listOf("quote", "stage"), layout.more)
    }

    @Test fun aSingleExtraActionSitsAboveDeleteInsteadOfBehindMore() {
        assertEquals(ChatActions.Layout(listOf("reply", "stage", "delete"), emptyList()), ChatActions.layout(setOf("reply", "delete", "stage")))
        assertEquals(ChatActions.Layout(listOf("reply", "copy", "stage"), emptyList()), ChatActions.layout(setOf("copy", "reply", "stage")))
        assertEquals(ChatActions.Layout(listOf("reply"), emptyList()), ChatActions.layout(setOf("reply", "unknown")))
    }
}
