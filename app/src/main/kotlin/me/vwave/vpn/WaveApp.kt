// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpn

import android.app.Application
import android.content.Context
import com.wireguard.android.backend.GoBackend
import kotlinx.coroutines.*
import me.vwave.vpn.data.*
import me.vwave.vpn.vpn.*

class WaveApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val log = EventLog()
    lateinit var settings: SettingsStore
    lateinit var repository: AirRepository
    val pinger = Pinger()
    lateinit var tunnels: TunnelManager
    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        repository = AirRepository(SessionVault(this))
        tunnels = TunnelManager(this, repository, settings, pinger, log, scope)
        GoBackend.setAlwaysOnCallback { if (repository.isLoggedIn) tunnels.connect() }
    }
}
val Context.app: WaveApp get() = applicationContext as WaveApp
