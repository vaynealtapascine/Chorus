package garden.vayne.chorus.ui

import garden.vayne.chorus.designsystem.Appearance
import garden.vayne.chorus.designsystem.ChorusAccent
import garden.vayne.chorus.designsystem.Spacing
import garden.vayne.chorus.designsystem.TextSize
import garden.vayne.chorus.designsystem.ThemeMode
import garden.vayne.chorus.designsystem.Tokens
import garden.vayne.chorus.designsystem.withAccent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceTest {
    @Test fun nothingStoredIsDarkTealComfortableWithMemberColours() {
        val a = Appearance.read { null }
        assertEquals(ThemeMode.Dark, a.mode)
        assertEquals(ChorusAccent.Teal, a.accent)
        assertTrue(a.memberColours)
        assertEquals(TextSize.Default, a.textSize)
        assertEquals(Spacing.Comfortable, a.spacing)
    }

    @Test fun storedValuesRoundTripAndUnknownOnesFallBack() {
        val a = Appearance(ThemeMode.Light, ChorusAccent.Sky, false, TextSize.Larger, Spacing.Compact)
        val stored = a.stored()
        // a device that chose a theme before F10.2 kept only "theme"
        assertEquals("Light", stored["theme"])
        assertEquals(a, Appearance.read { stored[it] })
        val odd = Appearance.read { mapOf("theme" to "Neon", "accent" to "Mauve", "text_size" to "Huge")[it] }
        assertEquals(ThemeMode.Dark, odd.mode); assertEquals(ChorusAccent.Teal, odd.accent); assertEquals(TextSize.Default, odd.textSize)
    }

    @Test fun tealIsTheBasePaletteAndOtherAccentsSwapOnlyTheAccentColours() {
        assertEquals(Tokens.Dark, Tokens.Dark.withAccent(ChorusAccent.Teal, true))
        assertEquals(Tokens.Light, Tokens.Light.withAccent(ChorusAccent.Teal, false))
        val sky = Tokens.Dark.withAccent(ChorusAccent.Sky, true)
        assertEquals(ChorusAccent.Sky.dark.accent, sky.accent)
        assertEquals(Tokens.Dark.copy(accent = sky.accent, accentSoft = sky.accentSoft, onAccent = sky.onAccent, bubbleOut = sky.bubbleOut), sky)
    }

    @Test fun moreRowsSayTheirStateInFewWords() {
        assertEquals("Dark · teal · comfortable", appearanceSummary(ThemeMode.Dark, ChorusAccent.Teal, Spacing.Comfortable))
        assertEquals("Device · lime · compact", appearanceSummary(ThemeMode.System, ChorusAccent.Lime, Spacing.Compact))
        assertEquals("4 members · 1 group", countLine(4, "member", 1, "group"))
        assertEquals("1 member", countLine(1, "member", 0, "group"))
        assertNull(countLine(0, "member", 0, "group"))
    }
}
