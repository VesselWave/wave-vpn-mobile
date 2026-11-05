// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpn.data

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** Application-owned admission and deletion: logout closes admission before cancelling work. */
internal class SessionWork(private val cancelTransport: () -> Unit) {
    private val monitor = Any()
    private val mutex = Mutex()
    private var active: Job? = null
    private var signingOut = false
    suspend fun update(signingIn: Boolean, work: suspend () -> Unit) {
        val job = currentCoroutineContext().job
        val admitted = synchronized(monitor) {
            if (signingOut || !mutex.tryLock()) false else { active = job; true }
        }
        if (!admitted) {
            if (signingIn) throw IOException("Another AirVPN request is busy. Retry shortly.")
            return
        }
        try { currentCoroutineContext().ensureActive(); work() }
        finally { synchronized(monitor) { active = null; mutex.unlock() } }
    }
    fun logout(scope: CoroutineScope, delete: suspend () -> Unit): Job {
        synchronized(monitor) { signingOut = true; active?.cancel(); cancelTransport() }
        return scope.launch(start = CoroutineStart.UNDISPATCHED) { withContext(NonCancellable) {
            try { mutex.withLock { withContext(NonCancellable) { delete() } } }
            finally { synchronized(monitor) { signingOut = false } }
        } }
    }
}

/** Refresh failure must not suppress the independent, locally budgeted latency check. */
internal suspend fun refreshAndMeasure(refresh: suspend () -> Unit, report: (Exception) -> Unit,
    measure: suspend () -> Unit) {
    try { refresh() }
    catch (e: CancellationException) { throw e }
    catch (e: Exception) { report(e) }
    measure()
}

/** Only a successful handshake authorizes ordinary background refresh. */
internal class HandshakeRefresh {
    var recoveryNeeded = false
        private set
    fun outcome(success: Boolean) { recoveryNeeded = !success }
    fun verifiedHandshake(refresh: () -> Unit) { recoveryNeeded = false; refresh() }
    suspend fun recover(refresh: suspend () -> Unit) {
        if (recoveryNeeded) { refresh(); recoveryNeeded = false }
    }
}
