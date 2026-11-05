// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpn.core

import java.io.IOException
import java.util.concurrent.locks.ReentrantLock

/** Stored only by the application's encrypted vault. Never log this object. */
class SessionSnapshot(val login: String, val password: String, val user: String, val manifest: String,
    val userAt: Long, val manifestAt: Long)

/** No queued refreshes or automatic retry loops. Failed/cancelled attempts consume the minimum interval. */
class RefreshSession(
    private val api: AirRequests,
    initial: SessionSnapshot?,
    private val save: (SessionSnapshot) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val fallback: Bootstrap = Defaults.bootstrap,
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val lock = ReentrantLock()
    var snapshot: SessionSnapshot? = initial
        private set
    private var nextAttempt = Long.MIN_VALUE
    private var failures = 0

    fun update(login: String? = null, password: String? = null, force: Boolean = false) {
        if (!lock.tryLock()) return
        try {
            val old = snapshot
            val signingIn = login != null
            val now = clock()
            val manifest = old?.let { parseManifest(it.manifest) }
            val userDue = old == null || now < old.userAt || now - old.userAt >= 6 * 60 * 60_000L
            val manifestDue = old == null || now < old.manifestAt ||
                now - old.manifestAt >= (manifest?.nextUpdateMinutes ?: 30) * 60_000L
            if (!signingIn && !force && !userDue && !manifestDue) return
            if (elapsed() < nextAttempt) {
                if (failures > 0 || signingIn) throw IOException("AirVPN is cooling down. Cached information is still available; retry later.")
                return
            }
            nextAttempt = elapsed() + 60_000
            try {
                val name = login ?: old?.login ?: return
                val secret = password ?: old?.password ?: return
                val boot = manifest?.bootstrap ?: fallback
                // Authentication is reused during ordinary server refreshes.
                val userXml = if (signingIn || userDue) api.request(boot, "user", name, secret).also {
                    parseUser(it, name)
                } else old.user
                val manifestXml = if (signingIn || force || manifestDue) api.request(boot, "manifest", name, secret).also {
                    parseManifest(it)
                } else old.manifest
                val updated = SessionSnapshot(name, secret, userXml, manifestXml,
                    if (signingIn || userDue) now else old.userAt,
                    if (signingIn || force || manifestDue) now else old.manifestAt)
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                save(updated)
                snapshot = updated
                failures = 0
            } catch (e: java.util.concurrent.CancellationException) { throw e }
            catch (e: InterruptedException) { throw e }
            catch (e: Exception) {
                failures = (failures + 1).coerceAtMost(5)
                nextAttempt = elapsed() + (30_000L shl failures).coerceAtMost(15 * 60_000L)
                throw e
            }
        } finally { lock.unlock() }
    }
}
