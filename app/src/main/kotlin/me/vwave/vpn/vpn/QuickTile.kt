// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpn.vpn

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import me.vwave.vpn.MainActivity
import me.vwave.vpn.app

class QuickTile : TileService() {
    override fun onStartListening() { qsTile?.apply {
        state = if (app.tunnels.isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        updateTile()
    } }
    override fun onClick() {
        if (app.repository.isLoggedIn && app.tunnels.permissionIntent() == null) unlockAndRun { app.tunnels.toggle(); onStartListening() }
        // Sign-in and Android's VPN consent need the app; open it rather than ignore the tap.
        else unlockAndRun { openApp() }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_CONNECT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        else @Suppress("DEPRECATION") startActivityAndCollapse(intent)
    }
}
