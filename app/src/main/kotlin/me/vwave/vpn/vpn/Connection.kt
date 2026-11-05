// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpn.vpn

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
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
import kotlinx.coroutines.sync.withLock
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
class Pinger(context: Context) {
    private val budget = context.getSharedPreferences("wave-probe-budget", Context.MODE_PRIVATE)
    var allowed: () -> Boolean = { false }
    private val cache = LatencyCache(probe = { host ->
        runInterruptible(Dispatchers.IO) {
            val start = System.nanoTime()
            Socket().use { it.connect(InetSocketAddress(host, 443), 1200) }
            ((System.nanoTime() - start) / 1_000_000).toInt().coerceAtLeast(1)
        }
    }, allowed = { allowed() },
        initialDelay = budget.getLong("nextSweep", 0) - System.currentTimeMillis(),
        reserveSweep = {
            check(budget.edit().putLong("nextSweep", System.currentTimeMillis() + 180_000).commit())
        })
    val pings = cache.pings
    val running = cache.running
    suspend fun measure(servers: List<Server>, priority: Set<String> = emptySet()) = cache.measure(servers, priority)
    suspend fun cached(servers: List<Server>) = cache.cached(servers)
    fun stop() = cache.stop()
    fun clear() = cache.clear()
}

/** All changes to the backend are serialized, including disconnect and cancel cleanup. */
class TunnelManager(private val context: Context, private val repository: AirRepository,
    private val preferences: SettingsStore, private val pinger: Pinger,
    private val log: EventLog, private val scope: CoroutineScope) {
    private val backend by lazy { GoBackend(context.applicationContext) }
    private val lock = Mutex()
    private var serviceBinding: ServiceConnection? = null
    private var boundService: CompletableDeferred<WaveVpnService>? = null
    private suspend fun vpnService(): WaveVpnService {
        boundService?.let { return it.await() }
        val ready = CompletableDeferred<WaveVpnService>()
        val binding = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val service = WaveVpnService.instance
                if (service != null) ready.complete(service)
                else ready.completeExceptionally(IllegalStateException("VPN service unavailable"))
            }
            override fun onServiceDisconnected(name: ComponentName) {
                ready.completeExceptionally(IllegalStateException("VPN service disconnected"))
            }
        }
        serviceBinding = binding
        boundService = ready
        if (!context.bindService(Intent(context, WaveVpnService::class.java)
                .setAction(VpnService.SERVICE_INTERFACE), binding, Context.BIND_AUTO_CREATE)) {
            serviceBinding = null
            boundService = null
            error("Cannot bind VPN service")
        }
        return ready.await()
    }
    private var operation: Job? = null
    private val handshakeRefresh = HandshakeRefresh()
    var lastServerName: String? = null
        private set
    private val status = MutableStateFlow<TunnelState>(TunnelState.Down)
    private val counters = MutableStateFlow<TunnelStats?>(null)
    private val since = MutableStateFlow<Long?>(null)
    private val count = MutableStateFlow(0)
    val state = status.asStateFlow()
    val stats = counters.asStateFlow()
    val connectedSince = since.asStateFlow()
    val connectionCount = count.asStateFlow()
    @Volatile var shouldReconnect = true
        private set
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
        if (!repository.isLoggedIn) return
        status.value = TunnelState.Connecting(null)
        pinger.stop()
        shouldReconnect = true
        context.startForegroundService(Intent(context, ConnectionService::class.java))
        operation?.cancel()
        operation = scope.launch { lock.withLock { connection(target) } }
    }
    fun disconnect() {
        shouldReconnect = false
        val previousState = status.value
        operation?.cancel()
        operation = scope.launch { lock.withLock {
            try { withContext(Dispatchers.IO) {
                val service = WaveVpnService.instance
                val lockdown = permissionIntent() == null &&
                    runCatching { backend.isLockdownEnabled }.getOrDefault(false)
                log.info("Stopping tunnel; lockdown=$lockdown, service=${service != null}")
                if (lockdown) {
                    checkNotNull(service) { "VPN service unavailable" }.block()
                    log.info("Blocking interface established")
                }
                backend.setState(tunnel, Tunnel.State.DOWN, null)
                if (!lockdown) {
                    context.stopService(Intent(context, ConnectionService::class.java))
                    service?.releaseBlock()
                    serviceBinding?.let { context.unbindService(it) }
                    serviceBinding = null
                    boundService = null
                }
            } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                log.warn("Android could not stop the tunnel (${e.javaClass.simpleName})")
                status.value = previousState
                return@withLock
            }
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
            vpnService()
            // Recovery uses only the bootstrap transport on an underlying network, before
            // another attempt consumes cached device keys. Keep the existing tunnel installed.
            handshakeRefresh.recover { withTimeout(3_000) { repository.refreshAccount() } }
            if (repository.manifest.value == null && WaveVpnService.instance?.isBlocking != true)
                withTimeout(3_000) { repository.refresh() }
            val m = repository.manifest.value ?: error("Refresh the server list first")
            val a = repository.account.value ?: error("Sign in first")
            val prefs = preferences.current
            val device = a.key(prefs.deviceKey) ?: error("Select an available device")
            val mode = m.wireguardModes.find { it.port == prefs.port } ?: error("Select a supported WireGuard port")
            val candidates = Scoring.rank(m.servers, pinger.cached(m.servers), m.factors, allowFailedFallback = target != Target.Best) { s -> when(target) {
                Target.Best -> s.warningOpen == null
                is Target.Country -> s.countryCode.equals(target.code, true)
                is Target.Server -> s.name == target.name
            } }.take(if (target is Target.Server) 1 else 3)
            if (candidates.isEmpty()) throw NoCandidateException(if (target == Target.Best)
                "No server answered the latency check; pick a server or retry."
                else "No available server for this choice")
            var selected = candidates.last()
            var anyHandshake = false
            for (server in candidates) {
                currentCoroutineContext().ensureActive()
                selected = server
                selectedServer = server
                lastServerName = server.name
                status.value = TunnelState.Connecting(server)
                val config = Config.parse(wgQuickConfig(a, device, server, WgOptions(prefs.port, mode.entryIndex,
                    prefs.ipv6Endpoint,
                    if (prefs.splitMode == SplitMode.Exclude) prefs.splitApps else emptySet(),
                    if (prefs.splitMode == SplitMode.Include) prefs.splitApps else emptySet())).byteInputStream())
                log.info("Connecting to ${server.name}")
                withContext(Dispatchers.IO) {
                    val service = vpnService()
                    service.block()
                    backend.setState(tunnel, Tunnel.State.DOWN, null)
                    try {
                        backend.setState(tunnel, Tunnel.State.UP, config)
                        service.releaseBlock()
                    } catch (e: Exception) {
                        // establish() may already have replaced the blocking descriptor.
                        service.releaseBlock()
                        if (permissionIntent() == null) service.block()
                        throw e
                    }
                }
                started = true
                val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
                var handshook = false
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    delay(500)
                    val s = withContext(Dispatchers.IO) { backend.getStatistics(tunnel).peer(config.peers.first().publicKey) }
                    if ((s?.latestHandshakeEpochMillis ?: 0) > 0) { handshook = true; break }
                }
                if (handshook) { anyHandshake = true; break }
            }
            handshakeRefresh.outcome(anyHandshake)
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
                    recorded = true
                    handshakeRefresh.verifiedHandshake {
                        scope.launch {
                            try { withTimeout(3_000) { repository.refresh() } }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { log.warn("Using cached account and server information") }
                        }
                    }
                    count.value += 1; since.value = android.os.SystemClock.elapsedRealtime()
                    log.info("WireGuard handshake established")
                }
            }
            status.value = TunnelState.Down; since.value = null; counters.value = null
            context.stopService(Intent(context, ConnectionService::class.java))
        } catch (e: CancellationException) {
            // Cancellation is followed by another serialized connect or disconnect.
            // Keep the current TUN until the next serialized operation replaces it.
            throw e
        } catch (e: Exception) {
            // Keep an existing tunnel in place on monitor errors instead of opening a traffic gap.
            val retainedServer = selectedServer ?: repository.manifest.value?.servers?.find { it.name == lastServerName }
            val retainedTunnel = started || withContext(Dispatchers.IO) {
                runCatching { backend.getState(tunnel) == Tunnel.State.UP }.getOrDefault(false)
            }
            if (retainedTunnel && retainedServer != null && WaveVpnService.instance?.isBlocking != true) {
                status.value = TunnelState.Stalled(retainedServer)
                log.error("Connection monitoring failed; disconnect and retry")
            } else {
                if (WaveVpnService.instance?.isBlocking != true)
                    context.stopService(Intent(context, ConnectionService::class.java))
                status.value = TunnelState.Failed(if (e is NoCandidateException || e is CooldownException) e.message!! else "Check account, server and WireGuard settings")
                log.error("Unable to start WireGuard")
            }
        }
    }
}

private class NoCandidateException(message: String) : Exception(message)
