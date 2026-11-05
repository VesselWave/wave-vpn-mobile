// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpn.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RefreshSessionTest {
    private val xml = File("../protocol-data/fixtures/eddie-manifest.xml").readText()
    private val user = """<user login="tester"><keys><key name="phone"/></keys></user>"""
    private var now = 1_000_000L
    private var elapsed = 0L
    private val calls = mutableListOf<String>()
    private var saved: SessionSnapshot? = null
    private var response: (String) -> String = { if (it == "user") user else xml }
    private var bootstrap: Bootstrap? = null
    private fun engine(initial: SessionSnapshot? = null) = RefreshSession(AirRequests { b, action, _, _ ->
        calls += action; bootstrap = b; response(action)
    }, initial, { saved = it }, clock = { now }, elapsed = { elapsed })
    private fun advance(ms: Long) { now += ms; elapsed += ms }

    @Test fun `first login uses embedded fallback and atomically persists validated documents`() {
        val session = engine()
        session.update("tester", "secret")
        assertEquals(listOf("user", "manifest"), calls)
        assertEquals(Defaults.bootstrap, bootstrap)
        assertSame(saved, session.snapshot)
        repeat(50) { session.update(force = true) }
        assertEquals(2, calls.size)
        assertFalse(session.snapshot.toString().contains("secret"))
    }

    @Test fun `restored latest bootstrap used without embedded fallback and refresh reuses authentication`() {
        val changed = xml.replace("http://63.33.78.166", "https://new.example")
        val initial = SessionSnapshot("tester", "secret", user, changed, now, now)
        val session = engine(initial)
        session.update()
        assertTrue(calls.isEmpty())
        advance(60_000)
        session.update(force = true)
        assertEquals(listOf("manifest"), calls)
        assertEquals("https://new.example", bootstrap!!.urls.first())
        assertEquals(initial.userAt, saved!!.userAt)
        advance(6 * 60 * 60_000L)
        session.update()
        assertEquals(listOf("manifest", "user", "manifest"), calls)
    }

    @Test fun `advertised manifest interval respected but force cannot bypass minimum interval`() {
        val session = engine()
        session.update("tester", "secret")
        advance(60_000)
        session.update()
        assertEquals(2, calls.size)
        session.update(force = true)
        assertEquals(3, calls.size)
        repeat(100) { session.update(force = true) }
        assertEquals(3, calls.size)
        advance(30 * 60_000)
        session.update()
        assertEquals(4, calls.size)
    }

    @Test fun `failures preserve usable snapshot and enforce increasing cooldown`() {
        val old = SessionSnapshot("tester", "secret", user, xml, now, now)
        val session = engine(old)
        response = { throw IOException("offline") }
        assertThrows(IOException::class.java) { session.update(force = true) }
        repeat(20) { assertThrows(IOException::class.java) { session.update(force = true) } }
        assertEquals(1, calls.size)
        advance(60_000)
        assertThrows(IOException::class.java) { session.update(force = true) }
        advance(60_000)
        assertThrows(IOException::class.java) { session.update(force = true) }
        assertEquals(2, calls.size)
        assertSame(old, session.snapshot)
        assertNull(saved)
        advance(60_000)
        response = { xml }
        session.update(force = true)
        assertEquals(3, calls.size)
    }

    @Test fun `invalid manifest or storage failure never replaces snapshot`() {
        val old = SessionSnapshot("tester", "secret", user, xml, now, now)
        val session = engine(old)
        response = { xml.replace("auth_rsa_exponent=\"AQAB\"", "auth_rsa_exponent=\"!\"") }
        assertThrows(Exception::class.java) { session.update(force = true) }
        assertSame(old, session.snapshot)
        assertNull(saved)
        val brokenDisk = RefreshSession(AirRequests { _, action, _, _ -> if (action == "user") user else xml }, old, { throw IOException("disk") })
        assertThrows(IOException::class.java) { brokenDisk.update(force = true) }
        assertSame(old, brokenDisk.snapshot)
    }

    @Test(timeout = 15000) fun `cancelled attempt cannot be hammered immediately`() {
        val session = engine()
        response = { throw CancellationException() }
        assertThrows(CancellationException::class.java) { session.update("tester", "secret") }
        repeat(30) { assertThrows(IOException::class.java) { session.update("tester", "secret") } }
        assertEquals(1, calls.size)
        assertNull(saved)
        advance(60_000)
        response = { if (it == "user") user else xml }
        session.update("tester", "secret")
        assertNotNull(saved)
    }

    @Test fun `concurrent forced refreshes are dropped instead of queued`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val old = SessionSnapshot("tester", "secret", user, xml, now, now)
        val session = engine(old)
        response = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); xml }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val first = executor.submit { session.update(force = true) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            repeat(50) { session.update(force = true) }
            release.countDown()
            first.get(5, TimeUnit.SECONDS)
            assertEquals(1, calls.size)
        } finally { release.countDown(); executor.shutdownNow() }
    }


    @Test fun `latest public bootstrap survives sign out and key rotation`() {
        val rotated = xml.replace("auth_rsa_exponent=\"AQAB\"", "auth_rsa_exponent=\"Aw==\"")
            .replace("http://63.33.78.166", "https://rotated.example")
        val latest = parseManifest(rotated).bootstrap
        val session = RefreshSession(AirRequests { b, action, _, _ ->
            assertEquals(latest, b)
            if (action == "user") user else rotated
        }, null, { saved = it }, fallback = latest)
        session.update("tester", "secret")
        assertEquals(latest, parseManifest(saved!!.manifest).bootstrap)
    }


    @Test fun `new manifest routing and key used on next request and after restore`() {
        val rotated = xml.replace("auth_rsa_exponent=\"AQAB\"", "auth_rsa_exponent=\"Aw==\"")
            .replace("http://63.33.78.166", "https://rotated.example")
        val session = engine(SessionSnapshot("tester", "secret", user, xml, now, now))
        response = { rotated }
        session.update(force = true)
        advance(60_000)
        session.update(force = true)
        assertEquals(parseManifest(rotated).bootstrap, bootstrap)
        val restored = engine(saved)
        advance(30 * 60_000)
        restored.update()
        assertEquals(parseManifest(rotated).bootstrap, bootstrap)
    }

    @Test fun `future persisted timestamp does not suppress refresh forever`() {
        val session = engine(SessionSnapshot("tester", "secret", user, xml, now + 1_000_000, now + 1_000_000))
        session.update()
        assertEquals(listOf("user", "manifest"), calls)
    }

    @Test fun `invalid manifest URLs rejected before persistence`() {
        for (url in listOf("file:///etc/passwd", "http://user:password@example.com", "garbage")) {
            assertThrows(Exception::class.java) { parseManifest(xml.replace("http://63.33.78.166", url)) }
        }
    }
}
