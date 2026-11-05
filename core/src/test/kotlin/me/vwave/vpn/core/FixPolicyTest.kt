// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpn.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FixPolicyTest {
    private val xml = FakeAirNetwork.manifest(FakeAirNetwork.keyA, listOf("http://192.0.2.1"))
    private val user = FakeAirNetwork.user()
    private fun snapshot() = SessionSnapshot("tester", "secret", user, xml, 1_000_000, 1_000_000)

    @Test(timeout = 15000) fun `signout and process restoration preserve outage cooldown and escalation`() {
        var now = 1_000_000L
        var budget = 0L to 0
        var calls = 0
        val api = AirRequests { _, _, _, _ -> calls++; throw IOException("offline") }
        fun engine(initial: SessionSnapshot?) = RefreshSession(api, initial, {}, clock = { now }, elapsed = { now },
            initialBudget = budget, saveBudget = { at, failures -> budget = at to failures })
        val first = engine(snapshot())
        assertThrows(IOException::class.java) { first.update(force = true) }
        first.clear()
        assertThrows(CooldownException::class.java) { first.update("tester", "secret") }
        val restored = engine(null)
        assertThrows(CooldownException::class.java) { restored.update("tester", "secret") }
        assertEquals(1, calls)
        now += 60_000
        assertThrows(IOException::class.java) { restored.update("tester", "secret") }
        assertEquals(now + 120_000, budget.first)
        assertEquals(2, calls)
    }

    @Test(timeout = 15000) fun `ordinary password corrections use five seconds then repeated rejection escalates`() {
        var now = 1_000_000L
        var calls = 0
        val engine = RefreshSession(AirRequests { _, _, _, _ -> calls++; throw LoginException("bad password") },
            null, {}, clock = { now }, elapsed = { now })
        repeat(8) { index ->
            assertThrows(LoginException::class.java) { engine.update("tester", "typo") }
            now += 1_000
            val remaining = if (index < 3) 4L else 59L
            assertEquals(remaining, assertThrows(CooldownException::class.java) { engine.update("tester", "secret") }.seconds)
            now += remaining * 1000
        }
        assertEquals(8, calls)
    }

    @Test(timeout = 15000) fun `overlapping login reports busy rather than successful completion`() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val engine = RefreshSession(AirRequests { _, action, _, _ -> entered.countDown(); release.await(); if (action == "user") user else xml }, snapshot(), {})
        val pool = Executors.newSingleThreadExecutor()
        try {
            val work = pool.submit { engine.update(force = true) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertThrows(IOException::class.java) { engine.update("tester", "secret") }
            release.countDown(); work.get(5, TimeUnit.SECONDS)
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test(timeout = 15000) fun `cache read and stop complete while an uncancellable probe still waits`() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val fleet = FakeAirNetwork.fixtureServers()
        val cache = LatencyCache({ entered.complete(Unit); withContext(NonCancellable) { release.await() }; 10 })
        val sweep = launch { cache.measure(fleet) }
        entered.await()
        try {
            withTimeout(500) { assertTrue(cache.cached(fleet).isEmpty()); cache.stop() }
            assertFalse(sweep.isCompleted)
        } finally { release.complete(Unit); sweep.join() }
        assertTrue(cache.cached(fleet).isEmpty())
    }

    @Test(timeout = 15000) fun `priority location is sampled first and estimates cover its peers within one socket`() = runBlocking {
        val fleet = FakeAirNetwork.fixtureServers()
        val group = fleet.filter { it.isOpen && it.warningOpen == null }.groupBy { it.countryCode to it.location }.values.last { it.size > 1 }
        var calls = 0; var host = ""
        val cache = LatencyCache({ calls++; host = it; 17 }, budget = 1)
        cache.measure(fleet, setOf(group.last().name))
        assertEquals(1, calls)
        assertTrue(host in group.last().entries)
        group.forEach { assertEquals(17, cache.cached(fleet)[it.name]) }
    }

    @Test(timeout = 15000) fun `restored sweep reservation prevents launch from spending another socket budget`() = runBlocking {
        var now = 0L; var reservations = 0; var calls = 0
        val cache = LatencyCache({ calls++; 10 }, clock = { now }, initialDelay = 180_000,
            reserveSweep = { reservations++ })
        repeat(100) { cache.measure(FakeAirNetwork.fixtureServers()) }
        assertEquals(0, calls); assertEquals(0, reservations)
        now = 180_000
        cache.measure(FakeAirNetwork.fixtureServers())
        assertEquals(24, calls); assertEquals(1, reservations)
    }

    @Test(timeout = 15000) fun `country with only failed TCP probes remains eligible but automatic Best does not`() {
        val fleet = FakeAirNetwork.fixtureServers().filter { it.isOpen }
        val country = fleet.first().countryCode
        val failures = fleet.associate { it.name to -1 }
        assertTrue(Scoring.rank(fleet, failures, Factors()).isEmpty())
        assertTrue(Scoring.rank(fleet, failures, Factors(), allowFailedFallback = false) { true }.isEmpty())
        val manual = Scoring.rank(fleet, failures, Factors()) { it.countryCode == country }
        assertEquals(fleet.count { it.countryCode == country }, manual.size)
    }

    @Test(timeout = 15000) fun `unmeasured idle server can outrank a measured overloaded server`() {
        val fleet = FakeAirNetwork.fixtureServers().filter { it.isOpen && it.warningOpen == null }
        val idle = fleet[0].copy(users = 0, bandwidth = 0, scoreBase = 0)
        val busy = fleet[1].copy(users = 100, usersMax = 100, scoreBase = 500)
        assertEquals(idle.name, Scoring.rank(listOf(idle, busy), mapOf(busy.name to 10), Factors()).first().name)
    }
    @Test(timeout = 15000) fun `network cancellation closes transport before waiting for blocked worker cleanup`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val released = CountDownLatch(1)
        var closed = false
        val job = launch {
            networkWork(cancel = { closed = true; released.countDown() }) {
                entered.complete(Unit)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (released.count > 0 && System.nanoTime() < deadline) {
                    try { released.await(100, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { /* platform socket ignores interrupt */ }
                }
            }
        }
        entered.await()
        job.cancel()
        withTimeout(1_000) { job.join() }
        assertTrue(closed)
    }

    @Test(timeout = 15000) fun `UI and rank share the same neutral scores for unmeasured servers`() {
        val fleet = FakeAirNetwork.fixtureServers().filter { it.isOpen && it.warningOpen == null }
        val pings = mapOf(fleet.first().name to 250)
        val scores = Scoring.scores(fleet, pings, Factors())
        val ranked = Scoring.rank(fleet, pings, Factors())
        assertEquals(scores.values.minOrNull(), scores[ranked.first().name])
        assertTrue(scores.values.all { it.isFinite() })
    }

    @Test(timeout = 15000) fun `embedded recovery uses its own rotated key within the shared attempt cap`() {
        var now = 0L
        val urls = (1..6).map { "http://192.0.2.$it" }
        val net = FakeAirNetwork { now }
        val old = FakeAirNetwork.keyA.bootstrap(urls)
        val updated = FakeAirNetwork.keyB.bootstrap(urls)
        val manifest = FakeAirNetwork.manifest(FakeAirNetwork.keyB, urls)
        urls.forEach { net.route(it, FakeAirNetwork.keyB) { manifest } }
        val api = AirApi(transport = net, clock = { now })
        repeat(2) {
            val before = net.posts.size
            assertThrows(IOException::class.java) { api.requestWithFallback(old, updated, "manifest", "tester", "secret") }
            assertTrue(net.posts.size - before <= 3)
            now += 60_000
        }
        val before = net.posts.size
        assertEquals(manifest, api.requestWithFallback(old, updated, "manifest", "tester", "secret"))
        assertTrue(net.posts.size - before <= 3)
        assertEquals(urls.toSet(), net.posts.take(6).map { it.url }.toSet())
    }

    @Test(timeout = 15000) fun `logout keeps latest manifest routing as well as shared cooldown`() {
        var now = 1_000_000L
        val updated = FakeAirNetwork.manifest(FakeAirNetwork.keyB, listOf("https://rotated.example"))
        val used = mutableListOf<Bootstrap>()
        val engine = RefreshSession(AirRequests { boot, action, _, _ ->
            used += boot
            if (action == "user") user else updated
        }, snapshot(), {}, clock = { now }, elapsed = { now })
        engine.update(force = true)
        engine.clear()
        now += 60_000
        engine.update("tester", "secret")
        assertEquals(parseManifest(updated).bootstrap, used[1])
        assertEquals(parseManifest(updated).bootstrap, used[2])
    }

}
