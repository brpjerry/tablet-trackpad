package dev.tablettrackpad.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun settingsEndWithAboutAndPrivacy() {
        val settings = TrackpadSettings(ApplicationProvider.getApplicationContext())
        compose.setContent { TrackpadTheme { SettingsScreen(settings, onRunSetup = {}) } }
        compose.onNodeWithText("About and privacy").performScrollTo().assertExists()
        compose.onNodeWithText("Tablet Trackpad collects no data", substring = true).assertExists()
        compose.onNodeWithText("no warranty", substring = true).assertExists()
    }

    @Test
    fun aboutLinksOpenTheirPages() {
        val opened = mutableListOf<String>()
        val uris = object : UriHandler {
            override fun openUri(uri: String) {
                opened += uri
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides uris) {
                // Taller than the test screen: the buttons are scrolled to before they are tapped.
                TrackpadTheme { Column(Modifier.verticalScroll(rememberScrollState())) { AboutCard() } }
            }
        }
        for (label in listOf("Source code", "Privacy policy", "GPL 3.0", "Apache License 2.0")) {
            compose.onNodeWithText(label).performScrollTo().performClick()
        }
        assertEquals(
            listOf(
                "https://github.com/brpjerry/tablet-trackpad",
                "https://github.com/brpjerry/tablet-trackpad/blob/main/PRIVACY.md",
                "https://www.gnu.org/licenses/gpl-3.0.html",
                "https://www.apache.org/licenses/LICENSE-2.0",
            ),
            opened,
        )
    }
}
