// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpn

import android.app.Application
import android.content.Context
import com.wireguard.android.backend.GoBackend
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import me.vwave.vpn.data.*
import me.vwave.vpn.vpn.*

class WaveApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val log = EventLog()
    lateinit var settings: SettingsStore
    lateinit var repository: AirRepository
    @Volatile var foreground = false
    /** Set when the Quick Settings tile opens the app to connect; the UI runs it once. */
    val connectRequested = MutableStateFlow(false)
    val pinger by lazy { Pinger(this) }
    lateinit var tunnels: TunnelManager
    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        repository = AirRepository(SessionVault(this), this)
        tunnels = TunnelManager(this, repository, settings, pinger, log, scope)
        pinger.allowed = { foreground && !tunnels.isActive && WaveVpnService.instance?.isBlocking != true }
        GoBackend.setAlwaysOnCallback {
            scope.launch {
                if (repository.isLoggedIn && tunnels.shouldReconnect && !tunnels.isActive) tunnels.connect()
            }
        }
    }
}
val Context.app: WaveApp get() = applicationContext as WaveApp
