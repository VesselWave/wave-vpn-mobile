// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpn.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LatencyCacheTest {
    private fun servers(n: Int = 250) = (1..n).map {
        Server("server$it", "nl", "City$it", listOf("192.0.2.$it"), 0, 100, 0, 100, 0, null, null)
    }

    @Test fun `sweep budget and fixed worker limit with concurrent clicks`() = runBlocking {
        var now = 0L
        var calls = 0; var active = 0; var peak = 0
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val cache = LatencyCache({
            calls++; active++; peak = maxOf(peak, active)
            if (active == 4) entered.complete(Unit)
            release.await(); active--; 10
        }, clock = { now })
        val first = launch { cache.measure(servers()) }
        entered.await()
        repeat(100) { cache.measure(servers()) }
        assertTrue(cache.running.value)
        release.complete(Unit); first.join()
        assertEquals(4, peak); assertEquals(24, calls)
        repeat(100) { cache.measure(servers()) }
        assertEquals(24, calls)
        now += 180_000
        cache.measure(servers())
        assertEquals(48, calls)
        assertEquals(48, cache.pings.value.size)
        assertFalse(cache.running.value)
    }

    @Test fun `success and failure cached until expiry then retried`() = runBlocking {
        var now = 0L; var calls = 0
        val cache = LatencyCache({ calls++; if (calls == 1) throw java.io.IOException() else 12 }, { now })
        cache.measure(servers(2))
        assertEquals(-1, cache.pings.value["server1"])
        assertEquals(12, cache.pings.value["server2"])
        now = 180_000; cache.measure(servers(2)); assertEquals(2, calls)
        now = 600_000; cache.measure(servers(2)); assertEquals(4, calls)
        assertEquals(12, cache.pings.value["server1"])
    }

    @Test fun `no probes while connected connecting blocked or background and stop midway`() = runBlocking {
        var allowed = false; var calls = 0; var now = 0L
        val cache = LatencyCache({ calls++; allowed = false; 5 }, { now }, { allowed })
        repeat(20) { cache.measure(servers()) }
        assertEquals(0, calls)
        allowed = true
        cache.measure(servers())
        assertEquals(1, calls)
        assertTrue(cache.pings.value.isEmpty()) // result through a changed route is discarded
        allowed = true
        cache.measure(servers()); assertEquals(1, calls)
        now = 180_000
        cache.measure(servers()); assertEquals(2, calls)
    }

    @Test fun `cancellation releases guard and suppresses immediate retry storm`() = runBlocking {
        var now = 0L; var calls = 0
        val entered = CompletableDeferred<Unit>()
        var wait = true
        val cache = LatencyCache({ calls++; entered.complete(Unit); if (wait) awaitCancellation() else 8 }, { now })
        val sweep = launch { cache.measure(servers()) }
        entered.await(); cache.stop(); sweep.join()
        assertFalse(cache.running.value)
        val count = calls
        repeat(50) { cache.measure(servers()) }
        assertEquals(count, calls)
        now = 180_000; wait = false
        cache.measure(servers(1))
        assertEquals(8, cache.pings.value["server1"])
    }

    @Test fun `changed addresses removed closed and IPv6 only servers not probed`() = runBlocking {
        var calls = 0; var now = 0L
        val cache = LatencyCache({ calls++; 9 }, { now })
        cache.measure(servers(1))
        now = 180_000
        cache.measure(listOf(servers(1).first().copy(entries = listOf("192.0.2.254")),
            servers(2).last().copy(warningClosed = "closed"),
            servers(3).last().copy(entries = listOf("2001:db8::1"))))
        assertEquals(2, calls)
        assertEquals(setOf("server1"), cache.pings.value.keys)
        cache.clear()
        assertTrue(cache.pings.value.isEmpty())
    }

    @Test fun `connection reads prune expired samples without network activity`() = runBlocking {
        var now = 0L; var calls = 0
        val cache = LatencyCache({ calls++; -1 }, { now })
        cache.measure(servers(1))
        assertEquals(-1, cache.cached(servers(1))["server1"])
        now = 600_000
        assertTrue(cache.cached(servers(1)).isEmpty())
        assertEquals(1, calls)
    }

    @Test fun `logout during sweep cannot repopulate cleared cache`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cache = LatencyCache({ entered.complete(Unit); awaitCancellation() })
        val sweep = launch { cache.measure(servers()) }
        entered.await()
        cache.clear()
        sweep.join()
        assertTrue(cache.pings.value.isEmpty())
        assertFalse(cache.running.value)
    }

    @Test fun `unreachable cannot win even against extreme load or if every probe fails`() {
        val all = servers(3)
        val loaded = all[1].copy(users = 100000, scoreBase = 100000)
        assertEquals("server2", Scoring.rank(listOf(all[0], loaded), mapOf("server1" to -1, "server2" to 20), Factors()).first().name)
        assertTrue(Scoring.rank(all, all.associate { it.name to -1 }, Factors()).isEmpty())
        assertEquals(Double.POSITIVE_INFINITY, Scoring.score(all[0], -1, Factors()), 0.0)
        assertEquals("server2", Scoring.rank(all, mapOf("server2" to 20), Factors()).first().name)
        assertEquals(3, Scoring.rank(all, emptyMap(), Factors()).size) // first-run/offline cache remains usable
    }
}
