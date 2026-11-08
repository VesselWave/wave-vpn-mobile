// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpn.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import me.vwave.vpn.core.*
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A single atomic encrypted snapshot, excluded from Android cloud backup. */
class SessionVault(context: Context) {
    private val budget = context.getSharedPreferences("wave-network-budget", Context.MODE_PRIVATE)
    fun readBudget() = budget.getLong("retryAt", 0) to budget.getInt("failures", 0)
    fun writeBudget(at: Long, failures: Int) {
        check(budget.edit().putLong("retryAt", at).putInt("failures", failures).commit()) { "Unable to save request budget" }
    }
    private val file = AtomicFile(File(context.noBackupFilesDir, "wave-session-v2"))
    // Public routing/key material survives logout; credentials remain solely in the encrypted snapshot.
    private val publicManifest = AtomicFile(File(context.noBackupFilesDir, "wave-bootstrap-v1"))
    fun readBootstrap(): Bootstrap = runCatching {
        parseManifest(publicManifest.readFully().toString(Charsets.UTF_8)).bootstrap
    }.getOrDefault(Defaults.bootstrap)
    fun writeBootstrap(manifest: String) {
        parseManifest(manifest)
        val stream = publicManifest.startWrite()
        try { stream.write(manifest.toByteArray(Charsets.UTF_8)); publicManifest.finishWrite(stream) }
        catch (e: Exception) { publicManifest.failWrite(stream); throw e }
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("wave-session-v2", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("wave-session-v2", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    fun read(): JSONObject? {
        if (!file.baseFile.exists()) return null
        return runCatching {
            val bytes = file.readFully()
            require(bytes.size >= 28)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            val plain = c.doFinal(bytes, 12, bytes.size - 12)
            try { JSONObject(plain.toString(Charsets.UTF_8)) } finally { plain.fill(0) }
        }.getOrNull()
    }
    fun write(snapshot: JSONObject) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val plain = snapshot.toString().toByteArray(Charsets.UTF_8)
        val sealed = try { c.iv + c.doFinal(plain) } finally { plain.fill(0) }
        val stream = file.startWrite()
        try { stream.write(sealed); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
    fun clear() = file.delete()
}

class AirRepository(private val vault: SessionVault, context: Context) {
    @Volatile private var recoveryTransport = false
    private val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
    private val transport = HttpTransport { url ->
        if (!recoveryTransport) url.openConnection() as java.net.HttpURLConnection
        else {
            // Only this bootstrap request may use the physical network; no app exclusion,
            // process binding, or general socket protection. Lockdown may refuse it.
            val active = connectivity.activeNetwork
            val candidates = connectivity.allNetworks.mapNotNull { network ->
                connectivity.getNetworkCapabilities(network)?.let { caps ->
                    RecoveryNetwork(network,
                        internet = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET),
                        nonVpn = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
                        validated = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                        active = network == active,
                        wifi = caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI))
                }
            }
            val network = recoveryNetwork(candidates)
                ?: throw java.io.IOException("No validated underlying network for account recovery")
            network.openConnection(url) as java.net.HttpURLConnection
        }
    }
    private val api = AirApi(arch = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown", transport = transport)
    private val work = SessionWork(api::cancel)
    private val a = MutableStateFlow<Account?>(null)
    private val m = MutableStateFlow<Manifest?>(null)
    val account = a.asStateFlow()
    val manifest = m.asStateFlow()
    val isLoggedIn get() = a.value != null
    private fun restore(): SessionSnapshot? = vault.read()?.let { s -> runCatching {
        val user = parseUser(s.getString("user"), s.getString("login"))
        val manifest = parseManifest(s.getString("manifest"))
        a.value = user; m.value = manifest
        SessionSnapshot(s.getString("login"), s.getString("password"), s.getString("user"),
            s.getString("manifest"), s.optLong("userAt", s.optLong("fetchedAt")),
            s.optLong("manifestAt", s.optLong("fetchedAt")))
    }.getOrElse { vault.clear(); null } }
    private fun engine(initial: SessionSnapshot?) = RefreshSession(api, initial, { s ->
        vault.writeBootstrap(s.manifest)
        vault.write(JSONObject().put("login", s.login).put("password", s.password).put("user", s.user)
            .put("manifest", s.manifest).put("userAt", s.userAt).put("manifestAt", s.manifestAt))
    }, fallback = vault.readBootstrap(), recovery = Defaults.bootstrap,
        initialBudget = vault.readBudget(), saveBudget = vault::writeBudget)
    private var session = engine(restore())
    private suspend fun update(login: String? = null, password: String? = null, force: Boolean = false, account: Boolean = false) {
        work.update(login != null || account) {
            try {
                recoveryTransport = account
                networkWork(api::cancel, api::timeout) { session.update(login, password, force, account, reportCooldown = true) }
            } finally {
                recoveryTransport = false
                session.snapshot?.let { s -> a.value = parseUser(s.user, s.login); m.value = parseManifest(s.manifest) }
            }
        }
    }
    suspend fun login(login: String, password: String) = update(login, password)
    suspend fun refresh(force: Boolean = false) = update(force = force)
    suspend fun refreshAccount() = update(account = true)
    fun logout(scope: CoroutineScope, after: () -> Unit = {}): Job = work.logout(scope) {
        withContext(NonCancellable + Dispatchers.IO) {
            session.clear(); vault.clear(); a.value = null; m.value = null
        }
        after()
    }
}
