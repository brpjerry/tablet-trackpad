package dev.tablettrackpad.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.tablettrackpad.debug.DebugLog
import dev.tablettrackpad.playground.PlaygroundActivity
import dev.tablettrackpad.service.TrackpadService
import kotlin.math.max
import kotlin.math.roundToInt

/** [settings] as of one preferences revision; a new instance per change makes sections recompose. */
internal class SettingsRevision(val settings: TrackpadSettings)

/** The settings; [onRunSetup] opens setup, at the service step when [onRunSetup]'s argument is true. */
@Composable
fun SettingsScreen(settings: TrackpadSettings, onRunSetup: (fromService: Boolean) -> Unit) {
    val context = LocalContext.current
    val prefsRevision = rememberPrefsRevision(settings.prefs)
    val revision = remember(prefsRevision) { SettingsRevision(settings) }
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose {}
    }
    val serviceRunning by TrackpadService.running.collectAsState()
    val serviceEnabled = remember(resumes, serviceRunning) { isServiceEnabled(context) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val twoColumns = maxWidth > 900.dp
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Tablet Trackpad", style = MaterialTheme.typography.headlineMediumEmphasized)
                ServiceCard(revision, serviceEnabled, serviceRunning, onRunSetup)
                if (twoColumns) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            TrackpadCard(revision, serviceRunning)
                            AutoHideCard(revision)
                            PointerCard(revision)
                            ClickingCard(revision)
                            ScrollingCard(revision)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            AppearanceSection(revision)
                            EdgeSwipeCard(revision)
                            GesturesCard(revision)
                            HelpCard(revision)
                            DiagnosticsCard()
                        }
                    }
                } else {
                    TrackpadCard(revision, serviceRunning)
                    AutoHideCard(revision)
                    PointerCard(revision)
                    ClickingCard(revision)
                    ScrollingCard(revision)
                    AppearanceSection(revision)
                    EdgeSwipeCard(revision)
                    GesturesCard(revision)
                    HelpCard(revision)
                    DiagnosticsCard()
                }
                AboutCard()
            }
        }
    }
}

@Composable
private fun ServiceCard(revision: SettingsRevision, enabled: Boolean, running: Boolean, onRunSetup: (Boolean) -> Unit) {
    val context = LocalContext.current
    val s = revision.settings
    Section("Service") {
        when {
            running -> {
                Text("The trackpad service is running.", style = MaterialTheme.typography.bodyMedium)
                SwitchRow("Show trackpad", s.visible, "Or swipe in from a screen edge") { s.visible = it }
            }
            enabled -> Text(
                "The service is switched on but not connected. Turn it off and on again in Accessibility settings.",
                style = MaterialTheme.typography.bodyMedium,
            )
            else -> Text(
                "The trackpad service is off. It runs as an accessibility service, which you switch on in " +
                    "Android's settings; setup walks you through it.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (running) {
                OutlinedButton(shapes = ButtonDefaults.shapes(), onClick = { onRunSetup(false) }) { Text("Setup guide") }
            } else {
                Button(shapes = ButtonDefaults.shapes(), onClick = { onRunSetup(true) }) { Text("Turn it on") }
            }
            OutlinedButton(shapes = ButtonDefaults.shapes(), onClick = {
                context.startActivity(Intent(context, PlaygroundActivity::class.java))
            }) { Text("Test area") }
        }
    }
}

@Composable
private fun TrackpadCard(revision: SettingsRevision, running: Boolean) {
    val s = revision.settings
    val window = LocalWindowInfo.current.containerSize
    val maxDp = with(LocalDensity.current) { max(window.width, window.height).toDp().value }.coerceAtLeast(400f)
    Section("Trackpad") {
        if (!running) Hint("Size changes apply once the service is running.")
        SliderRow("Width", s.widthDp, 200f..maxDp, { "${it.roundToInt()} dp" }) { s.widthDp = it }
        SliderRow("Height", s.heightDp, 140f..maxDp, { "${it.roundToInt()} dp" }) { s.heightDp = it }
        Hint("Drag the bar along the top of the trackpad to move it, or the bottom-right corner to resize it.")
        TextButton(shapes = ButtonDefaults.shapes(), onClick = { s.resetLayout() }) { Text("Reset size and position") }
    }
}

@Composable
private fun AutoHideCard(revision: SettingsRevision) {
    val s = revision.settings
    Section("Auto-hide") {
        Hint("Hidden parts stay where they are and keep working. Touching the trackpad brings it back.")
        SwitchRow(
            "Hide during fullscreen content",
            s.hideInFullscreen,
            "While an app is fullscreen, like a video or a game, hide the trackpad, cursor and edge zone " +
                "markers, whatever the settings below say. Edge swipes still work.",
        ) { s.hideInFullscreen = it }
        SwitchRow(
            "Hide trackpad when idle",
            s.padAutoHide,
            "Fades the trackpad out when you haven't touched it for a while. An edge swipe also brings it back.",
        ) { s.padAutoHide = it }
        if (s.padAutoHide || s.hideInFullscreen) {
            SliderRow("Trackpad hides after", s.padHideDelaySeconds, 1f..30f, { "${it.roundToInt()} s" }) {
                s.padHideDelaySeconds = it.roundToInt().toFloat()
            }
        }
        SwitchRow(
            "Hide cursor when idle",
            s.cursorAutoHide,
            "Fades the cursor out when you haven't touched the trackpad for a while.",
        ) { s.cursorAutoHide = it }
        if (s.cursorAutoHide || s.hideInFullscreen) {
            SliderRow("Cursor hides after", s.cursorHideDelaySeconds, 1f..30f, { "${it.roundToInt()} s" }) {
                s.cursorHideDelaySeconds = it.roundToInt().toFloat()
            }
        }
    }
}

@Composable
private fun PointerCard(revision: SettingsRevision) {
    val s = revision.settings
    Section("Pointer") {
        SliderRow("Speed", s.pointerSpeed, 0.5f..5f, { "%.1f×".format(it) }) { s.pointerSpeed = it }
        SliderRow("Acceleration", s.acceleration, 0f..1f, { "${(it * 100).roundToInt()}%" }) { s.acceleration = it }
        SliderRow("Cursor size", s.cursorScale, 0.6f..2.5f, { "%.1f×".format(it) }) { s.cursorScale = it }
    }
}

@Composable
private fun ClickingCard(revision: SettingsRevision) {
    val s = revision.settings
    Section("Clicking") {
        ChoiceRow("Long press", LongPressGesture.entries, s.longPress, { it.label }, stacked = true) { s.longPress = it }
        Hint(
            when (s.longPress) {
                LongPressGesture.TWO_FINGER_TAP ->
                    "Tap with two fingers to long-press, Android's right click: menus, selecting text."
                LongPressGesture.DOUBLE_TAP_HOLD ->
                    "Tap, then touch again and hold still to long-press, Android's right click. Keep holding " +
                        "and move to drag what you pressed. Two-finger taps do nothing."
            },
        )
        SwitchRow(
            "Tap and drag",
            s.tapAndDrag,
            "Tap, then touch again and move to drag. Delays single clicks by about 0.2 s.",
        ) { s.tapAndDrag = it }
        SwitchRow("Haptic feedback", s.haptics) { s.haptics = it }
    }
}

@Composable
private fun ScrollingCard(revision: SettingsRevision) {
    val s = revision.settings
    Section("Scrolling") {
        SliderRow("Scroll speed", s.scrollSpeed, 0.5f..4f, { "%.1f×".format(it) }) { s.scrollSpeed = it }
        SwitchRow("Natural scrolling", s.naturalScroll, "Content follows your fingers") { s.naturalScroll = it }
        SwitchRow(
            "Live scroll, drag and pinch",
            s.liveScroll,
            "Follow your fingers while they move. Turned off automatically if Android cancels the " +
                "trackpad touch; when off, scrolls, drags and pinches play when you lift your fingers.",
        ) { s.liveScroll = it }
        SwitchRow("Momentum", s.momentum, "Keep scrolling after a quick flick") { s.momentum = it }
        SwitchRow(
            "Scroll zone",
            s.scrollZone,
            "Slide one finger up or down along the edge of the trackpad to scroll faster",
        ) { s.scrollZone = it }
        if (s.scrollZone) {
            ChoiceRow("Scroll zone side", ScrollZoneSide.entries, s.scrollZoneSide, { it.label }) { s.scrollZoneSide = it }
            SliderRow("Scroll zone speed", s.scrollZoneSpeed, 1.5f..8f, { "%.1f× normal".format(it) }) {
                s.scrollZoneSpeed = it
            }
        }
    }
}

@Composable
private fun GesturesCard(revision: SettingsRevision) {
    val s = revision.settings
    Section("Gestures") {
        SwitchRow("Pinch to zoom", s.pinchZoom, "Zooms around the cursor") { s.pinchZoom = it }
        ActionRow("Three-finger swipe up", s.threeFingerUp) { s.threeFingerUp = it }
        ActionRow("Three-finger swipe down", s.threeFingerDown) { s.threeFingerDown = it }
        ActionRow("Three-finger swipe left", s.threeFingerLeft) { s.threeFingerLeft = it }
        ActionRow("Three-finger swipe right", s.threeFingerRight) { s.threeFingerRight = it }
        ActionRow("Three-finger tap", s.threeFingerTap) { s.threeFingerTap = it }
    }
}

@Composable
private fun EdgeSwipeCard(revision: SettingsRevision) {
    val s = revision.settings
    Section("Edge swipe") {
        Hint("Swipe inward from a screen edge, inside the marked zone, to show or hide the trackpad.")
        SwitchRow("Left edge", s.edgeLeft) { s.edgeLeft = it }
        SwitchRow("Right edge", s.edgeRight) { s.edgeRight = it }
        SliderRow("Zone height", s.edgeZoneHeightDp, 80f..600f, { "${it.roundToInt()} dp" }) { s.edgeZoneHeightDp = it }
        Hint("Android keeps the system back gesture out of at most 200 dp per edge; above that, swipes in the zone may also go back.")
        SliderRow("Zone position", s.edgeZonePosition, 0f..1f, { "${(it * 100).roundToInt()}% down" }) {
            s.edgeZonePosition = it
        }
        SliderRow("Zone width", s.edgeWidthDp, 6f..40f, { "${it.roundToInt()} dp" }) { s.edgeWidthDp = it }
        SwitchRow("Show zone markers", s.edgeHint) { s.edgeHint = it }
    }
}

@Composable
private fun HelpCard(revision: SettingsRevision) {
    Section("Gestures cheat sheet") { GestureList(revision.settings.longPress) }
}

@Composable
private fun DiagnosticsCard() {
    val context = LocalContext.current
    val version by DebugLog.version.collectAsState()
    val lines = remember(version) { DebugLog.snapshot().takeLast(80).asReversed() }
    Section("Diagnostics") {
        Hint("Recent events, newest first. Also in logcat under the tag ${DebugLog.TAG}.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(shapes = ButtonDefaults.shapes(), onClick = { copyLog(context) }) { Text("Copy log") }
            OutlinedButton(shapes = ButtonDefaults.shapes(), onClick = { DebugLog.clear() }) { Text("Clear") }
        }
        Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            if (lines.isEmpty()) Hint("Nothing logged yet.")
            for (line in lines) {
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 15.sp)
            }
        }
    }
}

private fun copyLog(context: Context) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("Tablet Trackpad log", DebugLog.snapshot().joinToString("\n")))
}

@Composable
internal fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth().widthIn(max = 900.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

@Composable
internal fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, summary: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (summary != null) Hint(summary)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    label: (Float) -> String,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(label(value), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TrackpadSlider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun ActionRow(title: String, value: GestureAction, onChange: (GestureAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Box {
            OutlinedButton(shapes = ButtonDefaults.shapes(), onClick = { open = true }) { Text(value.label) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (action in GestureAction.entries) {
                    DropdownMenuItem(text = { Text(action.label) }, onClick = {
                        onChange(action)
                        open = false
                    })
                }
            }
        }
    }
}

/**
 * A single-choice connected button group beside [title], or below it and full width when
 * [stacked], for long labels.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun <T> ChoiceRow(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    stacked: Boolean = false,
    onSelect: (T) -> Unit,
) {
    val buttons = @Composable { modifier: Modifier ->
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
            options.forEachIndexed { index, option ->
                ToggleButton(
                    checked = option == selected,
                    onCheckedChange = { onSelect(option) },
                    modifier = (if (stacked) Modifier.weight(1f) else Modifier).semantics { role = Role.RadioButton },
                    shapes = when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                ) { Text(label(option), maxLines = 1) }
            }
        }
    }
    if (stacked) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            buttons(Modifier.fillMaxWidth())
        }
    } else {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            buttons(Modifier)
        }
    }
}

/** Increments whenever any preference changes, so readers of [prefs] recompose. */
@Composable
internal fun rememberPrefsRevision(prefs: SharedPreferences): Int {
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return revision
}
