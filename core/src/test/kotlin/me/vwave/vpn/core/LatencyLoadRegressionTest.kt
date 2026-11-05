// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpn.core

import kotlinx.coroutines.*
import me.vwave.vpn.core.FakeAirNetwork.Companion.fixtureServers
import org.junit.Assert.*
import org.junit.Test

/**
 * Independent regression suite for AirVPN Staff's latency concerns (forum comment 268064):
 * fleet-wide TCP 443 sweeps at every launch/login/refresh, unlimited manual refresh, probes through
 * the tunnel, and unreachable servers winning "Best". Uses the recorded 259-server Eddie manifest
 * with documentation addresses and a fake probe; no sockets are opened.
 */
class LatencyLoadRegressionTest {
    private val fleet = fixtureServers()
    private var now = 0L

    @Test fun `an hour of refresh spam never re-probes a server within ten minutes nor exceeds four sockets`() = runBlocking {
        val probes = mutableListOf<Pair<String, Long>>()
        var active = 0; var peak = 0
        val cache = LatencyCache({ host ->
            probes += host to now; active++; peak = maxOf(peak, active)
            yield(); yield()
            active--; 40
        }, clock = { now })
        repeat(3600) { cache.measure(fleet); now += 1_000 }
        assertTrue("peak sockets $peak", peak in 1..4)
        assertTrue("total probes ${probes.size}", probes.size <= 20 * 24)
        assertTrue("a fleet-wide sweep never happens in one call", probes.groupBy { it.second }.values.all { it.size <= 24 })
        probes.groupBy { it.first }.values.forEach { times ->
            times.zipWithNext().forEach { (a, b) -> assertTrue("re-probed after ${b.second - a.second} ms", b.second - a.second >= 600_000) }
        }
    }

    @Test fun `launch login and refresh in quick succession share one sweep`() = runBlocking {
        var calls = 0
        val cache = LatencyCache({ calls++; 30 }, clock = { now })
        cache.measure(fleet) // ViewModel init
        now += 2_000; cache.measure(fleet) // login completion
        now += 5_000; repeat(10) { cache.measure(fleet) } // manual refresh taps
        assertEquals(24, calls)
    }

    @Test fun `tunnel coming up mid-sweep starts no further probe and publishes nothing measured through it`() = runBlocking {
        var allowed = true
        val startedHosts = mutableListOf<String>()
        val started = mutableListOf<Boolean>() // allowed() at probe start
        val cache = LatencyCache({ host -> startedHosts += host; started += allowed; if (started.size == 6) allowed = false; yield(); 25 },
            clock = { now }, allowed = { allowed })
        cache.measure(fleet)
        assertTrue(started.all { it })
        assertTrue("stopped well before budget: ${started.size}", started.size < 24)
        val before = cache.pings.value.size
        val count = started.size
        repeat(50) { now += 60_000; cache.measure(fleet) } // connected for 50 minutes
        assertEquals(count, started.size)
        assertTrue("nothing new published while connected", cache.pings.value.size <= before)
        val measuredBeforeRouteChange = fleet.filter { it.name in cache.pings.value }
        val safeLocations = fleet.filter { it.entries.firstOrNull() in startedHosts.take(5) }
            .map { it.countryCode to it.location }.toSet()
        assertTrue(measuredBeforeRouteChange.all { (it.countryCode to it.location) in safeLocations })
    }

    @Test(timeout = 15000) fun `stop with uncancellable in-flight sockets starts nothing new and discards late results`() = runBlocking {
        var calls = 0
        val inFlight = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val cache = LatencyCache({
            calls++; if (calls == 4) inFlight.complete(Unit)
            withContext(NonCancellable) { release.await() } // like a blocking Socket.connect
            15
        }, clock = { now })
        val sweep = launch { cache.measure(fleet) }
        inFlight.await()
        cache.stop()
        release.complete(Unit)
        sweep.join()
        assertEquals(4, calls)
        assertTrue(cache.pings.value.isEmpty())
        assertFalse(cache.running.value)
    }

    @Test fun `connection path only reads the cache even when a sweep is due`() = runBlocking {
        var calls = 0
        val cache = LatencyCache({ calls++; 10 }, clock = { now })
        repeat(20) { now += 600_000; cache.cached(fleet) }
        assertEquals(0, calls)
    }

    @Test fun `samples from before sign-out are invisible to the next account`() = runBlocking {
        val cache = LatencyCache({ 10 }, clock = { now })
        cache.measure(fleet)
        assertTrue("location estimates cover more than the 24 sockets", cache.pings.value.size > 24)
        cache.clear()
        assertTrue(cache.pings.value.isEmpty())
        assertTrue(cache.cached(fleet).isEmpty())
    }

    @Test fun `zero negative or failed probe results are cached as unreachable not fast`() = runBlocking {
        val replies = ArrayDeque(listOf(0, -7, Int.MIN_VALUE))
        val cache = LatencyCache({ replies.removeFirstOrNull() ?: throw java.net.SocketTimeoutException() }, clock = { now })
        // Distinct locations ensure all four responses are individually probed.
        val four = fleet.filter { it.isOpen && it.warningOpen == null }.take(4).mapIndexed { i, s -> s.copy(location = "location$i") }
        cache.measure(four)
        assertEquals(four.associate { it.name to -1 }, cache.pings.value)
        assertEquals(Double.POSITIVE_INFINITY, Scoring.score(four[0], cache.pings.value[four[0].name], Factors()), 0.0)
    }

    @Test fun `failed sample never wins Best over a responsive or unmeasured server`() {
        val (a, b, c) = fleet.filter { it.isOpen && it.warningOpen == null }.take(3)
        val fast = a.copy(users = 0, bandwidth = 0, scoreBase = 0)
        val busy = b.copy(users = 100, usersMax = 100, scoreBase = 100_000)
        assertEquals(busy.name, Scoring.rank(listOf(fast, busy), mapOf(fast.name to -1, busy.name to 300), Factors()).first().name)
        assertNotEquals(fast.name, Scoring.rank(listOf(fast, c), mapOf(fast.name to -1), Factors()).firstOrNull()?.name)
    }

    @Test fun `closed servers and IPv6-only entries are never probed`() = runBlocking {
        val hosts = mutableListOf<String>()
        val closed = fleet[0].copy(warningClosed = "maintenance")
        val v6 = fleet[1].copy(entries = listOf("2001:db8::7"))
        val cache = LatencyCache({ hosts += it; 10 }, clock = { now })
        cache.measure(listOf(closed, v6, fleet[2]))
        assertEquals(fleet[2].entries, hosts)
    }
}
