// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpn.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val file = AtomicFile(File(context.noBackupFilesDir, "wave-session-v2"))
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

class AirRepository(private val vault: SessionVault) {
    private val mutex = Mutex()
    private val api = AirApi(arch = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
    private var snapshot = vault.read()
    private val a = MutableStateFlow<Account?>(null)
    private val m = MutableStateFlow<Manifest?>(null)
    val account = a.asStateFlow()
    val manifest = m.asStateFlow()
    val isLoggedIn get() = a.value != null
    init {
        snapshot?.let { s -> runCatching {
            val user = parseUser(s.getString("user"), s.getString("login"))
            val manifest = parseManifest(s.getString("manifest"))
            a.value = user; m.value = manifest
        }.onFailure { snapshot = null; vault.clear() } }
    }
    suspend fun login(login: String, password: String) = mutex.withLock {
        withContext(Dispatchers.IO) { fetch(login, password) }
    }
    suspend fun refresh(force: Boolean = false) = mutex.withLock {
        val s = snapshot ?: return@withLock
        val interval = (m.value?.nextUpdateMinutes ?: 30) * 60_000L
        if (!force && System.currentTimeMillis() - s.optLong("fetchedAt") < interval) return@withLock
        withContext(Dispatchers.IO) { fetch(s.getString("login"), s.getString("password")) }
    }
    private fun fetch(login: String, password: String) {
        val boot = m.value?.bootstrap ?: Defaults.bootstrap
        val userXml = api.request(boot, "user", login, password)
        val user = parseUser(userXml, login)
        val manifestXml = api.request(boot, "manifest", login, password)
        val manifest = parseManifest(manifestXml)
        val s = JSONObject().put("login", login).put("password", password).put("user", userXml)
            .put("manifest", manifestXml).put("fetchedAt", System.currentTimeMillis())
        vault.write(s)
        snapshot = s; a.value = user; m.value = manifest
    }
    suspend fun logout() = mutex.withLock { snapshot = null; vault.clear(); a.value = null; m.value = null }
}
