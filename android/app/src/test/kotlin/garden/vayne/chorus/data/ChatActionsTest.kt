package garden.vayne.chorus.data

import org.junit.Assert.*
import org.junit.Test

class ChatActionsTest {
    @Test fun theFigmaEightComeFirstInTheirOrderAndTheRestWaitBehindMore() {
        val all = (ChatActions.PRIMARY + ChatActions.MORE).shuffled(java.util.Random(4))
        val layout = ChatActions.layout(all)
        assertEquals(listOf("reply", "thread", "copy", "pin", "forward", "edit", "select", "stage", "delete"), layout.primary)
        assertEquals(listOf("private", "reply_in", "quote", "link", "history", "unseen"), layout.more)
    }

    @Test fun onlyWhatIsAvailableShows() {
        val layout = ChatActions.layout(setOf("delete", "reply", "copy", "quote", "link", "stage"))
        assertEquals(listOf("reply", "copy", "stage", "delete"), layout.primary)
        assertEquals(listOf("quote", "link"), layout.more)
    }

    @Test fun aSingleExtraActionSitsAboveDeleteInsteadOfBehindMore() {
        assertEquals(ChatActions.Layout(listOf("reply", "quote", "delete"), emptyList()), ChatActions.layout(setOf("reply", "delete", "quote")))
        assertEquals(ChatActions.Layout(listOf("reply", "copy", "quote"), emptyList()), ChatActions.layout(setOf("copy", "reply", "quote")))
        assertEquals(ChatActions.Layout(listOf("reply"), emptyList()), ChatActions.layout(setOf("reply", "unknown")))
    }
}
