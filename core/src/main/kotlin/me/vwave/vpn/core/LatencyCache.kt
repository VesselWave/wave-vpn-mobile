// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpn.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Budgeted TCP samples. -1 is a cached failure, never a fast response. */
class LatencyCache(
    private val probe: suspend (String) -> Int,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val allowed: () -> Boolean = { true },
    private val parallelism: Int = 4,
    private val budget: Int = 24,
    private val minimumInterval: Long = 180_000,
    private val ttl: Long = 600_000,
    initialDelay: Long = 0,
    private val reserveSweep: () -> Unit = {},
) {
    private data class Sample(val host: String, val ping: Int, val at: Long)
    private val samples = mutableMapOf<String, Sample>()
    @Volatile private var published: Map<String, Sample> = emptyMap()
    private var sampleGeneration = 0
    private val guard = Mutex()
    private var nextSweep = clock() + initialDelay.coerceIn(0, minimumInterval)
    @Volatile private var job: Job? = null
    @Volatile private var generation = 0
    private val values = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val active = MutableStateFlow(false)
    val pings = values.asStateFlow()
    val running = active.asStateFlow()
    init { require(parallelism > 0 && budget > 0 && minimumInterval > 0 && ttl > 0) }

    fun stop() { job?.cancel() }
    fun clear() { generation++; stop(); published = emptyMap(); values.value = emptyMap() }

    private fun prune(servers: List<Server>) {
        if (sampleGeneration != generation) { samples.clear(); sampleGeneration = generation }
        samples.entries.removeAll { (name, sample) ->
            servers.none { it.name == name && sample.host in it.entries && it.isOpen }
        }
    }
    private fun publish(version: Int) {
        if (version == generation) {
            published = samples.toMap()
            values.value = published.filterValues { clock() - it.at < ttl }.mapValues { it.value.ping }
        }
    }

    /** Read current-route, unexpired samples without starting network activity. */
    fun cached(servers: List<Server>): Map<String, Int> {
        val view = published
        return servers.filter { it.isOpen }.mapNotNull { server ->
            view[server.name]?.takeIf { it.host in server.entries && clock() - it.at < ttl }
                ?.let { server.name to it.ping }
        }.toMap().also { values.value = it }
    }

    suspend fun measure(servers: List<Server>, priority: Set<String> = emptySet()) {
        if (!guard.tryLock()) return
        val version = generation
        try {
            prune(servers)
            publish(version)
            if (!allowed() || clock() < nextSweep) return
            reserveSweep()
            nextSweep = clock() + minimumInterval
            // One representative socket per location; spread the bounded sweep across countries
            // before returning to a country's other locations. Within a location prefer low load.
            val locations = servers.filter { it.isOpen && it.warningOpen == null }
                .groupBy { it.countryCode to it.location }
            val groups = locations.values.map { group ->
                group.sortedWith(compareBy<Server> { samples[it.name]?.let { s -> s.ping < 0 && clock() - s.at < ttl } == true }
                    .thenBy { it.name !in priority }
                    .thenBy { Scoring.score(it, null, Factors(), false) })
            }.filter { group -> group.any { s -> s.entries.any { ':' !in it } } }
            val countries = groups.groupBy { it.first().countryCode }
            val order = countries.values.flatMap { country -> country.mapIndexed { index, group -> index to group } }
                .sortedWith(compareBy<Pair<Int, List<Server>>> { (_, g) -> g.none { it.name in priority } }
                    .thenBy { it.first }
                    .thenBy { (_, g) -> g.mapNotNull { samples[it.name]?.at }.maxOrNull() ?: Long.MIN_VALUE })
            val candidates = order.map { it.second }.filter { group ->
                group.none { s -> samples[s.name]?.let { it.ping > 0 && clock() - it.at < ttl && it.host in s.entries } == true } &&
                    group.any { s -> samples[s.name]?.let { clock() - it.at < ttl && it.host in s.entries } != true }
            }.map { group -> group to group.firstNotNullOf { s -> s.entries.firstOrNull { ':' !in it } } }.take(budget)
            active.value = true
            coroutineScope {
                job = currentCoroutineContext().job
                // Fixed workers, rather than one coroutine per server.
                val queue = ArrayDeque(candidates)
                val queueLock = Mutex()
                List(parallelism.coerceAtMost(candidates.size)) {
                    launch {
                        while (isActive && allowed()) {
                            val item = queueLock.run {
                                lock()
                                try { queue.removeFirstOrNull() } finally { unlock() }
                            } ?: break
                            ensureActive()
                            if (!allowed()) break
                            val ping = try { probe(item.second).takeIf { it > 0 } ?: -1 }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { -1 }
                            ensureActive()
                            queueLock.lock()
                            try {
                                if (allowed() && version == generation) {
                                    // Location estimate is shared, but retain each server's address for
                                    // invalidation when routing changes in a later manifest.
                                    (if (ping > 0) item.first else item.first.filter { item.second in it.entries }.take(1)).forEach { server ->
                                        server.entries.firstOrNull { ':' !in it }?.let { host ->
                                            samples[server.name] = Sample(host, ping, clock())
                                        }
                                    }
                                    publish(version)
                                }
                            } finally { queueLock.unlock() }
                        }
                    }
                }.joinAll()
            }
        } finally { job = null; active.value = false; guard.unlock() }
    }
}
