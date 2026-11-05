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
) {
    private data class Sample(val host: String, val ping: Int, val at: Long)
    private val samples = mutableMapOf<String, Sample>()
    private var sampleGeneration = 0
    private val guard = Mutex()
    private var nextSweep = Long.MIN_VALUE
    @Volatile private var job: Job? = null
    @Volatile private var generation = 0
    private val values = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val active = MutableStateFlow(false)
    val pings = values.asStateFlow()
    val running = active.asStateFlow()
    init { require(parallelism > 0 && budget > 0 && minimumInterval > 0 && ttl > 0) }

    fun stop() { job?.cancel() }
    fun clear() { generation++; stop(); values.value = emptyMap() }

    private fun prune(servers: List<Server>) {
        if (sampleGeneration != generation) { samples.clear(); sampleGeneration = generation }
        samples.entries.removeAll { (name, sample) ->
            servers.none { it.name == name && sample.host in it.entries && it.isOpen }
        }
    }
    private fun publish(version: Int) {
        if (version == generation) values.value = samples.filterValues { clock() - it.at < ttl }
            .mapValues { it.value.ping }
    }

    /** Read current-route, unexpired samples without starting network activity. */
    suspend fun cached(servers: List<Server>): Map<String, Int> = guard.withLock {
        prune(servers)
        publish(generation)
        values.value
    }

    suspend fun measure(servers: List<Server>) {
        if (!guard.tryLock()) return
        val version = generation
        try {
            prune(servers)
            publish(version)
            if (!allowed() || clock() < nextSweep) return
            nextSweep = clock() + minimumInterval
            val candidates = servers.distinctBy { it.name }.filter { it.isOpen }
                .mapNotNull { s -> s.entries.firstOrNull { ':' !in it }?.let { s.name to it } }
                .filter { (name, _) -> samples[name]?.let { clock() - it.at < ttl } != true }
                .sortedBy { samples[it.first]?.at ?: Long.MIN_VALUE }.take(budget)
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
                                    samples[item.first] = Sample(item.second, ping, clock())
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
