package dev.warpterminal.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppearanceTest {
    @Test fun missingOrUnknownChoicesFallBackIndependently() {
        assertEquals(Appearance(), Appearance.restore(null, null))
        assertEquals(Appearance(size = TerminalSize.Large), Appearance.restore("unknown", "Large"))
        assertEquals(Appearance(palette = Palette.Paper), Appearance.restore("Paper", "old-size"))
    }

    @Test fun allPersistedChoicesRoundTrip() {
        for (palette in Palette.entries) for (size in TerminalSize.entries) {
            assertEquals(Appearance(palette, size), Appearance.restore(palette.name, size.name))
        }
    }

    @Test fun fullscreenSurvivesOnlyOnTheSameLiveTab() {
        assertEquals(7, fullscreenOwner(7, 7))
        assertNull(fullscreenOwner(null, 7))
        assertNull(fullscreenOwner(7, null))
        val afterSwitch = fullscreenOwner(7, 8)
        assertNull(afterSwitch)
        assertNull(fullscreenOwner(afterSwitch, 7))
    }
}
