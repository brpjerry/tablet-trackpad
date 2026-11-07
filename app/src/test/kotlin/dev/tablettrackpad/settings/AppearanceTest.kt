package dev.tablettrackpad.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceTest {
    @Test
    fun opacityBecomesTheAlphaChannel() {
        assertEquals(0x80123456.toInt(), argb(0x123456, 0.5f))
        assertEquals(0x00FFFFFF, argb(0xFFFFFF, 0f))
        // Out-of-range opacity clamps, and any alpha already in the color is dropped.
        assertEquals(0xFF000000.toInt(), argb(0x7F000000, 1.4f))
    }

    @Test
    fun everyPartHasItsOwnKeys() {
        assertEquals(OverlayColor.entries.size + OverlayPart.entries.size, TrackpadSettings.APPEARANCE_KEYS.size)
        // The surface reuses the old single opacity setting, which no longer moves the window.
        assertTrue("opacity" in TrackpadSettings.APPEARANCE_KEYS)
        assertTrue(TrackpadSettings.LAYOUT_KEYS.none { it in TrackpadSettings.APPEARANCE_KEYS })
    }
}
