package dev.tablettrackpad.overlay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.WindowInsets
import android.view.WindowManager

/**
 * Tells when an app shows fullscreen content, such as a video or a game: the window manager then
 * reports both system bars hidden for an app window. Our own overlays sit above the bars and get
 * no bar insets, so there is nothing to listen to; the watcher asks about once a second while it
 * runs. Bars that show only for a moment, after a swipe in from the edge, don't count.
 */
class FullscreenWatcher(context: Context, private val onChange: (fullscreen: Boolean) -> Unit) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    var fullscreen = false
        private set

    private val poll = object : Runnable {
        override fun run() {
            check()
            handler.postDelayed(this, POLL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        handler.post(poll)
    }

    /** Stops watching; fullscreen content that was showing counts as over. */
    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(poll)
        if (fullscreen) {
            fullscreen = false
            onChange(false)
        }
    }

    private fun check() {
        val insets = wm.currentWindowMetrics.windowInsets
        val hidden = !insets.isVisible(WindowInsets.Type.statusBars()) &&
            !insets.isVisible(WindowInsets.Type.navigationBars())
        if (hidden != fullscreen) {
            fullscreen = hidden
            onChange(hidden)
        }
    }

    private companion object {
        const val POLL_MS = 1000L
    }
}
