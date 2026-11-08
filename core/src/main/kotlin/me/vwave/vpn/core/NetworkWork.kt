// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpn.core

import kotlinx.coroutines.*

/** Close transport before interrupting its worker, preserving the reason for cancellation. */
suspend fun <T> networkWork(cancel: () -> Unit, timeout: () -> Unit = cancel, operation: () -> T): T = coroutineScope {
    // A separately owned worker lets us record the deadline before cancellation interrupts it.
    val worker = CoroutineScope(currentCoroutineContext().minusKey(Job) + SupervisorJob())
    val work = worker.async(Dispatchers.IO) { runInterruptible { operation() } }
    try { work.await() }
    catch (e: CancellationException) {
        withContext(NonCancellable + Dispatchers.IO) {
            if (e is TimeoutCancellationException) timeout() else cancel()
            work.cancelAndJoin()
        }
        throw e
    } finally { worker.cancel() }
}
