// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpn.core

import java.io.IOException
import java.util.concurrent.locks.ReentrantLock

/** Stored only by the application's encrypted vault. Never log this object. */
class SessionSnapshot(val login: String, val password: String, val user: String, val manifest: String,
    val userAt: Long, val manifestAt: Long)

/** No queued refreshes or automatic retry loops. Failed/cancelled attempts consume the minimum interval. */
class CooldownException(val seconds: Long) : IOException("Wait $seconds seconds before retrying.")

class RefreshSession(
    private val api: AirRequests,
    initial: SessionSnapshot?,
    private val save: (SessionSnapshot) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val fallback: Bootstrap = Defaults.bootstrap,
    private val recovery: Bootstrap = fallback,
    initialBudget: Pair<Long, Int> = 0L to 0,
    private val saveBudget: (Long, Int) -> Unit = { _, _ -> },
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val lock = ReentrantLock()
    private var routing = initial?.let { parseManifest(it.manifest).bootstrap } ?: fallback
    var snapshot: SessionSnapshot? = initial
        private set
    private var nextAttempt = elapsed() + (initialBudget.first - clock()).coerceIn(0, 15 * 60_000)
    private fun cooldown(ms: Long) {
        nextAttempt = elapsed() + ms
        saveBudget(clock() + ms, failures)
    }
    /** Retain this engine on logout so its infrastructure/authentication budget cannot be reset. */
    fun clear() { lock.lock(); try { snapshot = null } finally { lock.unlock() } }
    private var rejections = 0
    private var failures = initialBudget.second.coerceIn(0, 5)

    fun update(login: String? = null, password: String? = null, force: Boolean = false, account: Boolean = false, reportCooldown: Boolean = false) {
        if (!lock.tryLock()) { if (login != null) throw IOException("Another AirVPN request is busy. Retry shortly."); return }
        try {
            val generation = api.cancellationVersion
            fun checkCancelled() {
                if (Thread.currentThread().isInterrupted || generation != api.cancellationVersion) throw InterruptedException()
            }
            val old = snapshot
            val signingIn = login != null
            if (!signingIn && old == null) return
            val now = clock()
            val manifest = old?.let { parseManifest(it.manifest) }
            val userDue = account || old == null || now < old.userAt || now - old.userAt >= (if (force) 15 * 60_000L else 6 * 60 * 60_000L)
            val manifestDue = old == null || now < old.manifestAt ||
                now - old.manifestAt >= (manifest?.nextUpdateMinutes ?: 30) * 60_000L
            if (!signingIn && !force && !userDue && !manifestDue) return
            if (elapsed() < nextAttempt) {
                if (failures > 0 || signingIn || reportCooldown) throw CooldownException((nextAttempt - elapsed() + 999) / 1000)
                return
            }
            cooldown(60_000)
            try {
                val name = login ?: old?.login ?: return
                val secret = password ?: old?.password ?: return
                val boot = manifest?.bootstrap ?: routing
                // Authentication is reused during ordinary server refreshes.
                val userXml = if (signingIn || userDue) api.requestWithFallback(boot, recovery, "user", name, secret).also {
                    parseUser(it, name)
                } else old.user
                // Persist successful account refresh independently of the manifest request.
                if (!signingIn && old != null && userDue) {
                    val partial = SessionSnapshot(name, secret, userXml, old.manifest, now, old.manifestAt)
                    checkCancelled()
                    save(partial); snapshot = partial
                }
                checkCancelled()
                val manifestXml = if (signingIn || force || manifestDue) manifestWithSafeBootstrap(api.requestWithFallback(boot, recovery, "manifest", name, secret), boot).also {
                    parseManifest(it)
                } else old.manifest
                val updated = SessionSnapshot(name, secret, userXml, manifestXml,
                    if (signingIn || userDue) now else old.userAt,
                    if (signingIn || force || manifestDue) now else old.manifestAt)
                checkCancelled()
                save(updated)
                snapshot = updated
                routing = parseManifest(updated.manifest).bootstrap
                failures = 0
                rejections = 0
                saveBudget(clock() + (nextAttempt - elapsed()).coerceAtLeast(0), failures)
            } catch (e: java.util.concurrent.CancellationException) { throw e }
            catch (e: InterruptedException) { throw e }
            catch (e: LoginException) {
                checkCancelled()
                failures = 0
                rejections++
                val wait = if (rejections <= 3) 5L else 60L
                cooldown(wait * 1000)
                throw LoginException("${e.message} Wait $wait seconds before retrying.")
            }
            catch (e: Exception) {
                checkCancelled()
                if ((e is java.io.InterruptedIOException && e !is java.net.SocketTimeoutException)) throw InterruptedException()
                failures = (failures + 1).coerceAtMost(5)
                cooldown((30_000L shl failures).coerceAtMost(15 * 60_000L))
                throw e
            }
        } finally { lock.unlock() }
    }
}
