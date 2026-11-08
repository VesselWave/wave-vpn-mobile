// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpn.data

import kotlinx.coroutines.*
import me.vwave.vpn.core.CooldownException
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Exercises the production recovery policy used before TunnelManager starts WireGuard. */
class ForumRecoveryContractTest {

    @Test(timeout = 5000) fun `recovery outage must not prevent cached tunnel attempt`() = runBlocking {
        val policy = HandshakeRefresh().apply { outcome(false) }
        policy.recover { throw IOException("bootstrap unavailable") }
        // Reaching here is the precondition for TunnelManager's cached profile path.
        assertTrue(currentCoroutineContext().isActive)
    }

    @Test(timeout = 5000) fun `local recovery deadline must not cancel cached tunnel attempt`() = runBlocking {
        val policy = HandshakeRefresh().apply { outcome(false) }
        policy.recover { withTimeout(25) { awaitCancellation() } }
        assertTrue("the caller still owns a live connection attempt", currentCoroutineContext().isActive)
    }

    @Test(timeout = 5000) fun `session cooldown must not gate cached reconnect`() = runBlocking {
        val policy = HandshakeRefresh().apply { outcome(false) }
        repeat(3) { policy.recover { throw CooldownException(59) } }
        assertTrue(currentCoroutineContext().isActive)
    }

    @Test(timeout = 5000) fun `real user cancellation still cancels recovery`() = runBlocking {
        val policy = HandshakeRefresh().apply { outcome(false) }
        val entered = CompletableDeferred<Unit>()
        var reachedCachedAttempt = false
        val job = launch {
            policy.recover { entered.complete(Unit); awaitCancellation() }
            reachedCachedAttempt = true
        }
        entered.await(); job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertFalse(reachedCachedAttempt)
    }

    @Test(timeout = 5000) fun `successful handshake suppresses unnecessary recovery`() = runBlocking {
        val policy = HandshakeRefresh().apply { outcome(false) }
        var refreshed = 0
        policy.verifiedHandshake { refreshed++ }
        policy.recover { fail("healthy cached connection should not force account refresh") }
        assertEquals(1, refreshed)
    }
}
