// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpn.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import me.vwave.vpn.core.*
import me.vwave.vpn.data.*
import me.vwave.vpn.data.Target
import java.net.InetSocketAddress
import java.net.Socket

sealed interface TunnelState {
    data object Down : TunnelState
    data class Connecting(val server: Server?) : TunnelState
    data class Connected(val server: Server) : TunnelState
    data class Stalled(val server: Server) : TunnelState
    data class Failed(val message: String) : TunnelState
}
data class TunnelStats(val rxBytes: Long, val txBytes: Long, val rxRate: Double,
    val txRate: Double, val lastHandshakeMillis: Long)

/** TCP connect latency, not ICMP. No root/raw-socket privileges are required. */
class Pinger {
    private val guard = Mutex()
    private val values = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val active = MutableStateFlow(false)
    val pings = values.asStateFlow()
    val running = active.asStateFlow()
    suspend fun measure(servers: List<Server>) = guard.withLock {
        active.value = true
        try {
            val slots = Semaphore(12)
            val results = coroutineScope {
                servers.filter { it.isOpen }.map { s -> async(Dispatchers.IO) {
                    slots.withPermit {
                        val host = s.entries.firstOrNull { ':' !in it } ?: return@withPermit null
                        val start = System.nanoTime()
                        runCatching { Socket().use { it.connect(InetSocketAddress(host, 443), 1200) }
                            s.name to ((System.nanoTime() - start) / 1_000_000).toInt().coerceAtLeast(1)
                        }.getOrNull()
                    }
                } }.awaitAll().filterNotNull().toMap()
            }
            values.value = results
        } finally { active.value = false }
    }
}

/** All changes to the backend are serialized, including disconnect and cancel cleanup. */
class TunnelManager(private val context: Context, private val repository: AirRepository,
    private val preferences: SettingsStore, private val pinger: Pinger,
    private val log: EventLog, private val scope: CoroutineScope) {
    private val backend by lazy { GoBackend(context.applicationContext) }
    private val lock = Mutex()
    private var operation: Job? = null
    private val status = MutableStateFlow<TunnelState>(TunnelState.Down)
    private val counters = MutableStateFlow<TunnelStats?>(null)
    private val since = MutableStateFlow<Long?>(null)
    private val count = MutableStateFlow(0)
    val state = status.asStateFlow()
    val stats = counters.asStateFlow()
    val connectedSince = since.asStateFlow()
    val connectionCount = count.asStateFlow()
    val isActive get() = state.value is TunnelState.Connected || state.value is TunnelState.Connecting || state.value is TunnelState.Stalled
    private val tunnel = object : Tunnel {
        override fun getName() = "wave"
        override fun onStateChange(newState: Tunnel.State) {
            // The serialized connection loop observes revocations. Reconfiguration also emits
            // DOWN, so callbacks must not reset the UI halfway through a connection attempt.
        }
    }
    fun permissionIntent() = VpnService.prepare(context)
    fun connect(target: Target = preferences.current.target) {
        context.startForegroundService(Intent(context, ConnectionService::class.java))
        operation?.cancel()
        operation = scope.launch { lock.withLock { connection(target) } }
    }
    fun disconnect() {
        context.stopService(Intent(context, ConnectionService::class.java))
        operation?.cancel()
        operation = scope.launch { lock.withLock {
            try { withContext(Dispatchers.IO) { backend.setState(tunnel, Tunnel.State.DOWN, null) } }
            catch (_: Exception) { log.warn("Android could not stop the tunnel") }
            status.value = TunnelState.Down; since.value = null; counters.value = null
        } }
    }
    fun toggle() { if (isActive) disconnect() else connect() }
    private suspend fun connection(target: Target) {
        var started = false
        var selectedServer: Server? = null
        status.value = TunnelState.Connecting(null)
        since.value = null; counters.value = null
        try {
            try { repository.refresh() } catch (e: CancellationException) { throw e }
            catch (_: Exception) { log.warn("Using cached account and server information") }
            val m = repository.manifest.value ?: error("Refresh the server list first")
            val a = repository.account.value ?: error("Sign in first")
            val prefs = preferences.current
            val device = a.key(prefs.deviceKey) ?: error("Select an available device")
            val mode = m.wireguardModes.find { it.port == prefs.port } ?: error("Select a supported WireGuard port")
            val candidates = Scoring.rank(m.servers, pinger.pings.value, m.factors) { s -> when(target) {
                Target.Best -> true
                is Target.Country -> s.countryCode.equals(target.code, true)
                is Target.Server -> s.name == target.name
            } }.take(if (target is Target.Server) 1 else 3)
            require(candidates.isNotEmpty()) { "No available server for this choice" }
            var selected = candidates.last()
            for (server in candidates) {
                currentCoroutineContext().ensureActive()
                selected = server
                selectedServer = server
                status.value = TunnelState.Connecting(server)
                val config = Config.parse(wgQuickConfig(a, device, server, WgOptions(prefs.port, mode.entryIndex,
                    prefs.ipv6Endpoint,
                    if (prefs.splitMode == SplitMode.Exclude) prefs.splitApps else emptySet(),
                    if (prefs.splitMode == SplitMode.Include) prefs.splitApps else emptySet())).byteInputStream())
                log.info("Connecting to ${server.name}")
                withContext(Dispatchers.IO) { backend.setState(tunnel, Tunnel.State.UP, config) }
                started = true
                val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
                var handshook = false
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    delay(500)
                    val s = withContext(Dispatchers.IO) { backend.getStatistics(tunnel).peer(config.peers.first().publicKey) }
                    if ((s?.latestHandshakeEpochMillis ?: 0) > 0) { handshook = true; break }
                }
                if (handshook) break
            }
            // Keep an unanswered tunnel installed; Android blocking settings protect any teardown gap.
            val server = selected
            var lastRx = 0L; var lastTx = 0L; var lastAt = System.nanoTime()
            var recorded = false
            while (true) {
                delay(1000)
                if (withContext(Dispatchers.IO) { backend.getState(tunnel) } == Tunnel.State.DOWN) break
                val s = withContext(Dispatchers.IO) { backend.getStatistics(tunnel) }
                val handshake = s.peers().maxOfOrNull { s.peer(it)?.latestHandshakeEpochMillis ?: 0L } ?: 0L
                val rx = s.totalRx(); val tx = s.totalTx()
                val now = System.nanoTime(); val seconds = (now - lastAt) / 1e9
                counters.value = TunnelStats(rx, tx, (rx - lastRx).coerceAtLeast(0) / seconds,
                    (tx - lastTx).coerceAtLeast(0) / seconds, handshake)
                lastRx = rx; lastTx = tx; lastAt = now
                val alive = handshake > 0 && System.currentTimeMillis() - handshake < 180_000
                status.value = if (alive) TunnelState.Connected(server) else TunnelState.Stalled(server)
                if (alive && !recorded) {
                    recorded = true; count.value += 1; since.value = android.os.SystemClock.elapsedRealtime()
                    log.info("WireGuard handshake established")
                }
            }
            status.value = TunnelState.Down; since.value = null; counters.value = null
            context.stopService(Intent(context, ConnectionService::class.java))
        } catch (e: CancellationException) {
            // Finish the serialized teardown before the next connect/disconnect can run.
            if (started) withContext(NonCancellable + Dispatchers.IO) {
                runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
            }
            throw e
        } catch (_: Exception) {
            // Keep an existing tunnel in place on monitor errors instead of opening a traffic gap.
            if (started) {
                selectedServer?.let { status.value = TunnelState.Stalled(it) }
                log.error("Connection monitoring failed; disconnect and retry")
            } else {
                context.stopService(Intent(context, ConnectionService::class.java))
                status.value = TunnelState.Failed("Check account, server and WireGuard settings")
                log.error("Unable to start WireGuard")
            }
        }
    }
}
