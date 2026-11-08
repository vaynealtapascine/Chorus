package garden.vayne.chorus.data

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdaterWhatsNewTest {
    @Test
    fun keepsThreePlainLinesAndDropsCommitSubjects() {
        assertEquals(listOf("A Home page for every member", "Friends right now, at a glance", "Stage: fake names and times"),
            Updater.whatsNew("A Home page for every member\n\n# note\n- Friends right now, at a glance\nStage: fake names and times\nOne more"))
        assertEquals(emptyList<String>(), Updater.whatsNew("feat(web): a thing\nfix: another\nwip: half\ndocs(android)!: x"))
        assertEquals(emptyList<String>(), Updater.whatsNew(""))
    }
}
