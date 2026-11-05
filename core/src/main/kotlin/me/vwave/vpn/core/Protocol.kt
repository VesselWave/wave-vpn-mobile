// SPDX-License-Identifier: GPL-3.0-or-later
// New implementation of the protocol documented by Eddie's Service.cs.
// Eddie copyright (C) 2014-2026 AirVPN; see docs/PROVENANCE.md.
package me.vwave.vpn.core

import java.io.IOException
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class Bootstrap(val urls: List<String>, val modulus: String, val exponent: String)
class LoginException(message: String) : IOException(message)
class ProtocolException(message: String) : IOException(message)

internal fun association(values: Map<String, ByteArray>): ByteArray = values.entries.joinToString("") {
    Base64.getEncoder().encodeToString(it.key.toByteArray(Charsets.UTF_8)) + ":" +
        Base64.getEncoder().encodeToString(it.value) + "\n"
}.toByteArray(Charsets.UTF_8)

/** One fresh key and IV per request. CBC has no independent MAC: do not describe it as AEAD. */
class RequestEnvelope(bootstrap: Bootstrap) : AutoCloseable {
    private val sessionKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
    private val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(
        BigInteger(1, Base64.getDecoder().decode(bootstrap.modulus)),
        BigInteger(1, Base64.getDecoder().decode(bootstrap.exponent)),
    ))
    private var used = false

    fun encode(parameters: Map<String, String>): String {
        check(!used) { "An envelope may only encode one request" }
        used = true
        val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        rsa.init(Cipher.ENCRYPT_MODE, publicKey)
        val session = association(linkedMapOf("key" to sessionKey, "iv" to iv))
        val plain = association(parameters.mapValues { it.value.toByteArray(Charsets.UTF_8) })
        try {
            val s = rsa.doFinal(session)
            val d = aes(Cipher.ENCRYPT_MODE).doFinal(plain)
            fun form(bytes: ByteArray) = URLEncoder.encode(Base64.getEncoder().encodeToString(bytes), "UTF-8")
            return "s=${form(s)}&d=${form(d)}"
        } finally {
            session.fill(0)
            plain.fill(0)
        }
    }

    fun decode(response: ByteArray): String {
        require(used) { "No request encoded" }
        if (response.isEmpty() || response.size % 16 != 0) throw ProtocolException("Invalid encrypted response")
        val plain = try { aes(Cipher.DECRYPT_MODE).doFinal(response) }
        catch (_: Exception) { throw ProtocolException("Unable to decrypt AirVPN response") }
        return try { plain.toString(Charsets.UTF_8) } finally { plain.fill(0) }
    }

    private fun aes(mode: Int) = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
        init(mode, SecretKeySpec(sessionKey, "AES"), IvParameterSpec(this@RequestEnvelope.iv))
    }
    override fun close() { sessionKey.fill(0); iv.fill(0) }
}

fun interface Transport { fun post(url: String, body: String): ByteArray }

class HttpTransport : Transport {
    override fun post(url: String, body: String): ByteArray {
        val uri = URI(url)
        require(uri.scheme in setOf("http", "https") && uri.host != null && uri.userInfo == null)
        val c = uri.toURL().openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8_000
            c.readTimeout = 8_000
            c.instanceFollowRedirects = false
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(body.toByteArray(Charsets.US_ASCII)) }
            if (c.responseCode != 200) throw IOException("AirVPN bootstrap returned HTTP ${c.responseCode}")
            return c.inputStream.use {
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    if (out.size() + count > 4 * 1024 * 1024) throw ProtocolException("AirVPN response too large")
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
        } finally { c.disconnect() }
    }
}

fun interface AirRequests {
    fun request(bootstrap: Bootstrap, action: String, login: String, password: String): String
}

class AirApi(
    private val software: String = "WaveVPN_0.2-dev",
    private val system: String = "Android",
    private val arch: String = "unknown",
    private val transport: Transport = HttpTransport(),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val maxAttempts: Int = 3,
) : AirRequests {
    init { require(maxAttempts in 1..6) }
    private data class Endpoint(var failures: Int = 0, var retryAt: Long = 0)
    private val endpoints = mutableMapOf<String, Endpoint>()
    private var preferred: String? = null

    @Synchronized override fun request(bootstrap: Bootstrap, action: String, login: String, password: String): String {
        require(action in setOf("user", "manifest"))
        val parameters = linkedMapOf("act" to action, "login" to login, "password" to password,
            "software" to software, "system" to system, "arch" to arch, "version" to "295")
        var failure: Exception? = null
        val urls = bootstrap.urls.distinct().sortedWith(compareBy<String> { it != preferred }
            .thenBy { URI(it).host?.contains(':') == true })
        var attempts = 0
        for (url in urls) {
            val endpoint = endpoints.getOrPut(url) { Endpoint() }
            if (clock() < endpoint.retryAt) continue
            if (attempts++ >= maxAttempts) break
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            try {
                val xml = RequestEnvelope(bootstrap).use { it.decode(transport.post(url, it.encode(parameters))) }
                val root = Xml.root(xml)
                if (root.getAttribute("error").isNotBlank()) throw LoginException(root.getAttribute("error"))
                if (root.tagName != action) throw ProtocolException("Unexpected AirVPN document")
                endpoint.failures = 0; endpoint.retryAt = 0; preferred = url
                return xml
            } catch (e: LoginException) { throw e }
            catch (e: InterruptedException) { throw e }
            catch (e: java.util.concurrent.CancellationException) { throw e }
            catch (e: Exception) {
                endpoint.failures = (endpoint.failures + 1).coerceAtMost(7)
                endpoint.retryAt = clock() + (1_000L shl endpoint.failures).coerceAtMost(120_000)
                failure = e
            }
        }
        throw IOException("No AirVPN bootstrap server returned a valid response", failure)
    }
}
