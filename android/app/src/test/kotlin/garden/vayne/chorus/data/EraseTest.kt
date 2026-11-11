package garden.vayne.chorus.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EraseTest {
    @Test fun theMarkerIsSetFirstAndClearedOnlyWhenEveryStepWorked() {
        val log = mutableListOf<String>()
        val failed = Erase.run({ log += "mark" }, listOf(
            Erase.Step("store") { log += "store" },
            Erase.Step("files") { log += "files" },
        ), { log += "clear" })
        assertEquals(emptyList<String>(), failed)
        assertEquals(listOf("mark", "store", "files", "clear"), log)
    }

    @Test fun aFailingStepDoesntStopTheRestAndTheEraseGoesOnNextTime() {
        val log = mutableListOf<String>()
        val failed = Erase.run({ log += "mark" }, listOf(
            Erase.Step("store") { log += "store"; error("locked") },
            Erase.Step("files") { log += "files" },
        ), { log += "clear" }, log = { name, _ -> log += "failed $name" })
        assertEquals(listOf("store"), failed)
        // the marker stays: the next start erases again
        assertEquals(listOf("mark", "store", "failed store", "files"), log)
    }

    @Test fun libraryPreferencesStayOursGo() {
        for (name in listOf("chorus.erase", "androidx.work.util.preferences", "com.google.android.gms.appid", "FirebaseHeartBeatW0RFRkFVTFRd")) {
            assertTrue(name, Erase.keepsPrefs(name))
        }
        for (name in listOf("chorus.navigation", "chorus.setup", "quick_switch_pins", "quick_switch_widget", "garden.vayne.chorus_preferences")) {
            assertFalse(name, Erase.keepsPrefs(name))
        }
        // the account's work goes; the app's own update check stays
        assertTrue("sync-periodic" in Erase.WORK && "chorus-uploads" in Erase.WORK && "notify-check" in Erase.WORK)
        assertFalse("update-check" in Erase.WORK)
    }
}
