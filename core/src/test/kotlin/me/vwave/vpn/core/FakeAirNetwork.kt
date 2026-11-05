// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpn.core

import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.net.URLDecoder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Offline stand-in for AirVPN bootstrap servers. Each simulated server owns an RSA key and
 * decrypts requests independently of the production envelope code. Nothing here opens a socket.
 */
class FakeAirNetwork(private val clock: (() -> Long)? = null) : Transport {
    class Key(val pair: KeyPair) {
        private val pub = pair.public as RSAPublicKey
        private fun unsigned(n: BigInteger) = n.toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        val modulus: String = Base64.getEncoder().encodeToString(unsigned(pub.modulus))
        val exponent: String = Base64.getEncoder().encodeToString(unsigned(pub.publicExponent))
        fun bootstrap(urls: List<String>) = Bootstrap(urls, modulus, exponent)
    }

    data class Post(val url: String, val act: String?, val login: String?, val at: Long?)

    /** Return the XML to send, or throw to simulate a transport failure. */
    fun interface Handler { fun handle(params: Map<String, String>): String }

    private val routes = mutableMapOf<String, Pair<Key, Handler>>()
    val posts = mutableListOf<Post>()
    var onPost: (String) -> Unit = {}

    fun route(url: String, key: Key, handler: Handler) { routes[url] = key to handler }
    fun down(url: String) { routes.remove(url) }
    fun count(act: String) = posts.count { it.act == act }

    @Synchronized override fun post(url: String, body: String): ByteArray {
        onPost(url)
        val route = routes[url]
        if (route == null) {
            posts += Post(url, null, null, clock?.invoke())
            throw IOException("connect timed out (simulated)")
        }
        val (key, handler) = route
        val decoder = Base64.getDecoder()
        val form = body.split('&').associate { part -> part.split('=', limit = 2).let {
            it[0] to decoder.decode(URLDecoder.decode(it[1], "UTF-8"))
        } }
        val session = try {
            Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.DECRYPT_MODE, key.pair.private) }
                .doFinal(form.getValue("s"))
        } catch (e: Exception) {
            // Encrypted for some other key: a real server cannot answer usefully.
            posts += Post(url, "undecryptable", null, clock?.invoke())
            throw IOException("HTTP 500 (simulated wrong key)")
        }
        val pairs = association(session)
        val aesKey = SecretKeySpec(pairs.getValue("key"), "AES")
        val iv = IvParameterSpec(pairs.getValue("iv"))
        val plain = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.DECRYPT_MODE, aesKey, iv) }
            .doFinal(form.getValue("d"))
        val params = association(plain).mapValues { it.value.toString(Charsets.UTF_8) }
        posts += Post(url, params["act"], params["login"], clock?.invoke())
        val xml = handler.handle(params)
        return Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.ENCRYPT_MODE, aesKey, iv) }
            .doFinal(xml.toByteArray())
    }

    private fun association(bytes: ByteArray): Map<String, ByteArray> = bytes.toString(Charsets.UTF_8)
        .lineSequence().filter { it.isNotEmpty() }.associate { line -> line.split(':', limit = 2).let {
            Base64.getDecoder().decode(it[0]).toString(Charsets.UTF_8) to Base64.getDecoder().decode(it[1])
        } }

    /** Shared virtual wall and monotonic clock. */
    class Time {
        var ms = 0L
        val wall get() = 1_800_000_000_000L + ms
        fun advance(delta: Long) { ms += delta }
    }

    companion object {
        val keyA by lazy { Key(KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()) }
        val keyB by lazy { Key(KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()) }
        val fixture: String by lazy { File("../protocol-data/fixtures/eddie-manifest.xml").readText() }

        /** The recorded Eddie manifest, re-pointed at simulated bootstrap URLs and key. */
        fun manifest(key: Key, urls: List<String>, nextUpdate: Int = 30): String = fixture
            .replace(Regex("auth_rsa_modulus=\"[^\"]*\""), "auth_rsa_modulus=\"${key.modulus}\"")
            .replace(Regex("auth_rsa_exponent=\"[^\"]*\""), "auth_rsa_exponent=\"${key.exponent}\"")
            .replace(Regex("next_update=\"[^\"]*\""), "next_update=\"$nextUpdate\"")
            .replace(Regex("<urls>.*?</urls>", RegexOption.DOT_MATCHES_ALL),
                "<urls>" + urls.joinToString("") { "<url address=\"$it\"/>" } + "</urls>")

        fun user(login: String = "tester", vararg devices: String = arrayOf("phone")) =
            "<user login=\"$login\">" + devices.joinToString("", "<keys>", "</keys>") { "<key name=\"$it\"/>" } + "</user>"

        /** Fixture servers have blank entry IPs; give each a distinct documentation address. */
        fun fixtureServers(): List<Server> = parseManifest(fixture).servers.mapIndexed { i, s ->
            s.copy(entries = listOf(if (i < 250) "198.51.100.${i + 1}" else "203.0.113.${i - 249}"))
        }
    }
}
