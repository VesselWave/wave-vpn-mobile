// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpn.vpn

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import me.vwave.vpn.app

class QuickTile : TileService() {
    override fun onStartListening() { qsTile?.apply {
        state = if (app.tunnels.isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        updateTile()
    } }
    override fun onClick() {
        if (app.repository.isLoggedIn && app.tunnels.permissionIntent() == null) unlockAndRun { app.tunnels.toggle(); onStartListening() }
    }
}
