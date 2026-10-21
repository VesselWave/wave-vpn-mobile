// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpn.core

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class LiveBootstrapTest {
    @Test fun `real bootstrap decrypts and rejects a synthetic invalid account`() {
        assumeTrue("Opt in with WAVE_LIVE=1", System.getenv("WAVE_LIVE") == "1")
        val params = linkedMapOf("act" to "user", "login" to "wave-protocol-test-invalid-000000000000",
            "password" to "intentionally-invalid", "software" to "WaveVPN_0.2-dev", "system" to "Android",
            "arch" to "test", "version" to "295")
        var decrypted = false
        for (url in Defaults.bootstrap.urls.take(2)) {
            val xml = try {
                RequestEnvelope(Defaults.bootstrap).use { e -> e.decode(HttpTransport().post(url, e.encode(params))) }
            } catch (_: Exception) { continue }
            val root = Xml.root(xml)
            assertTrue("Expected explicit refusal", root.getAttribute("error").isNotBlank() ||
                root.getAttribute("message_action").isNotBlank())
            decrypted = true
            break
        }
        assertTrue("No real bootstrap response decrypted", decrypted)
    }
}
