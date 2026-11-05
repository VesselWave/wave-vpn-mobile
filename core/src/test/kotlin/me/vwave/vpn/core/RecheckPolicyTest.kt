// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpn.core

import kotlinx.coroutines.*
import me.vwave.vpn.core.FakeAirNetwork.Companion.fixtureServers
import me.vwave.vpn.core.FakeAirNetwork.Companion.keyA
import me.vwave.vpn.core.FakeAirNetwork.Companion.manifest
import me.vwave.vpn.core.FakeAirNetwork.Companion.user
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Independent always-on checks from the recheck of 9320cd6 (offline; fake network and clocks). */
class RecheckPolicyTest {
    private val t = FakeAirNetwork.Time()
    private val net = FakeAirNetwork { t.ms }
    private val urls = listOf("http://192.0.2.1", "http://192.0.2.2", "http://192.0.2.3",
        "http://192.0.2.4", "http://[2001:db8::1]", "http://bootstrap.example")
    private val doc = manifest(keyA, urls)
    private fun serve(vararg live: String) = live.forEach {
        net.route(it, keyA) { p -> if (p["act"] == "user") user(p.getValue("login")) else doc }
    }
    private fun session(initial: SessionSnapshot?, api: AirRequests = AirApi(transport = net, clock = { t.ms }),
        budget: Pair<Long, Int> = 0L to 0, save: (Long, Int) -> Unit = { _, _ -> }) =
        RefreshSession(api, initial, {}, clock = { t.wall }, fallback = keyA.bootstrap(urls),
            initialBudget = budget, saveBudget = save, elapsed = { t.ms })
    private fun snapshot() = SessionSnapshot("tester", "secret", user(), doc, t.wall, t.wall)

    @Test(timeout = 15000) fun `only the last bootstrap URL alive is reached quickly and the hour stays bounded`() {
        serve(urls[5])
        val s = session(snapshot())
        var reachedAt = -1L
        for (second in 0 until 3600) {
            if (runCatching { s.update(force = true) }.isSuccess && reachedAt < 0) reachedAt = t.ms
            t.advance(1_000)
        }
        assertTrue("URL6 reached at ${reachedAt / 1000}s", reachedAt in 0..(4 * 60_000))
        // After recovery the preferred URL is used directly: one post per document per minute at most.
        val failedPosts = net.posts.count { it.act == null }
        assertTrue("failed posts $failedPosts", failedPosts <= 5)
        assertTrue("total posts ${net.posts.size}", net.posts.size <= 60 * 2 + 5)
        assertEquals(urls[5], net.posts.last().url)
    }

    @Test(timeout = 15000) fun `every URL is tried once before any URL is retried`() {
        val s = session(snapshot())
        repeat(30) { runCatching { s.update(force = true) }; t.advance(15 * 60_000) }
        val firstRound = net.posts.take(urls.size).map { it.url }
        assertEquals(urls.toSet(), firstRound.toSet())
        assertTrue(net.posts.size <= 30 * 3)
    }

    @Test(timeout = 15000) fun `cancellation through networkWork sends no further request, keeps endpoint health and backoff`() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var block = true
        val posts = mutableListOf<String>()
        val transport = object : Transport {
            override fun post(url: String, body: String): ByteArray {
                synchronized(posts) { posts += url }
                if (block) {
                    block = false; entered.countDown()
                    while (release.count > 0) Thread.onSpinWait() // ignores, but keeps, the interrupt
                    throw IOException("Socket closed")
                }
                return net.post(url, body)
            }
            override fun cancel() { release.countDown() }
        }
        serve(*urls.toTypedArray())
        val api = AirApi(transport = transport, clock = { t.ms })
        var budget = 0L to 0
        val s = session(snapshot(), api, save = { at, f -> budget = at to f })
        val job = launch { runCatching { networkWork(api::cancel) { s.update(force = true) } } }
        withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
        job.cancel(); withTimeout(5_000) { job.join() }
        assertEquals(listOf(urls[0]), posts)
        assertEquals("cancellation consumes the interval but does not escalate", 0, budget.second)
        t.advance(60_000); posts.clear()
        s.update(force = true)
        assertEquals("healthy first URL not demoted", urls[0], posts.first())
    }

    @Test(timeout = 15000) fun `password rejections send exactly one request each and at most one per five seconds`() {
        urls.forEach { net.route(it, keyA) { "<user error=\"Wrong password\"/>" } }
        val s = session(null)
        repeat(3600) { runCatching { s.update("tester", "typo") }; t.advance(1_000) }
        assertTrue("repeated rejection should escalate after three short corrections", net.count("user") <= 64)
        assertEquals(setOf(urls[0]), net.posts.map { it.url }.toSet())
        net.posts.zipWithNext().forEach { (a, b) -> assertTrue(b.at!! - a.at!! >= 5_000) }
    }

    @Test(timeout = 15000) fun `sign-out and sign-in with a process restart every cycle stays within one attempt per minute`() {
        serve(*urls.toTypedArray())
        var budget = 0L to 0
        repeat(600) {
            val s = session(null, budget = budget, save = { at, f -> budget = at to f })
            runCatching { s.update("tester", "secret") }
            s.clear()
            t.advance(6_000)
        }
        assertTrue("user posts ${net.count("user")}", net.count("user") <= 61)
        assertEquals(net.count("user"), net.count("manifest"))
    }

    @Test(timeout = 15000) fun `explicit server in a location whose representative failed stays connectable`() = runBlocking {
        val fleet = fixtureServers()
        val group = fleet.filter { it.isOpen && it.warningOpen == null }
            .groupBy { it.countryCode to it.location }.values.first { it.size > 2 }
        val cache = LatencyCache({ host -> if (group.any { host in it.entries }) -1 else 30 }, clock = { 0L })
        cache.measure(fleet, group.map { it.name }.toSet())
        val pings = cache.cached(fleet)
        val target = group.last()
        assertEquals(listOf(target.name), Scoring.rank(fleet, pings, Factors(), allowFailedFallback = true) { it.name == target.name }.map { it.name })
        assertTrue(Scoring.rank(fleet, pings, Factors(), allowFailedFallback = false) { true }.none { pings[it.name] == -1 })
        assertTrue("unprobed location peers stay eligible", group.count { pings[it.name] != -1 } >= group.size - 1)
        // Retain the original all-failed-location rule when each peer really failed.
        assertTrue(Scoring.rank(fleet, pings + group.associate { it.name to -1 }, Factors(), allowFailedFallback = false) { true }
            .none { it.name in group.map { g -> g.name } })
    }

    @Test(timeout = 15000) fun `Best with every candidate failed yields no server (documented behavior)`() {
        val fleet = fixtureServers().filter { it.isOpen }
        assertTrue(Scoring.rank(fleet, fleet.associate { it.name to -1 }, Factors(), allowFailedFallback = false) { true }.isEmpty())
    }

    @Test(timeout = 15000) fun `latency still orders equal-load servers and unmeasured ones take the median`() {
        val base = fixtureServers().first { it.isOpen && it.warningOpen == null }.copy(users = 10, usersMax = 100, bandwidth = 0, scoreBase = 0)
        val fleet = listOf("Near", "Mid", "Far", "Unmeasured").map { base.copy(name = it) }
        val ranked = Scoring.rank(fleet, mapOf("Near" to 10, "Mid" to 100, "Far" to 300), Factors()).map { it.name }
        assertEquals(listOf("Near", "Mid", "Unmeasured", "Far"), ranked)
        val scores = Scoring.scores(fleet, mapOf("Near" to 10, "Mid" to 100, "Far" to 300), Factors())
        assertEquals(scores.getValue("Mid"), scores.getValue("Unmeasured"), 0.0)
    }
}
