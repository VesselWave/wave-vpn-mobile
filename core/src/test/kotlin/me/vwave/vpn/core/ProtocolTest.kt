// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpn.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.math.BigInteger
import java.net.URLDecoder
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class ProtocolTest {
    private val b64 = Base64.getEncoder()
    private val un64 = Base64.getDecoder()
    private val wgKey = b64.encodeToString(ByteArray(32) { 7 })
    private fun fixture() = File("../protocol-data/fixtures/eddie-manifest.xml").readText()

    @Test fun `independent server decodes RSA wrapped session and encrypted parameters`() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pub = pair.public as RSAPublicKey
        fun unsigned(n: BigInteger) = n.toByteArray().let { if (it[0] == 0.toByte()) it.drop(1).toByteArray() else it }
        val bootstrap = Bootstrap(listOf("http://127.0.0.1"), b64.encodeToString(unsigned(pub.modulus)),
            b64.encodeToString(unsigned(pub.publicExponent)))
        var requestCount = 0
        val api = AirApi(transport = Transport { _, body ->
            try {
            requestCount++
            val form = body.split('&').associate { field -> field.split('=', limit=2).let {
                it[0] to un64.decode(URLDecoder.decode(it[1], "UTF-8"))
            } }
            val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.DECRYPT_MODE, pair.private) }
            fun assoc(bytes: ByteArray) = bytes.toString(Charsets.UTF_8).lineSequence().filter(String::isNotEmpty)
                .associate { line -> line.split(':', limit=2).let { un64.decode(it[0]).toString(Charsets.UTF_8) to un64.decode(it[1]) } }
            val session = assoc(rsa.doFinal(form.getValue("s")))
            assertEquals(32, session.getValue("key").size)
            assertEquals(16, session.getValue("iv").size)
            fun aes(mode: Int) = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                init(mode, SecretKeySpec(session.getValue("key"), "AES"), IvParameterSpec(session.getValue("iv")))
            }
            val params = assoc(aes(Cipher.DECRYPT_MODE).doFinal(form.getValue("d")))
            assertEquals("a+&=é", params.getValue("password").toString(Charsets.UTF_8))
            assertEquals("manifest", params.getValue("act").toString(Charsets.UTF_8))
            aes(Cipher.ENCRYPT_MODE).doFinal(fixture().toByteArray(Charsets.UTF_8))
            } catch (e: Exception) { throw AssertionError("Fake server failed", e) }
        })
        assertTrue(parseManifest(api.request(bootstrap, "manifest", "tester", "a+&=é")).servers.isNotEmpty())
        assertEquals(1, requestCount)
    }

    @Test fun `official Eddie manifest parses groups and advertised WireGuard entry index`() {
        val m = parseManifest(fixture())
        assertTrue(m.servers.size > 10)
        assertTrue(m.wireguardModes.any { it.port == 1637 && it.entryIndex == 2 })
        assertTrue(m.servers.any { it.bandwidthMax > 0 && it.countryCode.isNotEmpty() })
        // Eddie's bundled manifest intentionally omits live entry addresses.
        assertTrue(m.servers.all { it.entries.isEmpty() })
    }

    @Test fun `profile uses account peer key and brackets IPv6 endpoints`() {
        val a = parseUser("""<user login="tester" wg_public_key="$wgKey"><keys><key name="phone"
            wg_private_key="$wgKey" wg_preshared="$wgKey" wg_ipv4="10.1.2.3/32"
            wg_ipv6="fd00::1/128" wg_dns_ipv4="10.1.0.1" wg_dns_ipv6="fd00::2"/></keys></user>""", "tester")
        val s = Server("Test", "nl", "City", listOf("192.0.2.1", "2001:db8::1"), 1, 100, 1, 10, 0, null, null)
        val text = wgQuickConfig(a, a.keys.first(), s, WgOptions(entryIndex=0, ipv6Endpoint=true))
        assertTrue(text.contains("Endpoint = [2001:db8::1]:1637"))
        assertTrue(text.contains("AllowedIPs = 0.0.0.0/0, ::/0"))
        assertTrue(text.contains("DNS = 10.1.0.1, fd00::2"))
    }

    @Test fun `reject entity expansion and account mismatch`() {
        assertThrows(IllegalArgumentException::class.java) {
            Xml.root("""<!DOCTYPE manifest [<!ENTITY x SYSTEM "file:///etc/passwd">]><manifest>&x;</manifest>""")
        }
        assertThrows(LoginException::class.java) { parseUser("<user login=\"other\"/>", "tester") }
    }

    @Test fun `unavailable configured device does not silently switch identity`() {
        val a = Account("tester", null, wgKey, listOf(DeviceKey("phone", wgKey, wgKey, "10.1.2.3/32", "", "10.1.0.1", "")))
        assertNull(a.key("deleted-device"))
        assertEquals("phone", a.key("")!!.name)
    }

    @Test fun `reject profile directive injection`() {
        val a = Account("tester", null, wgKey, emptyList())
        val k = DeviceKey("phone", wgKey, wgKey, "10.1.2.3/32\nPostUp = malicious", "", "10.1.0.1", "")
        val s = Server("Test", "nl", "City", listOf("192.0.2.1"), 1, 100, 1, 10, 0, null, null)
        assertThrows(IllegalArgumentException::class.java) { wgQuickConfig(a, k, s, WgOptions(entryIndex=0)) }
    }
}
