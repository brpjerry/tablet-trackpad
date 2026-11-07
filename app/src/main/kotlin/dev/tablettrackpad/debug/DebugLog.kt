package dev.tablettrackpad.debug

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Notable trackpad events, kept in memory so the settings screen can show them during on-device
 * testing without adb. Everything also goes to logcat under [TAG].
 */
object DebugLog {
    const val TAG = "TabletTrackpad"
    private const val MAX_LINES = 300

    private val lines = ArrayDeque<String>()
    private val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val _version = MutableStateFlow(0)

    /** Bumped on every change, for UI that wants to re-read [snapshot]. */
    val version: StateFlow<Int> = _version

    fun i(message: String) = add("I", message) { Log.i(TAG, message) }

    fun w(message: String) = add("W", message) { Log.w(TAG, message) }

    /** Logcat only: for high-rate events that would flush everything else out of the buffer. */
    fun v(message: String) {
        Log.v(TAG, message)
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    fun clear() {
        synchronized(lines) { lines.clear() }
        _version.value++
    }

    private inline fun add(level: String, message: String, logcat: () -> Unit) {
        logcat()
        val line = "${format.format(Date())} $level $message"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        _version.value++
    }
}
