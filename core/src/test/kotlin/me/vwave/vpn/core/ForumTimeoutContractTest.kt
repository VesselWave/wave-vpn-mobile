// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpn.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ForumTimeoutContractTest {

    @Test(timeout = 8000) fun `caller deadline penalizes blackholed URL before next request`() = runBlocking {
        val urls = listOf("https://blackhole.example/", "https://healthy.example/")
        val network = FakeAirNetwork()
        for (url in urls) network.route(url, FakeAirNetwork.keyA) { "<user/>" }
        val posts = Collections.synchronizedList(mutableListOf<String>())
        val entered = CountDownLatch(1)
        val never = CountDownLatch(1)
        val api = AirApi(transport = Transport { url, body ->
            posts += url
            if (posts.size == 1) { entered.countDown(); never.await(); error("unreachable") }
            network.post(url, body)
        }, clock = { 0L }, maxAttempts = 1)
        // Exercise the same local deadline boundary as the production repository.
        val request = async(Dispatchers.Default) {
            assertThrows(TimeoutCancellationException::class.java) {
                runBlocking {
                    withTimeout(1000) {
                        networkWork(api::cancel, api::timeout) {
                            api.request(FakeAirNetwork.keyA.bootstrap(urls), "user", "tester", "synthetic")
                        }
                    }
                }
            }
        }
        assertTrue(withContext(Dispatchers.IO) { entered.await(3, TimeUnit.SECONDS) })
        request.await()
        assertEquals("<user/>", api.request(FakeAirNetwork.keyA.bootstrap(urls), "user", "tester", "synthetic"))
        assertEquals("a deadline must move the next request away from the blackholed URL", urls, posts.toList())
    }

    @Test fun `socket timeout already fails over within request as positive control`() {
        val urls = listOf("https://blackhole.example/", "https://healthy.example/")
        val network = FakeAirNetwork()
        network.route(urls[1], FakeAirNetwork.keyA) { "<user/>" }
        val seen = mutableListOf<String>()
        val api = AirApi(transport = Transport { url, body ->
            seen += url
            if (url == urls[0]) throw SocketTimeoutException("read timed out")
            network.post(url, body)
        }, clock = { 0L })
        assertEquals("<user/>", api.request(FakeAirNetwork.keyA.bootstrap(urls), "user", "tester", "synthetic"))
        assertEquals(urls, seen)
    }

    @Test fun `explicit cancellation never launches another endpoint in same request`() {
        val seen = mutableListOf<String>()
        val urls = listOf("https://one.example/", "https://two.example/")
        val api = AirApi(transport = Transport { url, _ ->
            seen += url; throw java.util.concurrent.CancellationException("logout")
        })
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            api.request(FakeAirNetwork.keyA.bootstrap(urls), "user", "tester", "synthetic")
        }
        assertEquals(listOf(urls[0]), seen)
    }
}
