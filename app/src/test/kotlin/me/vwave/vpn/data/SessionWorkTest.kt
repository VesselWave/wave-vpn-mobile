// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpn.data

import kotlinx.coroutines.*
import me.vwave.vpn.core.CooldownException
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SessionWorkTest {
    @Test(timeout = 5000) fun `logout cancels admitted work blocks new login and deletes after UI dies`() = runBlocking {
        val application = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ui = CoroutineScope(Job() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val deleting = CompletableDeferred<Unit>()
        var deleted = false
        var transportCancelled = false
        val work = SessionWork { transportCancelled = true }
        val request = ui.launch {
            work.update(false) {
                entered.complete(Unit)
                try { awaitCancellation() } finally { withContext(NonCancellable) { release.await() } }
            }
        }
        entered.await()
        val logout = work.logout(application) { deleting.complete(Unit); deleted = true }
        ui.cancel()
        assertTrue(transportCancelled)
        try { work.update(true) { fail("login admitted during deletion") }; fail("busy expected") }
        catch (_: IOException) { }
        assertFalse(deleted)
        release.complete(Unit)
        withTimeout(2000) { request.join(); logout.join() }
        assertTrue(deleted)
        assertTrue(deleting.isCompleted)
        application.cancel()
    }

    @Test(timeout = 5000) fun `deletion survives cancellation of owning job after synchronous admission closes`() = runBlocking {
        val owner = CoroutineScope(Job() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var deleted = false
        val work = SessionWork { }
        val job = work.logout(owner) { entered.complete(Unit); release.await(); deleted = true }
        entered.await(); owner.cancel(); release.complete(Unit)
        withTimeout(2000) { job.join() }
        assertTrue(deleted)
    }

    @Test(timeout = 5000) fun `cooldown notice retains wait and independent probe still runs`() = runBlocking {
        var notice: String? = null; var measured = false
        refreshAndMeasure({ throw CooldownException(37) }, { notice = it.message }, { measured = true })
        assertTrue(notice!!.contains("37")); assertTrue(measured)
    }

    @Test(timeout = 5000) fun `cancelled refresh does not launch probe`() = runBlocking {
        try {
            refreshAndMeasure({ throw CancellationException() }, { fail("cancel reported as outage") }, { fail("probe after cancel") })
            fail("cancel expected")
        } catch (_: CancellationException) { }
    }

    @Test(timeout = 5000) fun `failed handshake requires successful recovery before next tunnel attempt`() = runBlocking {
        val policy = HandshakeRefresh()
        var calls = 0
        policy.recover { calls++ }; assertEquals(0, calls)
        policy.outcome(false)
        try { policy.recover { calls++; throw IOException() }; fail() } catch (_: IOException) { }
        assertTrue(policy.recoveryNeeded)
        policy.recover { calls++ }
        assertFalse(policy.recoveryNeeded); assertEquals(2, calls)
        var background = 0
        policy.verifiedHandshake { background++ }
        assertEquals(1, background)
        policy.recover { fail("working tunnel needs no forced recovery") }
    }
}
