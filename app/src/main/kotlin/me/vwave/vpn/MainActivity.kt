// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpn

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import me.vwave.vpn.ui.WaveRoot
import me.vwave.vpn.ui.WaveTheme

class MainActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        app.foreground = true
    }
    override fun onStop() {
        if (!isChangingConfigurations) {
            app.foreground = false
            app.pinger.stop()
        }
        super.onStop()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) takeConnectRequest(intent)
        setContent { WaveTheme { WaveRoot() } }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeConnectRequest(intent)
    }

    /** The Quick Settings tile asks to connect once; the request is consumed so recreation can't repeat it. */
    private fun takeConnectRequest(intent: Intent?) {
        if (intent?.action != ACTION_CONNECT) return
        intent.action = null
        if (app.repository.isLoggedIn) app.connectRequested.value = true
    }

    companion object { const val ACTION_CONNECT = "me.vwave.vpn.action.CONNECT" }
}
