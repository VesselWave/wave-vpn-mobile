// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpn.core

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.math.BigInteger
import java.net.URLDecoder
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** An independent encrypted fake endpoint; never opens a network connection. */
class RetryTest {
    private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val pub = pair.public as RSAPublicKey
    private fun b64(n: BigInteger) = Base64.getEncoder().encodeToString(n.toByteArray().let {
        if (it.first() == 0.toByte()) it.drop(1).toByteArray() else it
    })
    private val bootstrap = Bootstrap((1..6).map { "https://endpoint$it.example" }, b64(pub.modulus), b64(pub.publicExponent))
    private fun reply(body: String, xml: String): ByteArray {
        val decoder = Base64.getDecoder()
        val form = body.split('&').associate { it.split('=', limit = 2).let { p ->
            p[0] to decoder.decode(URLDecoder.decode(p[1], "UTF-8"))
        } }
        val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.DECRYPT_MODE, pair.private) }
        val session = rsa.doFinal(form.getValue("s")).toString(Charsets.UTF_8).lineSequence()
            .filter { it.isNotEmpty() }.associate { it.split(':', limit = 2).let { p ->
                decoder.decode(p[0]).toString(Charsets.UTF_8) to decoder.decode(p[1])
            } }
        return Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(session.getValue("key"), "AES"), IvParameterSpec(session.getValue("iv")))
            doFinal(xml.toByteArray())
        }
    }

    @Test fun `failover prefers last successful endpoint and skips cooling failures`() {
        var now = 0L
        val urls = mutableListOf<String>()
        val api = AirApi(transport = Transport { url, body ->
            urls += url
            if (url == bootstrap.urls[0]) throw IOException("offline")
            reply(body, "<manifest/>")
        }, clock = { now })
        repeat(10) { api.request(bootstrap, "manifest", "tester", "synthetic") }
        assertEquals(11, urls.size)
        assertEquals(1, urls.count { it == bootstrap.urls[0] })
        assertEquals(bootstrap.urls[1], urls.last())
        now = 5_000
        api.request(bootstrap, "manifest", "tester", "synthetic")
        assertEquals(bootstrap.urls[1], urls.last())
    }

    @Test fun `bounded attempts and exponential endpoint cooldown with no sleeping`() {
        var now = 0L; var count = 0
        val boot = bootstrap.copy(urls = bootstrap.urls.take(1))
        val api = AirApi(transport = Transport { _, _ -> count++; throw IOException() }, clock = { now })
        fun fail() { assertThrows(IOException::class.java) { api.request(boot, "user", "x", "x") } }
        fail(); assertEquals(1, count)
        repeat(100) { fail() }; assertEquals(1, count)
        now = 2_000; fail(); assertEquals(2, count)
        now = 4_000; fail(); assertEquals(2, count)
        now = 6_000; fail(); assertEquals(3, count)
        val bounded = AirApi(transport = Transport { _, _ -> count++; throw IOException() })
        assertThrows(IOException::class.java) { bounded.request(bootstrap, "manifest", "x", "x") }
        assertEquals(6, count)
    }

    @Test(timeout = 15000) fun `auth rejection stops failover and cancellation is never retried`() {
        var calls = 0
        val api = AirApi(transport = Transport { _, body -> calls++; reply(body, "<user error=\"denied\"/>") })
        assertThrows(LoginException::class.java) { api.request(bootstrap, "user", "x", "x") }
        assertEquals(1, calls)
        val cancelled = AirApi(transport = Transport { _, _ -> calls++; throw java.util.concurrent.CancellationException() })
        assertThrows(java.util.concurrent.CancellationException::class.java) { cancelled.request(bootstrap, "user", "x", "x") }
        assertEquals(2, calls)
    }

    @Test fun `invalid encrypted reply fails over with a fresh envelope`() {
        val bodies = mutableListOf<String>()
        val api = AirApi(transport = Transport { _, body ->
            bodies += body
            if (bodies.size == 1) byteArrayOf(1) else reply(body, "<manifest/>")
        })
        assertEquals("<manifest/>", api.request(bootstrap, "manifest", "x", "x"))
        assertEquals(2, bodies.distinct().size)
    }
}
