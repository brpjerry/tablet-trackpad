package dev.tablettrackpad.settings

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlin.math.roundToInt
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** What a three-finger gesture does. */
enum class GestureAction(val label: String, val globalAction: Int?) {
    NONE("Nothing", null),
    BACK("Back", AccessibilityService.GLOBAL_ACTION_BACK),
    HOME("Home", AccessibilityService.GLOBAL_ACTION_HOME),
    RECENTS("Recent apps", AccessibilityService.GLOBAL_ACTION_RECENTS),
    NOTIFICATIONS("Notifications", AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS),
    QUICK_SETTINGS("Quick settings", AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS),
    SPLIT_SCREEN("Split screen", AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN),
    SCREENSHOT("Screenshot", AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT),
    ALL_APPS("All apps", AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS),
    HIDE_TRACKPAD("Hide trackpad", null),
}

/** A color the user can set, in RGB without alpha. Several parts may share one. */
enum class OverlayColor(private val key: String, val defaultColor: Int) {
    TRACKPAD("pad", 0x1C1C1E),
    OUTLINE("outline", 0xFFFFFF),
    ICONS("icons", 0xFFFFFF),
    FINGERS("fingers", 0xFFFFFF),
    EDGE_MARKERS("edge_markers", 0xFFFFFF),
    ;

    val colorKey get() = "color_$key"
}

/**
 * A part of the overlays with its own opacity, drawn in [color]. The cursor has fixed colors
 * (null): its arrow, outline and feedback share one opacity.
 */
enum class OverlayPart(
    val group: String,
    val label: String,
    private val key: String,
    val color: OverlayColor?,
    val defaultOpacity: Float,
) {
    PAD("Trackpad", "Surface", "pad", OverlayColor.TRACKPAD, 0.35f),
    BAR("Trackpad", "Top bar", "bar", OverlayColor.TRACKPAD, 0.6f),
    SCROLL_ZONE("Trackpad", "Scroll zone", "scroll_zone", OverlayColor.TRACKPAD, 0.3f),
    OUTLINE("Trackpad", "Outline", "outline", OverlayColor.OUTLINE, 0.5f),
    ICONS("Trackpad", "Icons", "icons", OverlayColor.ICONS, 0.8f),
    FINGERS("Trackpad", "Finger marks", "fingers", OverlayColor.FINGERS, 0.27f),
    CURSOR("Cursor", "Opacity", "cursor", null, 1f),
    EDGE_MARKERS("Screen edges", "Edge zone markers", "edge_markers", OverlayColor.EDGE_MARKERS, 0.6f),
    ;

    /** The surface keeps the key of the single opacity setting these replaced. */
    val opacityKey get() = if (this == PAD) "opacity" else "opacity_$key"

    fun defaultArgb() = argb(color?.defaultColor ?: 0xFFFFFF, defaultOpacity)
}

/** [rgb] with [opacity] (0 to 1) as its alpha. */
fun argb(rgb: Int, opacity: Float): Int = ((opacity.coerceIn(0f, 1f) * 255).roundToInt() shl 24) or (rgb and 0xFFFFFF)

/** The gesture that long-presses (Android's right click) at the cursor. */
enum class LongPressGesture(val label: String) {
    TWO_FINGER_TAP("Two-finger tap"),
    DOUBLE_TAP_HOLD("Double-tap and hold"),
}

/** Which side of the trackpad holds the one-finger scroll zone. */
enum class ScrollZoneSide(val label: String) {
    LEFT("Left"),
    RIGHT("Right"),
}

/**
 * All user settings, backed by SharedPreferences. The settings screen writes them and the
 * accessibility service listens for changes and applies them live; both run in one process.
 */
class TrackpadSettings(context: Context) {
    val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var visible by bool(KEY_VISIBLE, false)

    // Setup
    var onboardingDone by bool("onboarding_done", false)
    var tileAdded by bool("tile_added", false)

    /** When setup sent the user to Accessibility settings; the service brings setup back on connect. */
    var setupReturnAt by long("setup_return_at", 0L)

    // Trackpad window. Position is a fraction of the free space, so it survives rotation.
    var widthDp by float("width_dp", 520f)
    var heightDp by float("height_dp", 340f)
    var positionX by float("position_x", 0.95f)
    var positionY by float("position_y", 0.92f)
    var hideInFullscreen by bool("hide_in_fullscreen", false)
    var padAutoHide by bool("pad_auto_hide", false)
    var padHideDelaySeconds by float("pad_hide_delay", 5f)

    // Pointer
    var pointerSpeed by float("pointer_speed", 2f)
    var acceleration by float("acceleration", 0.5f)
    var cursorScale by float("cursor_scale", 1f)
    var cursorAutoHide by bool("cursor_auto_hide", true)
    var cursorHideDelaySeconds by float("cursor_hide_delay", 3f)

    // Clicking
    var longPress by choice("long_press", LongPressGesture.TWO_FINGER_TAP, LongPressGesture.entries)
    var tapAndDrag by bool("tap_and_drag", true)
    var haptics by bool("haptics", true)

    // Scrolling
    var scrollSpeed by float("scroll_speed", 1.5f)
    var naturalScroll by bool("natural_scroll", true)
    var liveScroll by bool(KEY_LIVE_SCROLL, true)
    var momentum by bool("momentum", true)

    // Scroll zone: a strip along one side of the pad where one finger scrolls, faster than two.
    var scrollZone by bool("scroll_zone", false)
    var scrollZoneSide by choice("scroll_zone_side", ScrollZoneSide.RIGHT, ScrollZoneSide.entries)
    var scrollZoneSpeed by float("scroll_zone_speed", 3f)

    // Gestures
    var pinchZoom by bool("pinch_zoom", true)
    var threeFingerUp by choice("three_up", GestureAction.RECENTS, GestureAction.entries)
    var threeFingerDown by choice("three_down", GestureAction.HOME, GestureAction.entries)
    var threeFingerLeft by choice("three_left", GestureAction.BACK, GestureAction.entries)
    var threeFingerRight by choice("three_right", GestureAction.BACK, GestureAction.entries)
    var threeFingerTap by choice("three_tap", GestureAction.NONE, GestureAction.entries)

    // Edge swipe
    var edgeLeft by bool("edge_left", true)
    var edgeRight by bool("edge_right", true)
    var edgeZoneHeightDp by float("edge_zone_height_dp", 200f)
    var edgeZonePosition by float("edge_zone_position", 0.5f)
    var edgeWidthDp by float("edge_width_dp", 14f)
    var edgeHint by bool("edge_hint", true)

    fun setPosition(x: Float, y: Float) = prefs.edit {
        putFloat("position_x", x)
        putFloat("position_y", y)
    }

    /** Size and position in one edit, so the relayout it triggers sees both. */
    fun setLayout(widthDp: Float, heightDp: Float, x: Float, y: Float) = prefs.edit {
        putFloat("width_dp", widthDp)
        putFloat("height_dp", heightDp)
        putFloat("position_x", x)
        putFloat("position_y", y)
    }

    /** Whether the service should reopen setup now, which it does once per trip to Accessibility settings. */
    fun takeSetupReturn(nowMs: Long): Boolean {
        val at = setupReturnAt
        if (at == 0L) return false
        setupReturnAt = 0L
        return nowMs - at < SETUP_RETURN_WINDOW_MS
    }

    fun color(color: OverlayColor): Int = prefs.getInt(color.colorKey, color.defaultColor) and 0xFFFFFF

    fun setColor(color: OverlayColor, rgb: Int) = prefs.edit { putInt(color.colorKey, rgb and 0xFFFFFF) }

    fun opacity(part: OverlayPart): Float = prefs.getFloat(part.opacityKey, part.defaultOpacity)

    fun setOpacity(part: OverlayPart, opacity: Float) = prefs.edit { putFloat(part.opacityKey, opacity) }

    /** The part's color with its opacity as alpha, ready to draw with. */
    fun argb(part: OverlayPart): Int = argb(part.color?.let(::color) ?: 0xFFFFFF, opacity(part))

    fun resetAppearance() = prefs.edit {
        for (color in OverlayColor.entries) remove(color.colorKey)
        for (part in OverlayPart.entries) remove(part.opacityKey)
    }

    fun resetLayout() = prefs.edit {
        remove("width_dp")
        remove("height_dp")
        remove("position_x")
        remove("position_y")
    }

    private fun bool(key: String, default: Boolean) = object : ReadWriteProperty<Any, Boolean> {
        override fun getValue(thisRef: Any, property: KProperty<*>) = prefs.getBoolean(key, default)
        override fun setValue(thisRef: Any, property: KProperty<*>, value: Boolean) =
            prefs.edit { putBoolean(key, value) }
    }

    private fun long(key: String, default: Long) = object : ReadWriteProperty<Any, Long> {
        override fun getValue(thisRef: Any, property: KProperty<*>) = prefs.getLong(key, default)
        override fun setValue(thisRef: Any, property: KProperty<*>, value: Long) =
            prefs.edit { putLong(key, value) }
    }

    private fun float(key: String, default: Float) = object : ReadWriteProperty<Any, Float> {
        override fun getValue(thisRef: Any, property: KProperty<*>) = prefs.getFloat(key, default)
        override fun setValue(thisRef: Any, property: KProperty<*>, value: Float) =
            prefs.edit { putFloat(key, value) }
    }

    private fun <E : Enum<E>> choice(key: String, default: E, entries: List<E>) = object : ReadWriteProperty<Any, E> {
        override fun getValue(thisRef: Any, property: KProperty<*>): E {
            val name = prefs.getString(key, null) ?: return default
            return entries.firstOrNull { it.name == name } ?: default
        }

        override fun setValue(thisRef: Any, property: KProperty<*>, value: E) =
            prefs.edit { putString(key, value.name) }
    }

    companion object {
        const val FILE = "trackpad"
        const val KEY_VISIBLE = "visible"
        const val KEY_LIVE_SCROLL = "live_scroll"
        val LAYOUT_KEYS = setOf("width_dp", "height_dp", "position_x", "position_y")
        val SETUP_KEYS = setOf("onboarding_done", "tile_added", "setup_return_at")
        val APPEARANCE_KEYS = (OverlayColor.entries.map { it.colorKey } + OverlayPart.entries.map { it.opacityKey }).toSet()
        private const val SETUP_RETURN_WINDOW_MS = 10 * 60 * 1000L
    }
}
