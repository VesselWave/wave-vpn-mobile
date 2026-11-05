// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpn.core

import me.vwave.vpn.core.FakeAirNetwork.Companion.keyA
import me.vwave.vpn.core.FakeAirNetwork.Companion.keyB
import me.vwave.vpn.core.FakeAirNetwork.Companion.manifest
import me.vwave.vpn.core.FakeAirNetwork.Companion.user
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Independent regression suite for AirVPN Staff's bootstrap concerns (forum comment 268064):
 * user+manifest at every refresh, always the first URL, no failover, no increasing backoff, and
 * manifest-advertised URL/RSA rotation. Drives the real RefreshSession + AirApi + envelope code
 * against encrypted fake servers on a virtual clock; no network or sleeping.
 */
class BootstrapLoadRegressionTest {
    private val t = FakeAirNetwork.Time()
    private val net = FakeAirNetwork { t.ms }
    private val urls = listOf("http://192.0.2.1", "http://192.0.2.2", "http://192.0.2.3",
        "http://192.0.2.4", "http://[2001:db8::1]", "http://bootstrap.example")
    private val doc = manifest(keyA, urls)
    private var saved: SessionSnapshot? = null
    private fun serve(vararg live: String, manifestXml: String = doc, key: FakeAirNetwork.Key = keyA) = live.forEach {
        net.route(it, key) { p -> if (p["act"] == "user") user(p.getValue("login")) else manifestXml }
    }
    private fun api() = AirApi(transport = net, clock = { t.ms })
    private fun session(initial: SessionSnapshot? = null, api: AirRequests = api(), fallback: Bootstrap = keyA.bootstrap(urls)) =
        RefreshSession(api, initial, { saved = it }, clock = { t.wall }, fallback = fallback, elapsed = { t.ms })
    private fun snapshot(ageMs: Long = 0, manifestXml: String = doc) =
        SessionSnapshot("tester", "secret", user(), manifestXml, t.wall - ageMs, t.wall - ageMs)

    @Test fun `five hours of automatic refreshes send the account once and follow next_update`() {
        serve(*urls.toTypedArray())
        val s = session()
        s.update("tester", "secret")
        repeat(1800) { t.advance(10_000); s.update() } // a refresh trigger every 10 s for 5 h
        assertEquals("account requested only at sign-in", 1, net.count("user"))
        assertEquals("sign-in + one per 30 min next_update", 11, net.count("manifest"))
        assertEquals("sticks to one healthy endpoint", setOf(urls[0]), net.posts.map { it.url }.toSet())
        repeat(360) { t.advance(10_000); s.update() } // reach six hours
        assertEquals(2, net.count("user"))
    }

    @Test fun `manual refresh spam is held to one manifest per minute and never re-sends the account`() {
        serve(urls[0])
        val s = session(snapshot())
        repeat(600) { t.advance(1_000); s.update(force = true) } // ten minutes, one tap per second
        assertEquals(0, net.count("user"))
        assertEquals(10, net.count("manifest"))
    }

    @Test fun `hour-long bootstrap outage backs off with increasing gaps and at most three sockets per attempt`() {
        val s = session(snapshot())
        repeat(3600) {
            try { s.update(force = true) } catch (_: IOException) {}
            t.advance(1_000)
        }
        val attempts = net.posts.groupBy { it.at!! }
        assertEquals(listOf(0L, 60_000, 180_000, 420_000, 900_000, 1_800_000, 2_700_000), attempts.keys.toList())
        assertTrue(attempts.values.all { it.size <= 3 })
        assertTrue("never more than 21 sockets in an hour, was ${net.posts.size}", net.posts.size <= 21)
        assertNull("failures never overwrite the usable snapshot", saved)
        assertEquals("tester", s.snapshot!!.login)
    }

    @Test fun `blackholed primary fails over within the same refresh and later requests start at the survivor`() {
        serve(urls[1])
        val s = session(snapshot(ageMs = 31 * 60_000))
        s.update()
        assertEquals(listOf(urls[0], urls[1]), net.posts.map { it.url })
        net.posts.clear()
        t.advance(31 * 60_000)
        s.update()
        assertEquals(listOf(urls[1]), net.posts.map { it.url })
    }

    @Test fun `malformed or truncated document from one endpoint fails over to the next`() {
        net.route(urls[0], keyA) { "<manifest" }
        serve(urls[1])
        val s = session(snapshot(ageMs = 31 * 60_000))
        s.update()
        assertEquals(2, net.count("manifest"))
        assertNotNull(saved)
    }

    @Test fun `valid XML that fails manifest validation keeps the previous snapshot and cools down`() {
        net.route(urls[0], keyA) { "<manifest/>" }
        val old = snapshot(ageMs = 31 * 60_000)
        val s = session(old)
        assertThrows(Exception::class.java) { s.update() }
        assertSame(old, s.snapshot)
        repeat(30) { runCatching { s.update() } }
        assertEquals(1, net.posts.size)
    }

    @Test fun `rotated manifest URLs and RSA key are used end to end and survive restore`() {
        val newUrls = listOf("https://203.0.113.10", "https://203.0.113.11")
        val rotated = manifest(keyB, newUrls)
        serve(*urls.toTypedArray(), manifestXml = rotated) // old servers advertise the rotation
        serve(*newUrls.toTypedArray(), manifestXml = rotated, key = keyB)
        val s = session()
        s.update("tester", "secret")
        assertEquals(urls[0], net.posts.last().url)
        urls.forEach(net::down) // old fleet retired
        t.advance(60_000)
        s.update(force = true)
        assertEquals(newUrls[0], net.posts.last().url)
        assertEquals("encrypted for the rotated key", "manifest", net.posts.last().act)
        val restored = session(saved, api = api())
        t.advance(30 * 60_000)
        restored.update()
        assertEquals(newUrls[0], net.posts.last().url)
        assertTrue(net.posts.none { it.act == "undecryptable" })
    }

    @Test fun `concurrent refresh from eight threads produces one exchange`() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        net.route(urls[0], keyA) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); doc }
        val s = session(snapshot())
        val pool = Executors.newFixedThreadPool(8)
        try {
            val first = pool.submit { s.update(force = true) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            (1..7).map { pool.submit { s.update(force = true) } }.forEach { it.get(5, TimeUnit.SECONDS) }
            release.countDown(); first.get(5, TimeUnit.SECONDS)
            assertEquals(1, net.posts.size)
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test(timeout = 15000) fun `interrupt during a transport stops failover to other endpoints`() {
        net.route(urls[0], keyA) { Thread.currentThread().interrupt(); throw InterruptedIOException("cancelled") }
        serve(urls[1])
        val api = api()
        val pool = Executors.newSingleThreadExecutor()
        try {
            val outcome = pool.submit<Throwable?> {
                try { api.request(keyA.bootstrap(urls), "manifest", "tester", "secret"); null }
                catch (e: Throwable) { e } finally { Thread.interrupted() }
            }.get(5, TimeUnit.SECONDS)
            assertNotNull(outcome)
            assertEquals(listOf(urls[0]), net.posts.map { it.url })
        } finally { pool.shutdownNow() }
    }

    @Test fun `a long advertised URL list is capped at three attempts per document`() {
        val many = (1..20).map { "http://192.0.2.${100 + it}" }
        assertThrows(IOException::class.java) { api().request(keyA.bootstrap(many), "manifest", "x", "y") }
        assertEquals(3, net.posts.size)
    }

    @Test fun `offline relaunch with stale manifest makes one bounded attempt and keeps cached selection`() {
        val old = snapshot(ageMs = 2 * 60 * 60_000L)
        val s = session(old)
        assertThrows(IOException::class.java) { s.update() } // ViewModel launch refresh
        repeat(10) { assertThrows(IOException::class.java) { s.update() } } // connect taps
        assertTrue(net.posts.size <= 3)
        assertSame(old, s.snapshot)
        assertFalse(parseManifest(s.snapshot!!.manifest).servers.isEmpty())
    }

    @Test fun `manifest RSA and next_update validation`() {
        val weak = FakeAirNetwork.Key(java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair())
        assertThrows(Exception::class.java) { parseManifest(manifest(weak, urls)) }
        assertThrows(Exception::class.java) { parseManifest(doc.replace(Regex("auth_rsa_exponent=\"[^\"]*\""), "auth_rsa_exponent=\"AQ==\"")) }
        val even = java.util.Base64.getEncoder().encodeToString(ByteArray(256) { if (it == 0) 0x80.toByte() else 0 })
        assertThrows(Exception::class.java) { parseManifest(doc.replace(Regex("auth_rsa_modulus=\"[^\"]*\""), "auth_rsa_modulus=\"$even\"")) }
        assertThrows(Exception::class.java) { parseManifest(manifest(keyA, listOf("ftp://192.0.2.1"))) }
        assertEquals(1, parseManifest(manifest(keyA, urls, nextUpdate = 0)).nextUpdateMinutes)
        assertEquals(1, parseManifest(manifest(keyA, urls, nextUpdate = -5)).nextUpdateMinutes)
        assertEquals(1440, parseManifest(manifest(keyA, urls, nextUpdate = 999_999)).nextUpdateMinutes)
        assertEquals(listOf("http://192.0.2.1"), parseManifest(manifest(keyA, listOf("http://192.0.2.1", "http://192.0.2.1"))).bootstrap.urls)
    }
}
