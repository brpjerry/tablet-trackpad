package dev.tablettrackpad.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp

private const val SOURCE_URL = "https://github.com/brpjerry/tablet-trackpad"
private const val PRIVACY_URL = "$SOURCE_URL/blob/main/PRIVACY.md"
private const val GPL_URL = "https://www.gnu.org/licenses/gpl-3.0.html"
private const val APACHE_URL = "https://www.apache.org/licenses/LICENSE-2.0"

/** What the app does with its access, what it keeps, its license and where its source is. */
@Composable
internal fun AboutCard() {
    val context = LocalContext.current
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
    val uriHandler = LocalUriHandler.current
    // Links open in a browser; without one installed, the buttons do nothing.
    val open = { url: String -> runCatching { uriHandler.openUri(url) } }
    Section("About and privacy") {
        Hint("Version $version")
        Body("Tablet Trackpad lets one hand reach the whole screen of a large tablet.")

        Heading("Accessibility")
        Body(
            "The trackpad is an accessibility service because that is how Android lets an app draw over " +
                "other apps, tap and scroll where the cursor is, and go Back, Home or to Recents. It does " +
                "not read what is on your screen: it never asks Android for window content or " +
                "accessibility events.",
        )

        Heading("Privacy")
        Body(
            "Tablet Trackpad collects no data. It has no internet access and sends nothing anywhere. Its " +
                "settings stay on this tablet and are left out of Android backups and transfers. The " +
                "Diagnostics log is kept in memory and in the tablet's system log, and leaves the " +
                "tablet only if you copy or export it.",
        )

        Heading("License")
        Body(
            "Copyright (C) 2026 brpjerry. Tablet Trackpad is free software under the GNU General Public " +
                "License, version 3: you may share and change it under its terms. It comes with no " +
                "warranty.",
        )
        Body(
            "It is built with AndroidX, Jetpack Compose and the Kotlin libraries, which are licensed " +
                "under the Apache License 2.0.",
        )
        FlowRow(
            Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LinkButton("Source code") { open(SOURCE_URL) }
            LinkButton("Privacy policy") { open(PRIVACY_URL) }
            LinkButton("GPL 3.0") { open(GPL_URL) }
            LinkButton("Apache License 2.0") { open(APACHE_URL) }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        Modifier.padding(top = 12.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun LinkButton(label: String, onClick: () -> Unit) {
    OutlinedButton(shapes = ButtonDefaults.shapes(), onClick = onClick) { Text(label) }
}
