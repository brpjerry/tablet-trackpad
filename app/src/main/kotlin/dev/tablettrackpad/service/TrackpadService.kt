package dev.tablettrackpad.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.TileService
import android.view.Display
import android.view.HapticFeedbackConstants
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import dev.tablettrackpad.core.GestureRecognizer
import dev.tablettrackpad.core.TouchAction
import dev.tablettrackpad.core.TouchPoint
import dev.tablettrackpad.debug.DebugLog
import dev.tablettrackpad.inject.GestureInjector
import dev.tablettrackpad.overlay.EdgeStripView
import dev.tablettrackpad.overlay.OverlayManager
import dev.tablettrackpad.overlay.TrackpadView
import dev.tablettrackpad.settings.LongPressGesture
import dev.tablettrackpad.settings.SettingsActivity
import dev.tablettrackpad.settings.TrackpadSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The accessibility service that hosts the trackpad. Accessibility gives us overlay windows that
 * need no extra permission, gesture injection, and global actions like Back and Home.
 */
class TrackpadService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var settings: TrackpadSettings
    private lateinit var overlays: OverlayManager
    private lateinit var injector: GestureInjector
    private lateinit var controller: TrackpadController
    private lateinit var recognizer: GestureRecognizer
    private var connected = false

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (connected) onSettingChanged(key)
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            if (connected && displayId == Display.DEFAULT_DISPLAY) relayout()
        }

        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        settings = TrackpadSettings(this)
        overlays = OverlayManager(this, settings)
        injector = GestureInjector({ gesture, callback -> dispatchGesture(gesture, callback, handler) }, overlays, handler)
        controller = TrackpadController(this, overlays, injector, settings, handler)
        recognizer = GestureRecognizer(recognizerConfig(), HandlerScheduler(handler), controller)
        overlays.trackpadView.callbacks = trackpadCallbacks
        overlays.onFullscreenChanged = { controller.onFullscreenChanged() }
        overlays.leftEdge.callbacks = edgeCallbacks
        overlays.rightEdge.callbacks = edgeCallbacks
        connected = true
        _running.value = true

        controller.applySettings()
        controller.onDisplayChanged()
        overlays.layoutEdges()
        if (settings.visible) {
            overlays.showTrackpad()
            controller.wakeOverlays()
        }
        if (settings.takeSetupReturn(System.currentTimeMillis())) {
            // Setup sent the user to Accessibility settings to switch us on: take them back to it.
            startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        settings.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, handler)
        val viewConfig = ViewConfiguration.get(this)
        DebugLog.i(
            "Service connected, display ${overlays.displayBounds()}, touch slop ${viewConfig.scaledTouchSlop} px, " +
                "min scaling span ${viewConfig.scaledMinimumScalingSpan} px",
        )
        updateTile()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (connected) relayout()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        if (!connected) return
        connected = false
        _running.value = false
        settings.prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        recognizer.reset()
        controller.reset()
        injector.cancelQueued()
        overlays.destroy()
        handler.removeCallbacksAndMessages(null)
        DebugLog.i("Service disconnected")
        updateTile()
    }

    private fun onSettingChanged(key: String?) {
        when (key) {
            // Posted: the change can come from inside a gesture callback, and the recognizer
            // should finish that event before it is reset.
            TrackpadSettings.KEY_VISIBLE -> handler.post { if (connected) applyVisibility() }
            in TrackpadSettings.LAYOUT_KEYS -> if (overlays.trackpadShown) overlays.layoutTrackpad()
            in TrackpadSettings.APPEARANCE_KEYS -> overlays.applyAppearance()
            // Setup's own bookkeeping, which nothing in the service depends on.
            in TrackpadSettings.SETUP_KEYS -> Unit
            else -> {
                recognizer.config = recognizerConfig()
                controller.applySettings()
                overlays.layoutEdges()
            }
        }
    }

    private fun applyVisibility() {
        recognizer.reset()
        controller.reset()
        if (settings.visible && !overlays.trackpadShown) {
            overlays.showTrackpad()
            controller.wakeOverlays()
        } else if (!settings.visible && overlays.trackpadShown) {
            overlays.hideTrackpad()
        }
        updateTile()
    }

    private fun relayout() {
        overlays.relayout()
        controller.onDisplayChanged()
    }

    private fun recognizerConfig(): GestureRecognizer.Config {
        val d = resources.displayMetrics.density
        return GestureRecognizer.Config(
            tapSlopPx = 5 * d,
            scrollSlopPx = 6 * d,
            pinchSlopPx = 16 * d,
            swipeDistancePx = 56 * d,
            tapAndDrag = settings.tapAndDrag,
            holdForLongPress = settings.longPress == LongPressGesture.DOUBLE_TAP_HOLD,
            longPressMs = ViewConfiguration.getLongPressTimeout().toLong(),
            pinchEnabled = settings.pinchZoom,
            inScrollZone = overlays.trackpadView::inScrollZone,
        )
    }

    private fun updateTile() {
        TileService.requestListeningState(this, ComponentName(this, TrackpadTileService::class.java))
    }

    private val trackpadCallbacks = object : TrackpadView.Callbacks {
        override fun onPadTouch(action: TouchAction, changedId: Int, touches: List<TouchPoint>, timeMs: Long) =
            recognizer.onTouch(action, changedId, touches, timeMs)

        override fun onTouchActivity(touching: Boolean) = controller.onTrackpadActivity(touching)

        override fun onInjectedTouch() = controller.onInjectedTouchOnPad()

        override fun edgeZoneAt(rawX: Float, rawY: Float) = overlays.edgeZoneAt(rawX, rawY)

        override fun onEdgeSwipe(isLeft: Boolean) = edgeCallbacks.onEdgeSwipe(isLeft)

        override fun onMoveBy(dx: Float, dy: Float) = overlays.moveTrackpadBy(dx, dy)

        override fun onMoveEnd() = overlays.commitTrackpadPosition()

        override fun onResizeBy(dx: Float, dy: Float) = overlays.resizeTrackpadBy(dx, dy)

        override fun onResizeEnd() = overlays.commitTrackpadSize()

        override fun onHideClicked() {
            settings.visible = false
        }

        override fun onSettingsClicked() {
            startActivity(Intent(this@TrackpadService, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private val edgeCallbacks = object : EdgeStripView.Callbacks {
        override fun onEdgeSwipe(isLeft: Boolean) {
            val strip = if (isLeft) overlays.leftEdge else overlays.rightEdge
            if (settings.visible && overlays.trackpadView.concealed) {
                // It is there but faded out: bring it back rather than hide what can't be seen.
                DebugLog.i("Edge swipe (${if (isLeft) "left" else "right"}): reveal")
                if (settings.haptics) strip.performHapticFeedback(HapticFeedbackConstants.GESTURE_START)
                controller.wakeOverlays()
                return
            }
            val show = !settings.visible
            DebugLog.i("Edge swipe (${if (isLeft) "left" else "right"}): ${if (show) "show" else "hide"}")
            if (settings.haptics) strip.performHapticFeedback(HapticFeedbackConstants.GESTURE_START)
            settings.visible = show
        }

        override fun onEdgeTap(rawX: Float, rawY: Float) = controller.forwardTap(rawX, rawY)
    }

    companion object {
        private val _running = MutableStateFlow(false)

        /** Whether the service is connected, for the settings screen and the tile. */
        val running: StateFlow<Boolean> = _running

        val isRunning get() = _running.value
    }
}
