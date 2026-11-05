// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpn

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
        setContent { WaveTheme { WaveRoot() } }
    }
}
