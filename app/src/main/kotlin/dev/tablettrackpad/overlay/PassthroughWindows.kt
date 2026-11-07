package dev.tablettrackpad.overlay

/** Our own touchable windows, which an injected touch would hit, and a way to let touches fall through them. */
interface PassthroughWindows {
    /** Our touchable windows under a screen point, which an injected touch there would hit. */
    fun touchableWindowsAt(x: Int, y: Int): List<OverlayManager.Window>

    /** Lets touches fall through [window] until a matching [releasePassthrough]. Nests. */
    fun acquirePassthrough(window: OverlayManager.Window)

    fun releasePassthrough(window: OverlayManager.Window)
}
