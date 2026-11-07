package dev.tablettrackpad.playground

import android.os.Bundle
import android.os.SystemClock
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tablettrackpad.R
import dev.tablettrackpad.debug.DebugLog
import dev.tablettrackpad.overlay.TrackpadView
import dev.tablettrackpad.settings.TrackpadTheme
import kotlin.math.roundToInt

private val ROW_HEIGHT = 44.dp

/**
 * Targets for checking each trackpad gesture on a device, plus a monitor of the raw touch events
 * this window receives, which shows whether they were injected and whether they were cancelled.
 */
class PlaygroundActivity : ComponentActivity() {
    private val events = mutableStateListOf<String>()
    private val counts = mutableStateListOf(0, 0, 0, 0)
    private var lastInjectedDevice = mutableStateOf<Int?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Live scroll pays the service's touch slop once; apps that use a different one scroll a little off.
        DebugLog.i("Test area touch slop ${ViewConfiguration.get(this).scaledTouchSlop} px")
        setContent {
            TrackpadTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Playground(events, counts, lastInjectedDevice.value)
                }
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val injected = ev.flags and TrackpadView.FLAG_IS_ACCESSIBILITY_EVENT != 0
        val action = ev.actionMasked
        val bucket = when (action) {
            MotionEvent.ACTION_DOWN -> 0
            MotionEvent.ACTION_MOVE -> 1
            MotionEvent.ACTION_UP -> 2
            MotionEvent.ACTION_CANCEL -> 3
            else -> -1
        }
        if (bucket >= 0) counts[bucket]++
        if (injected) {
            lastInjectedDevice.value = ev.deviceId
            if (action != MotionEvent.ACTION_MOVE) {
                val at = (0 until ev.pointerCount).joinToString(" ") { "(%.0f, %.0f)".format(ev.getRawX(it), ev.getRawY(it)) }
                DebugLog.v(
                    "Test area got injected ${MotionEvent.actionToString(action)} at $at, %d ms after its event time"
                        .format(SystemClock.uptimeMillis() - ev.eventTime),
                )
            }
        }
        if (action != MotionEvent.ACTION_MOVE) {
            events.add(
                0,
                "%-12s dev=%-3d %s (%.0f, %.0f) n=%d".format(
                    MotionEvent.actionToString(action).removePrefix("ACTION_"),
                    ev.deviceId,
                    if (injected) "injected" else "finger  ",
                    ev.rawX,
                    ev.rawY,
                    ev.pointerCount,
                ),
            )
            while (events.size > 14) events.removeAt(events.lastIndex)
        }
        return super.dispatchTouchEvent(ev)
    }
}

@Composable
private fun Playground(events: List<String>, counts: List<Int>, injectedDevice: Int?) {
    Row(
        Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ClickTarget()
            Panel("Horizontal scroll", Modifier.height(120.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(60) { i ->
                        Box(
                            Modifier.size(96.dp).clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center,
                        ) { Text("$i") }
                    }
                }
            }
            DragTarget(Modifier.weight(1f))
            PinchTarget(Modifier.weight(1f))
        }
        Column(Modifier.weight(0.7f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ComposeList(Modifier.weight(1f))
            ViewList(Modifier.weight(1f))
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            FullscreenToggle()
            TouchMonitor(events, counts, injectedDevice, Modifier.weight(1f))
        }
    }
}

/** Hides the system bars like a video or game would, to try the fullscreen auto-hide. */
@Composable
private fun FullscreenToggle() {
    val window = LocalActivity.current?.window ?: return
    var fullscreen by remember { mutableStateOf(false) }
    Panel("Fullscreen", Modifier.fillMaxWidth()) {
        Text(
            "Hides the system bars the way a video or game does. Swipe in from the top or bottom edge to see them for a moment.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = {
            fullscreen = !fullscreen
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            if (fullscreen) {
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }) { Text(if (fullscreen) "Leave fullscreen" else "Try fullscreen") }
    }
}

@Composable
private fun TouchMonitor(events: List<String>, counts: List<Int>, injectedDevice: Int?, modifier: Modifier) {
    Panel("Touch events in this window", modifier.fillMaxWidth()) {
        Text(
            "down ${counts[0]}  move ${counts[1]}  up ${counts[2]}  cancel ${counts[3]}",
            fontFamily = FontFamily.Monospace,
        )
        Text(
            if (injectedDevice == null) "No injected events yet" else "Injected events use device $injectedDevice",
            style = MaterialTheme.typography.bodySmall,
        )
        for (line in events) Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}

/** Rows of fixed height, so the readout can give the exact scroll distance. */
@Composable
private fun ComposeList(modifier: Modifier) {
    val state = rememberLazyListState()
    val rowPx = with(LocalDensity.current) { ROW_HEIGHT.roundToPx() }
    val offset by remember { derivedStateOf { state.firstVisibleItemIndex * rowPx + state.firstVisibleItemScrollOffset } }
    Panel("Compose list: $offset px", modifier.fillMaxWidth()) {
        LazyColumn(state = state) {
            items(300) { i ->
                Text("Row $i", Modifier.fillMaxWidth().height(ROW_HEIGHT).wrapContentHeight())
            }
        }
    }
}

/** The classic View toolkit applies touch slop differently from Compose, so test both. */
@Composable
private fun ViewList(modifier: Modifier) {
    var scrollY by remember { mutableIntStateOf(0) }
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    Panel("View ScrollView: $scrollY px", modifier.fillMaxWidth()) {
        AndroidView(
            factory = { context ->
                ScrollView(context).apply {
                    val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                    for (i in 0 until 300) {
                        column.addView(TextView(context).apply {
                            text = context.getString(R.string.view_row, i)
                            setTextColor(textColor)
                            setPadding(0, 24, 0, 24)
                        })
                    }
                    addView(column)
                    setOnScrollChangeListener { _, _, y, _, _ -> scrollY = y }
                }
            },
            // Compose does not clip a hosted View, so scrolled rows would draw over the title.
            modifier = Modifier.fillMaxSize().clipToBounds(),
        )
    }
}

@Composable
private fun ClickTarget() {
    var clicks by remember { mutableIntStateOf(0) }
    var doubles by remember { mutableIntStateOf(0) }
    var longs by remember { mutableIntStateOf(0) }
    Box(
        Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .combinedClickable(
                onClick = { clicks++ },
                onDoubleClick = { doubles++ },
                onLongClick = { longs++ },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text("Click target\nclicks $clicks · double $doubles · long press $longs", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun DragTarget(modifier: Modifier) {
    var offset by remember { mutableStateOf(Offset.Zero) }
    Panel("Drag the square", modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
                    .size(72.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.tertiary)
                    .pointerInput(Unit) { detectDragGestures { _, delta -> offset += delta } },
            )
        }
    }
}

@Composable
private fun PinchTarget(modifier: Modifier) {
    var scale by remember { mutableFloatStateOf(1f) }
    // The whole card takes the pinch: injected pinches start up to three times the minimum
    // scaling span apart, which is more than the space under the title.
    val zoomable = Modifier.pointerInput(Unit) {
        detectTransformGestures { _, _, zoom, _ -> scale = (scale * zoom).coerceIn(0.2f, 6f) }
    }
    Panel("Pinch to zoom: ×%.2f".format(scale), modifier.fillMaxWidth().then(zoomable)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(60.dp).graphicsLayer { scaleX = scale; scaleY = scale }
                    .clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun Panel(title: String, modifier: Modifier, content: @Composable () -> Unit) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Column(Modifier.padding(top = 8.dp)) { content() }
        }
    }
}
