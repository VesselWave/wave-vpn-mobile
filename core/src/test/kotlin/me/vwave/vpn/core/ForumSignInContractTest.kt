// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpn.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * D-09 (shared with Wave VPN for Linux): one overall sign-in deadline and a user Cancel.
 * Deadline: an ordinary failure with a clear message; the stalled endpoint backs off.
 * Cancel: not a failure, no endpoint penalty, nothing saved.
 */
class ForumSignInContractTest {
    private val urls = listOf("https://stalled.example/", "https://healthy.example/")

    /** The first POST stalls until interrupted; later ones reach the fake network. */
    private class Stalling(urls: List<String>) {
        val network = FakeAirNetwork().apply {
            for (url in urls) route(url, FakeAirNetwork.keyA) { p -> if (p["act"] == "user") "<user/>" else "<manifest/>" }
        }
        val posts: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val entered = CountDownLatch(1)
        private val never = CountDownLatch(1)
        val api = AirApi(transport = Transport { url, body ->
            posts += url
            if (posts.size == 1) { entered.countDown(); never.await(); error("unreachable") }
            network.post(url, body)
        }, clock = { 0L }, maxAttempts = 1)
    }

    @Test fun `deadline and message are shared with the Linux app`() {
        assertEquals(45_000L, SIGN_IN_DEADLINE_MS)
        assertEquals("Sign-in timed out after 45 seconds. Check your network and retry shortly.", SIGN_IN_TIMED_OUT)
    }

    @Test(timeout = 8000) fun `sign-in deadline settles as an ordinary failure and backs off the stalled endpoint`() = runBlocking {
        val s = Stalling(urls)
        val failure = runCatching {
            withSignInDeadline(200) {
                networkWork(s.api::cancel, s.api::timeout) {
                    s.api.request(FakeAirNetwork.keyA.bootstrap(urls), "user", "tester", "synthetic")
                }
            }
        }.exceptionOrNull()
        assertTrue("an ordinary failure, not a cancellation: $failure", failure is SignInTimeoutException)
        assertFalse(failure is CancellationException)
        assertEquals(SIGN_IN_TIMED_OUT, failure!!.message)
        assertTrue("the caller stays active and can show the error", isActive)
        assertEquals("<user/>", s.api.request(FakeAirNetwork.keyA.bootstrap(urls), "user", "tester", "synthetic"))
        assertEquals("the next attempt moves away from the stalled endpoint", urls, s.posts.toList())
    }

    @Test(timeout = 8000) fun `user cancel settles without failure or endpoint penalty`() = runBlocking {
        val s = Stalling(urls)
        var failure: Throwable? = null
        val job = launch(Dispatchers.Default) {
            try {
                withSignInDeadline {
                    networkWork(s.api::cancel, s.api::timeout) {
                        s.api.request(FakeAirNetwork.keyA.bootstrap(urls), "user", "tester", "synthetic")
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure = e }
        }
        assertTrue(withContext(Dispatchers.IO) { s.entered.await(3, TimeUnit.SECONDS) })
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertNull("Cancel is not reported as a failure", failure)
        assertEquals("<user/>", s.api.request(FakeAirNetwork.keyA.bootstrap(urls), "user", "tester", "synthetic"))
        assertEquals("a cancelled endpoint is not penalized", listOf(urls[0], urls[0]), s.posts.toList())
    }

    @Test(timeout = 8000) fun `a cancelled or timed-out sign-in saves no session or credentials`() = runBlocking {
        for (cancel in listOf(true, false)) {
            val s = Stalling(urls)
            var saved = 0
            val session = RefreshSession(s.api, null, { saved++ }, fallback = FakeAirNetwork.keyA.bootstrap(urls))
            val job = launch(Dispatchers.Default) {
                try {
                    withSignInDeadline(if (cancel) SIGN_IN_DEADLINE_MS else 200) {
                        networkWork(s.api::cancel, s.api::timeout) { session.update("tester", "synthetic") }
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: SignInTimeoutException) {}
            }
            assertTrue(withContext(Dispatchers.IO) { s.entered.await(3, TimeUnit.SECONDS) })
            if (cancel) job.cancelAndJoin() else job.join()
            assertEquals("cancel=$cancel", 0, saved)
            assertNull("cancel=$cancel", session.snapshot)
        }
    }
}
