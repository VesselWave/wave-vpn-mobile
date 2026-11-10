// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpn.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import me.vwave.vpn.app
import me.vwave.vpn.data.*
import me.vwave.vpn.data.Target

/** New adapter between the retained authored screens and the replacement backend. */
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.app
    val account = app.repository.account
    val manifest = app.repository.manifest
    val settings = app.settings.settings
    val pings = app.pinger.pings
    val pinging = app.pinger.running
    val tunnel = app.tunnels.state
    val stats = app.tunnels.stats
    val connectedSince = app.tunnels.connectedSince
    val connectionCount = app.tunnels.connectionCount
    val interruption = app.tunnels.interruption
    /** Wave's blocking interface is installed: apps have no internet whatever the tunnel state says. */
    val blockingTraffic = me.vwave.vpn.vpn.WaveVpnService.blockingTraffic
    fun lockdownEnabled() = me.vwave.vpn.vpn.WaveVpnService.lockdownEnabled()
    val log = app.log.entries
    val loginBusy = MutableStateFlow(false)
    val loginError = MutableStateFlow<String?>(null)
    val refreshing = MutableStateFlow(false)
    val notice = MutableStateFlow<String?>(null)
    private var signingIn: Job? = null
    init {
        if (app.repository.isLoggedIn) refresh(false)
        viewModelScope.launch {
            while (isActive) { app.pinger.cached(manifest.value?.servers.orEmpty()); delay(500) }
        }
    }
    fun login(user: String, password: String) {
        if (loginBusy.value) return
        if (user.isBlank() || password.isEmpty()) { loginError.value = "Enter your username and password"; return }
        loginBusy.value = true; loginError.value = null
        signingIn = viewModelScope.launch {
            // One 45-second deadline for the whole sign-in, as on Linux; Cancel stays a cancellation.
            try {
                me.vwave.vpn.core.withSignInDeadline { app.repository.login(user.trim(), password) }
                app.log.info("Signed in"); refresh(false)
            }
            catch (e: CancellationException) { throw e }
            catch (e: me.vwave.vpn.core.CooldownException) { loginError.value = e.message }
            catch (e: me.vwave.vpn.core.LoginException) { loginError.value = e.message }
            catch (e: me.vwave.vpn.core.SignInTimeoutException) { loginError.value = e.message; app.log.error(e.message.orEmpty()) }
            catch (_: Exception) { loginError.value = "Sign-in failed. Check your network and retry shortly." }
            finally { loginBusy.value = false }
        }
    }
    /** Not a failure: no error, no endpoint penalty, nothing saved; the form keeps what was typed. */
    fun cancelSignIn() {
        if (!loginBusy.value) return
        signingIn?.cancel()
        loginError.value = null
        app.log.info("Sign-in cancelled")
    }
    fun refresh(force: Boolean = true) {
        if (!app.repository.isLoggedIn || refreshing.value) return
        refreshing.value = true
        viewModelScope.launch {
            try {
                refreshAndMeasure({ app.repository.refresh(force) }, { e ->
                    notice.value = if (e is me.vwave.vpn.core.CooldownException) e.message
                        else "Unable to refresh AirVPN information. Cached servers are still available; retry later."
                }) {
                val servers = manifest.value?.servers.orEmpty()
                val prefs = app.settings.current
                val priority = prefs.favorites + listOfNotNull(app.tunnels.lastServerName) + servers.filter { s ->
                    when (val target = prefs.target) {
                        is Target.Country -> s.countryCode.equals(target.code, true)
                        is Target.Server -> s.name == target.name
                        Target.Best -> false
                    }
                }.map { it.name }
                app.pinger.measure(servers, priority.toSet())
                }
            }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { notice.value = "Unable to refresh AirVPN information. Cached servers are still available; retry later." }
            finally { refreshing.value = false }
        }
    }
    /**
     * TunnelManager refuses settings it cannot honor (Only selected with nothing checked) and
     * reports why. Skip Android's consent prompt then: it would stop another VPN for nothing.
     */
    val needsConsentFirst get() = !settings.value.noAppsSelected
    fun connect() = app.tunnels.connect()
    fun disconnect() = app.tunnels.disconnect()
    fun choose(target: Target) {
        val connected = app.tunnels.state.value as? me.vwave.vpn.vpn.TunnelState.Connected
        updateSettings { it.copy(target = target) }
        // Choosing the server already carrying traffic must not interrupt that connection.
        if (target is Target.Server && target.name == connected?.server?.name) return
        reconnectIfActive()
    }
    fun toggleFavorite(name: String) = updateSettings { s ->
        s.copy(favorites = if (name in s.favorites) s.favorites - name else s.favorites + name)
    }
    fun updateSettings(change: (Settings) -> Settings) = app.settings.update(change)
    /**
     * Applies changed settings to a running tunnel. Settings it can no longer honor (Only selected
     * with nothing checked) disconnect with the reason rather than keep routing every app.
     */
    fun reconnectIfActive() { if (app.tunnels.isActive) connect() }
    fun clearLog() = app.log.clear()
    fun noticeShown() { notice.value = null }
    fun logout() {
        app.pinger.clear()
        signingIn?.cancel()
        app.tunnels.disconnect()
        app.repository.logout(app.scope) { app.settings.clear(); app.log.clear() }
    }
}
