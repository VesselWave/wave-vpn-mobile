// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpn.core

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import java.io.IOException

/** One overall deadline for a whole sign-in (every endpoint attempt), shared with Wave VPN for Linux. */
const val SIGN_IN_DEADLINE_MS = 45_000L
const val SIGN_IN_TIMED_OUT = "Sign-in timed out after 45 seconds. Check your network and retry shortly."

class SignInTimeoutException : IOException(SIGN_IN_TIMED_OUT)

/**
 * Its expiry is an ordinary failure ([SignInTimeoutException]); [networkWork] records it as
 * endpoint evidence. A caller's cancellation (Cancel, sign-out) stays a cancellation and
 * penalizes nothing.
 */
suspend fun <T> withSignInDeadline(deadlineMs: Long = SIGN_IN_DEADLINE_MS, block: suspend () -> T): T =
    try { withTimeout(deadlineMs) { block() } }
    catch (e: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw SignInTimeoutException().apply { initCause(e) }
    }
