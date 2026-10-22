// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import me.vwave.vpn.MainActivity
import me.vwave.vpn.R
import me.vwave.vpn.app

/**
 * Own the connection lifetime independently of Android's binding to the TUN service.
 * On process death the system can unbind the VPN as the interface disappears, before
 * scheduling service restarts. This separate started service survives that bookkeeping.
 */
class ConnectionService : Service() {
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(
            CHANNEL, "VPN connection", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(1, Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Wave VPN service")
            .setContentText("Open Wave for connection status")
            .setContentIntent(open)
            .setOngoing(true)
            .build())
        if (!app.repository.isLoggedIn || app.tunnels.permissionIntent() != null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!app.tunnels.isActive) {
            app.log.info("Restoring VPN after Android service start")
            app.tunnels.connect()
        }
        return START_STICKY
    }

    private companion object { const val CHANNEL = "wave-vpn" }
}
