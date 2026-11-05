// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpn.core

import kotlinx.coroutines.*
import me.vwave.vpn.core.FakeAirNetwork.Companion.fixtureServers
import me.vwave.vpn.core.FakeAirNetwork.Companion.keyA
import me.vwave.vpn.core.FakeAirNetwork.Companion.keyB
import me.vwave.vpn.core.FakeAirNetwork.Companion.manifest
import me.vwave.vpn.core.FakeAirNetwork.Companion.user
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Open findings from the independent recheck of 9320cd6, written as the behavior a fix should
 * produce. They FAIL on 9320cd6 and are skipped by default so the required suite stays green:
 *
 *     WAVE_RECHECK=1 ./gradlew :core:test --rerun-tasks --tests '*RecheckFindingsTest'
 *
 * Remove the gate (or the individual test) once its finding is fixed. See docs/NETWORK-POLICY-RECHECK.md.
 */
class RecheckFindingsTest {
    @Before fun gate() = assumeTrue("Opt in with WAVE_RECHECK=1", System.getenv("WAVE_RECHECK") == "1")

    private val t = FakeAirNetwork.Time()
    private val net = FakeAirNetwork { t.ms }
    private val stored = (1..6).map { "http://192.0.2.$it" }

    /**
     * R1 (high): a short offline period puts every stored URL at failures > 0, which switches
     * AirApi into recovery ordering. Recovery sorts all embedded routes first, and with six embedded
     * URLs the three-attempt cap is always spent on them, so the stored (latest manifest) routing is
     * never tried again in this process. If the embedded copy is outdated (rotated key here), refresh
     * and sign-in never recover once the network is back.
     */
    @Test fun `R1 stored routing is retried after an offline period even when embedded routing is outdated`() {
        val current = manifest(keyB, stored)
        var online = false
        stored.forEach { url -> net.route(url, keyB) { p ->
            if (!online) throw IOException("offline (simulated)")
            if (p["act"] == "user") user(p.getValue("login")) else current
        } }
        // App wiring: recovery = Defaults.bootstrap (six URLs, older key). Same hosts, rotated key.
        val embedded = keyA.bootstrap(stored)
        val session = RefreshSession(AirApi(transport = net, clock = { t.ms }),
            SessionSnapshot("tester", "secret", user(), current, t.wall, t.wall), {},
            clock = { t.wall }, fallback = keyB.bootstrap(stored), recovery = embedded, elapsed = { t.ms })
        // Two refreshes while offline (e.g. app opened in a tunnel/airplane transition).
        repeat(2) { runCatching { session.update(force = true) }; t.advance(60_000) }
        online = true
        var ok = false
        for (second in 0 until 2 * 3600) {
            if (runCatching { session.update(force = true) }.isSuccess && session.snapshot!!.manifestAt == t.wall) { ok = true; break }
            t.advance(1_000)
        }
        val tail = net.posts.takeLast(6).map { it.act }
        assertTrue("never recovered in 2h; last posts $tail", ok)
    }

    /** R1b (high): same stickiness when the embedded hosts were retired rather than re-keyed. */
    @Test fun `R1b stored routing is retried after an offline period when embedded hosts are dead`() {
        val current = manifest(keyA, stored)
        var online = false
        stored.forEach { url -> net.route(url, keyA) { p ->
            if (!online) throw IOException("offline (simulated)")
            if (p["act"] == "user") user(p.getValue("login")) else current
        } }
        val embedded = keyA.bootstrap((1..6).map { "http://198.51.100.$it" }) // never routed: retired
        val session = RefreshSession(AirApi(transport = net, clock = { t.ms }),
            SessionSnapshot("tester", "secret", user(), current, t.wall, t.wall), {},
            clock = { t.wall }, fallback = keyA.bootstrap(stored), recovery = embedded, elapsed = { t.ms })
        repeat(2) { runCatching { session.update(force = true) }; t.advance(60_000) }
        online = true
        var ok = false
        for (second in 0 until 2 * 3600) {
            if (runCatching { session.update(force = true) }.isSuccess && session.snapshot!!.manifestAt == t.wall) { ok = true; break }
            t.advance(1_000)
        }
        assertTrue("never recovered in 2h; last urls ${net.posts.takeLast(6).map { it.url }}", ok)
    }

    /**
     * R2 (medium, platform-dependent): cancellation is detected only through the thread interrupt
     * flag. Android's bundled OkHttp/Okio may consume the flag and throw InterruptedIOException
     * ("thread interrupted"). AirApi then treats the cancelled request as an endpoint failure and
     * keeps posting credentials to the next URLs after cancel(); RefreshSession escalates the outage
     * backoff. Fix: record cancellation in AirApi.cancel() and treat non-timeout InterruptedIOException
     * as cancellation.
     */
    @Test fun `R2 cancellation stops failover even when the platform clears the interrupt flag`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val posts = mutableListOf<String>()
        val transport = object : Transport {
            override fun post(url: String, body: String): ByteArray {
                synchronized(posts) { posts += url }
                if (posts.size == 1) {
                    entered.countDown()
                    while (release.count > 0) Thread.onSpinWait() // blocking socket: ignores, but keeps, the interrupt
                    Thread.interrupted() // Okio 1.x Timeout.throwIfReached() style: consumes the flag
                    throw InterruptedIOException("thread interrupted")
                }
                throw IOException("connect timed out (simulated)")
            }
            override fun cancel() { release.countDown() }
        }
        val api = AirApi(transport = transport, clock = { t.ms })
        val session = RefreshSession(api, SessionSnapshot("tester", "secret", user(), manifest(keyA, stored), t.wall, t.wall), {},
            clock = { t.wall }, fallback = keyA.bootstrap(stored), elapsed = { t.ms })
        val job = launch { runCatching { networkWork(api::cancel) { session.update(force = true) } } }
        withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
        job.cancel()
        withTimeout(5_000) { job.join() }
        assertEquals("posts after cancel: $posts", 1, posts.size)
        // The cancelled attempt must not escalate the outage backoff either.
        // One genuine failure afterwards must give the first backoff step (60 s), not the second (120 s).
        t.advance(60_000)
        assertThrows(IOException::class.java) { session.update(force = true) }
        t.advance(60_000)
        val next = assertThrows(IOException::class.java) { session.update(force = true) }
        assertFalse("cancelled attempt escalated backoff: ${next.message}", next is CooldownException)
    }

    /**
     * R3 (low, UX regression vs eca93f0): every LoginException, including AirVPN's own
     * explanation (expired account, identity mismatch, service message), is replaced with a generic
     * "check your credentials" text.
     */
    @Test fun `R3 AirVPN rejection text reaches the sign-in error`() {
        stored.forEach { net.route(it, keyA) { "<user error=\"Your account has expired\"/>" } }
        val session = RefreshSession(AirApi(transport = net, clock = { t.ms }), null, {},
            clock = { t.wall }, fallback = keyA.bootstrap(stored), elapsed = { t.ms })
        val e = assertThrows(LoginException::class.java) { session.update("tester", "secret") }
        assertTrue(e.message, e.message!!.contains("expired"))
    }

    /**
     * R4 (low/medium, selection quality): a single failed representative probe is copied to every
     * server in the location, so one server under maintenance (or one lost SYN) removes the whole
     * city from automatic Best for ten minutes. Successes may be shared; a failure should mark only
     * the probed server (or the next sweep should try another representative).
     */
    @Test fun `R4 one failed representative does not exclude its healthy location peers from Best`() = runBlocking {
        val fleet = fixtureServers()
        val group = fleet.filter { it.isOpen && it.warningOpen == null }
            .groupBy { it.countryCode to it.location }.values.maxBy { it.size }
        val cache = LatencyCache({ host -> if (group.any { host in it.entries }) -1 else 40 }, clock = { 0L })
        cache.measure(fleet, group.map { it.name }.toSet())
        val pings = cache.cached(fleet)
        val healthyPeers = group.count { pings[it.name] != -1 }
        assertTrue("all ${group.size} servers in ${group.first().location} marked failed by one probe", healthyPeers >= group.size - 1)
    }
}
