// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpn.core

import kotlinx.coroutines.runBlocking
import me.vwave.vpn.core.FakeAirNetwork.Companion.fixtureServers
import me.vwave.vpn.core.FakeAirNetwork.Companion.keyA
import me.vwave.vpn.core.FakeAirNetwork.Companion.manifest
import me.vwave.vpn.core.FakeAirNetwork.Companion.user
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Open review findings against eca93f0, written as the behavior a fix should produce.
 * They FAIL on eca93f0 and are skipped by default so the required suite stays green:
 *
 *     WAVE_FINDINGS=1 ./gradlew :core:test --rerun-tasks --tests '*ReviewFindingsTest'
 *
 * Remove the gate (or the individual test) once its finding is fixed. See docs/NETWORK-POLICY-REVIEW.md.
 */
class ReviewFindingsTest {
    @Before fun gate() = assumeTrue("Opt in with WAVE_FINDINGS=1", System.getenv("WAVE_FINDINGS") == "1")

    private val t = FakeAirNetwork.Time()
    private val net = FakeAirNetwork { t.ms }
    private val urls = listOf("http://192.0.2.1", "http://192.0.2.2", "http://192.0.2.3",
        "http://192.0.2.4", "http://[2001:db8::1]", "http://bootstrap.example")
    private val doc = manifest(keyA, urls)
    private fun serve(vararg live: String, xml: String = doc, users: (String) -> String = { user(it) }) = live.forEach {
        net.route(it, keyA) { p -> if (p["act"] == "user") users(p.getValue("login")) else xml }
    }
    private fun session(initial: SessionSnapshot? = null, api: AirRequests = AirApi(transport = net, clock = { t.ms }),
        fallback: Bootstrap = keyA.bootstrap(urls)) =
        RefreshSession(api, initial, {}, clock = { t.wall }, fallback = fallback, elapsed = { t.ms })
    private fun snapshot(manifestXml: String = doc) = SessionSnapshot("tester", "secret", user(), manifestXml, t.wall, t.wall)

    /** F1 (high): with the first three URLs blocked, the 4th-6th are never tried under the session retry schedule. */
    @Test fun `F1 outage of the first three bootstrap URLs eventually fails over to the others`() {
        serve(urls[3], urls[5])
        val s = session(snapshot())
        var ok = false
        for (second in 0 until 3600) {
            ok = runCatching { s.update(force = true) }.isSuccess && net.posts.any { it.act == "manifest" }
            if (ok) break
            t.advance(1_000)
        }
        assertTrue("only ever contacted ${net.posts.map { it.url }.toSet()}", ok)
    }

    /** F2a (high): one sample anywhere makes every unsampled server +Infinity, so load is ignored and names decide. */
    @Test fun `F2a unsampled servers keep load ordering when some other server was sampled`() {
        val base = fixtureServers().filter { it.isOpen && it.warningOpen == null }.take(3)
        val busy = base[0].copy(name = "Aaa-busy", bandwidth = base[0].bandwidthMax * 125_000, users = 99, usersMax = 100)
        val idle = base[1].copy(name = "Zzz-idle", bandwidth = 0, users = 1, usersMax = 100)
        val far = base[2].copy(name = "Far")
        val ranked = Scoring.rank(listOf(busy, idle, far), mapOf("Far" to 280), Factors()).map { it.name }
        assertTrue("idle must outrank busy: $ranked", ranked.indexOf("Zzz-idle") < ranked.indexOf("Aaa-busy"))
    }

    /** F2b (high): Country target whose servers were not in the 24-sample budget is picked alphabetically. */
    @Test fun `F2b country target picks its least loaded server when only other countries were sampled`() = runBlocking {
        val fleet = fixtureServers()
        val cache = LatencyCache({ 50 }, clock = { 0L })
        cache.measure(fleet) // launch sweep
        val country = fleet.filter { it.isOpen && it.warningOpen == null }.groupBy { it.countryCode }
            .entries.first { (_, l) -> l.size >= 3 && l.none { it.name in cache.pings.value } }
        val expected = country.value.minBy { Scoring.score(it, null, Factors(), usePing = false) }
        val picked = Scoring.rank(fleet, cache.pings.value, Factors()) { it.countryCode == country.key }.first()
        assertEquals("country ${country.key}", expected.name, picked.name)
    }

    /** F2c (high): the 24-sample budget in manifest order confines Best to the alphabetical prefix of the fleet. */
    @Test fun `F2c Best after the launch sweep matches full-information Best when latency is uniform per location`() = runBlocking {
        val fleet = fixtureServers()
        val country = fleet.associate { it.entries.first() to it.countryCode }
        // A user in Singapore: SG 8 ms, JP 70 ms, everything else 250 ms (uniform within each location).
        val model = { host: String -> when (country[host]) { "sg" -> 8; "jp" -> 70; else -> 250 } }
        val full = Scoring.rank(fleet, fleet.filter { it.isOpen }.associate { it.name to model(it.entries.first()) }, Factors()).first()
        val cache = LatencyCache({ model(it) }, clock = { 0L })
        cache.measure(fleet)
        val best = Scoring.rank(fleet, cache.cached(fleet), Factors()).first()
        assertEquals("sampled ${cache.pings.value.keys.sorted().take(3)}...", full.name, best.name)
    }

    /** F3 (high): a cached failed TCP sample removes an explicitly chosen server, so Connect fails for 10 minutes. */
    @Test fun `F3 explicitly chosen server remains connectable after one failed TCP probe`() {
        val server = fixtureServers().first { it.isOpen }
        assertEquals(listOf(server.name), Scoring.rank(listOf(server), mapOf(server.name to -1), Factors()) { it.name == server.name }.map { it.name })
    }

    /** F4 (medium): a refresh while signed out (Quick Settings tile -> connect) arms the cooldown and blocks sign-in. */
    @Test fun `F4 refresh while signed out does not block the next sign-in`() {
        serve(*urls.toTypedArray())
        val s = session(null)
        s.update() // TunnelManager.connection -> repository.refresh() with no account
        t.advance(5_000)
        s.update("tester", "secret")
        assertEquals(1, net.count("user"))
    }

    /** F5 (medium): once a manifest is stored, the embedded bootstrap is never used again, even if every stored URL is dead. */
    @Test fun `F5 dead persisted bootstrap falls back to the embedded routing`() {
        val stale = manifest(keyA, listOf("http://198.51.100.200", "http://198.51.100.201"))
        serve(*urls.toTypedArray())
        val s = session(SessionSnapshot("tester", "secret", user(), stale, t.wall - 31 * 60_000, t.wall),
            fallback = keyA.bootstrap(urls))
        var ok = false
        repeat(3600) { if (!ok) { ok = runCatching { s.update() }.isSuccess && net.count("manifest") > 0; t.advance(1_000) } }
        assertTrue("posted only to ${net.posts.map { it.url }.toSet()}", ok)
    }

    /** F6 (medium, UX): a device added on the website is invisible to manual refresh for up to six hours. */
    @Test fun `F6 manual refresh picks up account device changes within a short bound`() {
        var devices = arrayOf("phone")
        serve(*urls.toTypedArray(), users = { user(it, *devices) })
        val s = session(null)
        s.update("tester", "secret")
        devices = arrayOf("phone", "tablet")
        t.advance(15 * 60_000)
        s.update(force = true)
        assertEquals(listOf("phone", "tablet"), parseUser(s.snapshot!!.user, "tester").keys.map { it.name })
    }

    /** F7 (medium, UX): a password typo locks sign-in out locally, escalating to 15 minutes. */
    @Test fun `F7 corrected password shortly after a typo reaches AirVPN`() {
        serve(*urls.toTypedArray(), users = { "<user error=\"Wrong password\"/>" })
        val s = session(null)
        assertThrows(IOException::class.java) { s.update("tester", "typo") }
        serve(*urls.toTypedArray())
        t.advance(15_000)
        s.update("tester", "secret")
        assertNotNull(s.snapshot)
    }

    /** F8 (low): a cancelled/interrupted request marks the healthy endpoint as failing and demotes it. */
    @Test fun `F8 cancellation does not put a healthy endpoint into backoff`() {
        var cancel = true
        net.route(urls[0], keyA) { if (cancel) { Thread.currentThread().interrupt(); throw InterruptedIOException() } else doc }
        serve(urls[1])
        val api = AirApi(transport = net, clock = { t.ms })
        val pool = Executors.newSingleThreadExecutor()
        try {
            pool.submit { runCatching { api.request(keyA.bootstrap(urls), "manifest", "x", "y") }; Thread.interrupted() }.get(5, TimeUnit.SECONDS)
        } finally { pool.shutdownNow() }
        cancel = false; net.posts.clear()
        api.request(keyA.bootstrap(urls), "manifest", "x", "y")
        assertEquals(listOf(urls[0]), net.posts.map { it.url })
    }

    /** F9 (low): when account and manifest are both due and only the manifest fails, the fresh account is discarded and re-sent. */
    @Test fun `F9 account fetched before a manifest failure is not requested again on retry`() {
        var manifestUp = false
        urls.forEach { net.route(it, keyA) { p -> if (p["act"] == "user") user() else if (manifestUp) doc else throw IOException("503") } }
        val s = session(SessionSnapshot("tester", "secret", user(), doc, t.wall - 7 * 60 * 60_000L, t.wall - 7 * 60 * 60_000L))
        assertThrows(IOException::class.java) { s.update() }
        manifestUp = true
        t.advance(10 * 60_000)
        s.update()
        assertEquals(1, net.count("user"))
    }
}
