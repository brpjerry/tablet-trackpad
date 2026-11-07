package dev.tablettrackpad.settings

import android.app.StatusBarManager
import android.content.Intent
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.tablettrackpad.playground.PlaygroundActivity
import dev.tablettrackpad.service.TrackpadService
import kotlin.math.min
import kotlinx.coroutines.delay

enum class SetupStep { WELCOME, SERVICE, BATTERY, TILE, TRY }

/** The steps this device shows: adding the tile from an app needs Android 13. */
val setupSteps: List<SetupStep> =
    SetupStep.entries.filter { it != SetupStep.TILE || Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU }

/**
 * First-run setup: switching the accessibility service on, keeping it from being put to sleep,
 * the Quick Settings tile, and a first edge swipe. Steps that are already done show as done;
 * a step completed while it is on screen moves on by itself.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SetupScreen(settings: TrackpadSettings, step: Int, onStepChange: (Int) -> Unit, onFinish: () -> Unit) {
    val index = step.coerceIn(0, setupSteps.lastIndex)
    val current = setupSteps[index]
    val progress by animateFloatAsState(
        (index + 1f) / setupSteps.size,
        MaterialTheme.motionScheme.slowSpatialSpec(),
        label = "setup progress",
    )

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            // Side by side only in landscape; a portrait tablet reads better as one column.
            val wide = maxWidth >= WIDE_LAYOUT && maxWidth > maxHeight
            val content: @Composable ColumnScope.() -> Unit = {
                Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Setup · step ${index + 1} of ${setupSteps.size}",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (current != SetupStep.TRY) TextButton(shapes = ButtonDefaults.shapes(), onClick = onFinish) { Text("Skip setup") }
                }
                LinearWavyProgressIndicator(progress = { progress }, Modifier.fillMaxWidth())
                StepTransition(current) { shown ->
                    // Each step navigates from its own position, also while it animates out.
                    val i = setupSteps.indexOf(shown)
                    val next = { if (i < setupSteps.lastIndex) onStepChange(i + 1) else onFinish() }
                    val back = { if (i > 0) onStepChange(i - 1) }
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (!wide) SetupSketch(shown, 170.dp)
                        when (shown) {
                            SetupStep.WELCOME -> WelcomeStep(next)
                            SetupStep.SERVICE -> ServiceStep(settings, next, back)
                            SetupStep.BATTERY -> BatteryStep(next, back)
                            SetupStep.TILE -> TileStep(settings, next, back)
                            SetupStep.TRY -> TryStep(settings, onFinish, back, onTurnOn = {
                                onStepChange(setupSteps.indexOf(SetupStep.SERVICE))
                            })
                        }
                    }
                }
            }
            if (wide) {
                // Text on the left, picture on the right: the trackpad opens bottom-right, over the picture.
                Row(Modifier.fillMaxSize().padding(horizontal = 32.dp), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                    StepColumn(Modifier.weight(1f).widthIn(max = 640.dp), content)
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        StepTransition(current) { SetupSketch(it, 300.dp) }
                    }
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    StepColumn(Modifier.widthIn(max = 640.dp).padding(horizontal = 24.dp), content)
                }
            }
        }
    }
}

private val WIDE_LAYOUT = 900.dp

/** Slides a new step in from the side it comes from, with the theme's expressive motion. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StepTransition(step: SetupStep, content: @Composable (SetupStep) -> Unit) {
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    AnimatedContent(
        targetState = step,
        transitionSpec = {
            val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
            (slideInHorizontally(spatial) { it / 5 * direction } + fadeIn(fade)) togetherWith
                (slideOutHorizontally(spatial) { -it / 5 * direction } + fadeOut(fade))
        },
        label = "setup step",
    ) { content(it) }
}

/** The step's text and controls, vertically centered when they fit and scrolling when they don't. */
@Composable
private fun StepColumn(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        // The scroll container passes the screen height on as a minimum, which is what centers it.
        modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        content = content,
    )
}

@Composable
private fun WelcomeStep(next: () -> Unit) {
    StepText(
        "A trackpad for your tablet",
        "Tablet Trackpad puts a translucent, laptop-style trackpad over any app. Move the cursor with " +
            "one finger, tap to click, and scroll or zoom with two fingers.",
    )
    Bullets(
        "Setup takes about a minute: you switch the trackpad on in Android's settings, then try it.",
        "Android will ask you to give it **full control of your device**. That is what lets it tap and " +
            "scroll where the cursor is. It does not read what is on your screen and has no internet access.",
    )
    NavRow(back = null) { Button(shapes = ButtonDefaults.shapes(), onClick = next) { Text("Get started") } }
}

@Composable
private fun ServiceStep(settings: TrackpadSettings, next: () -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    val running by TrackpadService.running.collectAsState()
    val resumes = rememberResumeCount()
    val enabled = remember(resumes, running) { isServiceEnabled(context) }
    val restricted = remember { isRestrictedInstall(context) }
    AdvanceWhenDone(running, next)

    StepText(
        "Turn on the trackpad service",
        "The trackpad runs as an accessibility service, which only you can switch on, in Accessibility settings.",
    )
    if (!running) {
        val list = if (isSamsung()) "**Installed apps**" else "**Downloaded apps** (or **Installed apps**)"
        Numbered(
            "Tap **Open Accessibility settings** below.",
            "Tap $list, then **Tablet Trackpad**.",
            "Turn the switch on.",
            "When Android asks to allow full control of your device, tap **Allow**. This screen then comes back by itself.",
        )
    }
    when {
        running -> StatusLine(true, "Tablet Trackpad is on")
        enabled -> StatusLine(false, "Switched on, but not running yet. If this stays, turn it off and on again.")
        else -> StatusLine(false, "Not on yet")
    }
    if (!running) RestrictedHelp(expanded = restricted)
    NavRow(back) {
        if (running) {
            Button(shapes = ButtonDefaults.shapes(), onClick = next) { Text("Next") }
        } else {
            Button(shapes = ButtonDefaults.shapes(), onClick = {
                settings.setupReturnAt = System.currentTimeMillis()
                openAccessibilitySettings(context)
            }) { Text("Open Accessibility settings") }
        }
    }
}

/** Android greys out the switch for apps installed from an APK file until the user allows it. */
@Composable
private fun RestrictedHelp(expanded: Boolean) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(expanded) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Is Tablet Trackpad greyed out?", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                TextButton(shapes = ButtonDefaults.shapes(), onClick = { open = !open }) { Text(if (open) "Hide" else "Show how to fix") }
            }
            if (open) {
                Text(
                    "Android blocks accessibility for apps installed from a downloaded file, as a precaution, " +
                        "until you allow it:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Numbered(
                    "In Accessibility settings, tap the greyed-out **Tablet Trackpad** or its switch once. Android " +
                        "says the setting is restricted; close that message.",
                    "Open **App info** below, tap **⋮** in the top corner, then **Allow restricted settings**, and confirm.",
                    "Come back here and turn the trackpad on as above.",
                )
                OutlinedButton(shapes = ButtonDefaults.shapes(), onClick = { openAppInfo(context) }) { Text("Open App info") }
            }
        }
    }
}

@Composable
private fun BatteryStep(next: () -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    val resumes = rememberResumeCount()
    val unrestricted = remember(resumes) { isIgnoringBatteryOptimizations(context) }
    AdvanceWhenDone(unrestricted, next)

    StepText(
        "Keep it running",
        if (isSamsung()) {
            "Galaxy devices put apps to sleep to save battery, which can switch the trackpad off. Let " +
                "Tablet Trackpad run without battery restrictions so it is there when you swipe for it."
        } else {
            "Let Tablet Trackpad run without battery restrictions, so Android does not stop it in the background."
        },
    )
    RichHint("It does no work while you are not using it, so this costs no battery.")
    StatusLine(unrestricted, if (unrestricted) "Unrestricted" else "Battery optimized")
    NavRow(back) {
        if (unrestricted) {
            Button(shapes = ButtonDefaults.shapes(), onClick = next) { Text("Next") }
        } else {
            TextButton(shapes = ButtonDefaults.shapes(), onClick = next) { Text("Skip") }
            Button(shapes = ButtonDefaults.shapes(), onClick = { requestIgnoreBatteryOptimizations(context) }) { Text("Allow") }
        }
    }
}

@Composable
private fun TileStep(settings: TrackpadSettings, next: () -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    val revision = rememberPrefsRevision(settings.prefs)
    val added = remember(revision) { settings.tileAdded }
    var declined by remember { mutableStateOf(false) }
    AdvanceWhenDone(added, next)

    StepText(
        "Add a Quick Settings tile",
        "Show or hide the trackpad from Quick Settings, from any app. Edge swipes do the same, so this is optional.",
    )
    StatusLine(added, if (added) "Tile added" else "No tile yet")
    if (declined) RichHint("Not added. You can still add it later by editing Quick Settings.")
    NavRow(back) {
        if (added) {
            Button(shapes = ButtonDefaults.shapes(), onClick = next) { Text("Next") }
        } else {
            TextButton(shapes = ButtonDefaults.shapes(), onClick = next) { Text("Skip") }
            Button(shapes = ButtonDefaults.shapes(), onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestAddTile(context) { result ->
                        val ok = result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                            result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
                        if (ok) settings.tileAdded = true
                        declined = !ok
                    }
                }
            }) { Text("Add tile") }
        }
    }
}

@Composable
private fun TryStep(settings: TrackpadSettings, finish: () -> Unit, back: () -> Unit, onTurnOn: () -> Unit) {
    val context = LocalContext.current
    val running by TrackpadService.running.collectAsState()
    val revision = rememberPrefsRevision(settings.prefs)
    val visible = remember(revision) { settings.visible }
    val visibleOnArrival = remember { visible }
    var toggled by remember { mutableStateOf(false) }
    LaunchedEffect(visible) { if (visible != visibleOnArrival) toggled = true }

    StepText("Try it", edgeSwipeHowTo(settings))
    if (!running) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "The trackpad service is off, so the trackpad cannot appear yet.",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(shapes = ButtonDefaults.shapes(), onClick = onTurnOn) { Text("Turn it on") }
            }
        }
    } else {
        when {
            toggled && visible -> StatusLine(true, "There it is. Swipe in again to hide it.")
            toggled -> StatusLine(true, "Hidden. Swipe in again whenever you need it.")
            else -> StatusLine(false, "Waiting for your swipe")
        }
        SwitchLine("Show trackpad", visible) { settings.visible = it }
    }
    RichHint(
        "On the trackpad, drag the top bar to move it and the bottom-right corner to resize it. " +
            "**⋯** opens these settings and **×** hides it.",
    )
    NavRow(back) {
        OutlinedButton(shapes = ButtonDefaults.shapes(), onClick = { context.startActivity(Intent(context, PlaygroundActivity::class.java)) }) {
            Text("Practice")
        }
        Button(shapes = ButtonDefaults.shapes(), onClick = finish) { Text("Done") }
    }
    Text("Gestures", style = MaterialTheme.typography.titleMedium)
    GestureList(remember(revision) { settings.longPress })
    RichHint("Speed, scrolling, the scroll zone and three-finger actions are in the settings.")
}

/** How to swipe the trackpad in, given which edges are on and where their zones sit. */
private fun edgeSwipeHowTo(settings: TrackpadSettings): String {
    val where = when {
        settings.edgeZonePosition < 0.34f -> "near the top"
        settings.edgeZonePosition > 0.66f -> "near the bottom"
        else -> "halfway down"
    }
    val edge = when {
        settings.edgeLeft && settings.edgeRight -> "the left or right edge"
        settings.edgeLeft -> "the left edge"
        settings.edgeRight -> "the right edge"
        else -> return "Edge swipes are switched off in the settings, so use the switch below to show the trackpad."
    }
    return "Swipe in from $edge of the screen, starting on the thin marker $where, to show the trackpad. " +
        "Swipe in the same way to hide it."
}

/** Moves on shortly after [done] turns true on this step, but not if it was already true on arrival. */
@Composable
private fun AdvanceWhenDone(done: Boolean, next: () -> Unit) {
    val doneOnArrival = remember { done }
    val currentNext by rememberUpdatedState(next)
    if (done && !doneOnArrival) {
        LaunchedEffect(Unit) {
            delay(900)
            currentNext()
        }
    }
}

@Composable
private fun rememberResumeCount(): Int {
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose {}
    }
    return resumes
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StepText(title: String, body: String) {
    Text(title, style = MaterialTheme.typography.headlineMediumEmphasized)
    Text(body, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun RichHint(text: String) {
    Text(rich(text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Bullets(vararg items: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (item in items) {
            Row {
                Text("•", Modifier.width(20.dp), style = MaterialTheme.typography.bodyLarge)
                Text(rich(item), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun Numbered(vararg items: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEachIndexed { i, item ->
            Row {
                Text("${i + 1}.", Modifier.width(24.dp), style = MaterialTheme.typography.bodyLarge)
                Text(rich(item), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StatusLine(done: Boolean, text: String) {
    val color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val mark = MaterialTheme.colorScheme.onPrimary
    // Done pops in as a scalloped badge, turning as it grows.
    val shown by animateFloatAsState(if (done) 1f else 0f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "done")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(22.dp)) {
                drawCircle(color, radius = size.minDimension / 2 - 1.dp.toPx(), style = Stroke(2.dp.toPx()))
            }
            Box(
                Modifier
                    .size(26.dp)
                    .graphicsLayer {
                        scaleX = shown
                        scaleY = shown
                        rotationZ = (1f - shown) * -120f
                    }
                    .background(color, MaterialShapes.Cookie9Sided.toShape()),
            )
            Canvas(Modifier.size(26.dp).graphicsLayer { alpha = shown.coerceIn(0f, 1f) }) {
                val check = Path().apply {
                    moveTo(size.width * 0.3f, size.height * 0.52f)
                    lineTo(size.width * 0.45f, size.height * 0.66f)
                    lineTo(size.width * 0.71f, size.height * 0.37f)
                }
                drawPath(check, mark, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}

@Composable
private fun SwitchLine(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun NavRow(back: (() -> Unit)?, actions: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (back != null) TextButton(shapes = ButtonDefaults.shapes(), onClick = back) { Text("Back") }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
    }
}

/** Text with **bold** spans. */
private fun rich(text: String): AnnotatedString = buildAnnotatedString {
    text.split("**").forEachIndexed { i, part ->
        if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(part) } else append(part)
    }
}

/** A small drawing of a landscape tablet showing what each step is about. */
@Composable
private fun SetupSketch(step: SetupStep, height: Dp) {
    val colors = MaterialTheme.colorScheme
    val screen = colors.surfaceContainerHighest
    val outline = colors.outline
    val ink = colors.onSurfaceVariant
    val accent = colors.primary
    val onAccent = colors.onPrimary
    Canvas(Modifier.fillMaxWidth().height(height)) {
        val h = size.height
        val w = min(size.width, h * 1.55f)
        val body = Rect(Offset((size.width - w) / 2, 0f), Size(w, h))
        val radius = CornerRadius(16.dp.toPx())
        drawRoundRect(screen, body.topLeft, body.size, radius)
        drawRoundRect(outline, body.topLeft, body.size, radius, style = Stroke(2.dp.toPx()))
        val inner = body.deflate(12.dp.toPx())
        // Details are drawn for a 170 dp sketch and grow with it.
        val unit = h / 170.dp.toPx()
        when (step) {
            SetupStep.WELCOME -> {
                val pad = drawPad(inner, ink, alpha = 1f)
                val cursor = Offset(inner.left + inner.width * 0.34f, inner.top + inner.height * 0.32f)
                val finger = pad.center + Offset(-pad.width * 0.12f, pad.height * 0.12f)
                drawLine(
                    accent.copy(alpha = 0.6f), finger, cursor, 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
                )
                drawCircle(accent.copy(alpha = 0.55f), 9.dp.toPx() * unit, finger)
                drawCursor(cursor, ink, screen, unit)
            }
            SetupStep.SERVICE -> {
                val rowHeight = inner.height / 4
                for (i in 0 until 4) {
                    val top = inner.top + i * rowHeight
                    val mid = top + rowHeight / 2
                    if (i == 2) {
                        drawRoundRect(accent.copy(alpha = 0.14f), Offset(inner.left, top + 3.dp.toPx()),
                            Size(inner.width, rowHeight - 6.dp.toPx()), CornerRadius(8.dp.toPx()))
                    }
                    drawCircle(ink.copy(alpha = 0.5f), 6.dp.toPx(), Offset(inner.left + 16.dp.toPx(), mid))
                    drawLine(ink.copy(alpha = 0.4f), Offset(inner.left + 32.dp.toPx(), mid),
                        Offset(inner.left + inner.width * (if (i == 2) 0.5f else 0.62f), mid), 5.dp.toPx(), StrokeCap.Round)
                }
                val mid = inner.top + rowHeight * 2.5f
                val trackW = 34.dp.toPx()
                val trackH = 18.dp.toPx()
                val trackLeft = inner.right - 12.dp.toPx() - trackW
                drawRoundRect(accent, Offset(trackLeft, mid - trackH / 2), Size(trackW, trackH), CornerRadius(trackH / 2))
                drawCircle(onAccent, trackH / 2 - 3.dp.toPx(), Offset(trackLeft + trackW - trackH / 2, mid))
            }
            SetupStep.BATTERY -> {
                val bw = inner.width * 0.22f
                val bh = inner.height * 0.62f
                val left = inner.center.x - bw / 2
                val top = inner.center.y - bh / 2 + 4.dp.toPx()
                val capW = bw * 0.4f
                drawRoundRect(ink, Offset(inner.center.x - capW / 2, top - 7.dp.toPx()), Size(capW, 7.dp.toPx()), CornerRadius(2.dp.toPx()))
                drawRoundRect(ink, Offset(left, top), Size(bw, bh), CornerRadius(6.dp.toPx()), style = Stroke(3.dp.toPx()))
                val inset = 6.dp.toPx()
                val fill = (bh - 2 * inset) * 0.75f
                drawRoundRect(accent, Offset(left + inset, top + bh - inset - fill), Size(bw - 2 * inset, fill), CornerRadius(3.dp.toPx()))
            }
            SetupStep.TILE -> {
                val shade = Rect(inner.topLeft, Size(inner.width, inner.height * 0.55f))
                drawRoundRect(ink.copy(alpha = 0.12f), shade.topLeft, shade.size, CornerRadius(10.dp.toPx()))
                val tile = shade.height * 0.5f
                val gap = (shade.width - 4 * tile) / 5
                for (i in 0 until 4) {
                    val topLeft = Offset(shade.left + gap + i * (tile + gap), shade.center.y - tile / 2)
                    val color = if (i == 2) accent else ink.copy(alpha = 0.3f)
                    drawRoundRect(color, topLeft, Size(tile, tile), CornerRadius(tile / 3))
                    if (i == 2) drawCursor(topLeft + Offset(tile * 0.36f, tile * 0.22f), onAccent, accent, scale = 0.7f * unit)
                }
            }
            SetupStep.TRY -> {
                drawPad(inner, ink, alpha = 0.35f)
                val markerH = inner.height * 0.32f
                val markerW = 4.dp.toPx()
                for (x in listOf(body.left + 3.dp.toPx(), body.right - 3.dp.toPx() - markerW)) {
                    drawRoundRect(accent, Offset(x, body.center.y - markerH / 2), Size(markerW, markerH), CornerRadius(markerW / 2))
                }
                val y = body.center.y
                val start = Offset(body.left + 14.dp.toPx(), y)
                val end = Offset(body.left + body.width * 0.3f, y)
                val head = 9.dp.toPx()
                drawLine(accent, start, end, 3.dp.toPx(), StrokeCap.Round)
                drawLine(accent, end, end + Offset(-head, -head * 0.8f), 3.dp.toPx(), StrokeCap.Round)
                drawLine(accent, end, end + Offset(-head, head * 0.8f), 3.dp.toPx(), StrokeCap.Round)
                drawCircle(accent.copy(alpha = 0.45f), 9.dp.toPx() * unit, start + Offset(4.dp.toPx(), 0f))
            }
        }
    }
}

/** The trackpad in the bottom-right corner of [screen]; returns its rectangle. */
private fun DrawScope.drawPad(screen: Rect, ink: Color, alpha: Float): Rect {
    val pad = Rect(
        Offset(screen.right - screen.width * 0.42f, screen.bottom - screen.height * 0.48f),
        Size(screen.width * 0.42f, screen.height * 0.48f),
    )
    val radius = CornerRadius(10.dp.toPx())
    drawRoundRect(ink.copy(alpha = 0.18f * alpha), pad.topLeft, pad.size, radius)
    drawRoundRect(ink.copy(alpha = 0.55f * alpha), pad.topLeft, pad.size, radius, style = Stroke(1.5f.dp.toPx()))
    val barY = pad.top + 9.dp.toPx()
    drawLine(ink.copy(alpha = 0.7f * alpha), Offset(pad.center.x - 12.dp.toPx(), barY),
        Offset(pad.center.x + 12.dp.toPx(), barY), 3.dp.toPx(), StrokeCap.Round)
    return pad
}

/** An arrow cursor with its tip at [tip]. */
private fun DrawScope.drawCursor(tip: Offset, fill: Color, edge: Color, scale: Float = 1f) {
    val u = 1.dp.toPx() * scale
    val arrow = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(tip.x, tip.y + 20 * u)
        lineTo(tip.x + 5 * u, tip.y + 15.5f * u)
        lineTo(tip.x + 9 * u, tip.y + 23 * u)
        lineTo(tip.x + 12.5f * u, tip.y + 21.5f * u)
        lineTo(tip.x + 8.5f * u, tip.y + 14 * u)
        lineTo(tip.x + 15 * u, tip.y + 14 * u)
        close()
    }
    drawPath(arrow, fill)
    drawPath(arrow, edge, style = Stroke(1.5f * u))
}

/** The trackpad gestures, as gesture → effect lines, with the long press the settings choose. */
@Composable
fun GestureList(longPress: LongPressGesture, modifier: Modifier = Modifier) {
    val lines = GESTURES.toMutableList()
    lines.add(
        lines.indexOfFirst { it.first == "Two-finger drag" },
        when (longPress) {
            LongPressGesture.TWO_FINGER_TAP -> "Two-finger tap" to "Long press (right click)"
            LongPressGesture.DOUBLE_TAP_HOLD -> "Tap, then touch and hold" to "Long press (right click); move to drag"
        },
    )
    Column(modifier) {
        for ((gesture, effect) in lines) {
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(gesture, Modifier.weight(1f), fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                Text(effect, Modifier.weight(1.4f), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private val GESTURES = listOf(
    "One finger" to "Move the cursor",
    "Tap" to "Click",
    "Tap twice" to "Double-click",
    "Tap, then touch and move" to "Drag (rest first to long-press, e.g. to pick up icons)",
    "Two-finger drag" to "Scroll",
    "One finger in the scroll zone" to "Scroll faster (when the zone is on)",
    "Pinch" to "Zoom at the cursor",
    "Three-finger swipe or tap" to "Back, Home, Recents (configurable)",
)
