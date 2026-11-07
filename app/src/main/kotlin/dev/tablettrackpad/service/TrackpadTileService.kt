package dev.tablettrackpad.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.tablettrackpad.settings.SettingsActivity
import dev.tablettrackpad.settings.TrackpadSettings

/** Quick Settings toggle for the trackpad. Opens the app when the service is not enabled yet. */
class TrackpadTileService : TileService() {
    private val settings by lazy { TrackpadSettings(this) }

    override fun onTileAdded() {
        super.onTileAdded()
        settings.tileAdded = true
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
        settings.tileAdded = false
    }

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        if (!TrackpadService.isRunning) {
            openSettings()
            return
        }
        settings.visible = !settings.visible
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val running = TrackpadService.isRunning
        tile.state = when {
            !running -> Tile.STATE_INACTIVE
            settings.visible -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.subtitle = if (running) null else "Service off"
        tile.updateTile()
    }

    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            startActivityAndCollapseLegacy(intent)
        }
    }

    /** The only variant before Android 14; it throws on 14+ for apps targeting 14+. */
    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun startActivityAndCollapseLegacy(intent: Intent) = startActivityAndCollapse(intent)
}
