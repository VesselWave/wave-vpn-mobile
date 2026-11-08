// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpn.vpn

import android.os.ParcelFileDescriptor
import com.wireguard.android.backend.GoBackend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.vwave.vpn.app

/** Keeps VPN ownership while the encrypted and blocking interfaces replace each other. */
class WaveVpnService : GoBackend.VpnService() {
    companion object {
        @Volatile var instance: WaveVpnService? = null; private set
        private val blockingState = MutableStateFlow(false)
        /**
         * True while Wave's blocking interface owns the VPN: every routed app has no internet.
         * Screens show this instead of inferring it from the tunnel state.
         */
        val blockingTraffic: StateFlow<Boolean> = blockingState.asStateFlow()
        /** Android's "Block connections without VPN" for Wave (API 29+; false when unknown). */
        fun lockdownEnabled(): Boolean = android.os.Build.VERSION.SDK_INT >= 29 &&
            runCatching { instance?.isLockdownEnabled == true }.getOrDefault(false)
    }
    @Volatile private var blocking: ParcelFileDescriptor? = null
        set(value) { field = value; blockingState.value = value != null }
    val isBlocking get() = blocking != null
    override fun onCreate() {
        super.onCreate()
        instance = this
        // Restore a sink before account refresh/handshake after process restart.
        // The interval while this process is dead still belongs to Android.
        if (android.os.Build.VERSION.SDK_INT >= 29 && isLockdownEnabled && prepare(this) == null) {
            runCatching { block() }.onFailure {
                app.log.warn("Android could not establish startup blocking interface")
            }
        }
    }
    @Synchronized fun block() {
        if (blocking != null) return
        // Nothing reads or forwards this descriptor; a full queue drops packets.
        blocking = getBuilder().setSession("Wave — blocking traffic")
            .addAddress("192.0.2.1", 32).addAddress("fd00:7761:7665::1", 128)
            .addRoute("0.0.0.0", 0).addRoute("::", 0)
            .addDnsServer("192.0.2.2").addDnsServer("fd00:7761:7665::2")
            .setMtu(1280).establish() ?: error("VPN blocking interface unavailable")
    }
    @Synchronized fun releaseBlock() { blocking?.close(); blocking = null }
    override fun onRevoke() {
        releaseBlock()
        app.tunnels.revoked()
        super.onRevoke()
    }
    override fun onDestroy() {
        instance = null
        releaseBlock()
        super.onDestroy()
    }
}
