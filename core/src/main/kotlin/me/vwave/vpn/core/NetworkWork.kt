// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpn.core

import kotlinx.coroutines.*

/** Interrupt the worker and close its transport on cancellation before awaiting cleanup. */
suspend fun <T> networkWork(cancel: () -> Unit, operation: () -> T): T = coroutineScope {
    val work = async(Dispatchers.IO) { runInterruptible { operation() } }
    try { work.await() }
    finally {
        if (!currentCoroutineContext().isActive) withContext(NonCancellable + Dispatchers.IO) { cancel() }
    }
}
