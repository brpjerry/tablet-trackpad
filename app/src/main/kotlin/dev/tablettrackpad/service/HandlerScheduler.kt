package dev.tablettrackpad.service

import android.os.Handler
import dev.tablettrackpad.core.Cancellable
import dev.tablettrackpad.core.Scheduler

/** [Scheduler] on a [Handler], for the code in core and inject that is tested without Android. */
class HandlerScheduler(private val handler: Handler) : Scheduler {
    override fun schedule(delayMs: Long, action: () -> Unit): Cancellable {
        val runnable = Runnable(action)
        handler.postDelayed(runnable, delayMs)
        return Cancellable { handler.removeCallbacks(runnable) }
    }
}
