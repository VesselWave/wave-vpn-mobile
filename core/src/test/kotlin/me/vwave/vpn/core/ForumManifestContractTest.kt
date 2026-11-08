// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpn.core

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Desired contracts, intentionally RED on v0.1.4. Run scripts/check-forum.py. */
@RunWith(Parameterized::class)
class ForumManifestContractTest(private val label: String, private val broken: String) {
    @Before fun optIn() { assumeTrue(System.getenv("WAVE_FORUM_CONTRACTS") == "1") }

    @Test fun `usable servers survive optional bootstrap corruption`() {
        val good = parseManifest(FakeAirNetwork.fixture)
        val actual = parseManifest(broken)
        assertEquals("$label must not discard usable servers", good.servers, actual.servers)
        assertEquals(good.wireguardModes, actual.wireguardModes)
        assertEquals(good.factors, actual.factors)
    }

    @Test fun `sign in accepts usable manifest with bad bootstrap`() {
        val session = RefreshSession(AirRequests { _, action, _, _ ->
            if (action == "user") FakeAirNetwork.user() else broken
        }, null, {})
        session.update("tester", "synthetic")
        assertEquals(parseManifest(FakeAirNetwork.fixture).servers, parseManifest(session.snapshot!!.manifest).servers)
    }

    @Test fun `refresh accepts servers and keeps last usable routing across restore`() {
        val key = FakeAirNetwork.keyB
        val urls = listOf("https://last-known.example/")
        val previous = FakeAirNetwork.manifest(key, urls)
        val changed = broken.replace("location=\"", "location=\"Updated ")
        val expected = parseManifest(FakeAirNetwork.fixture.replace("location=\"", "location=\"Updated ")).servers
        val initial = SessionSnapshot("tester", "synthetic", FakeAirNetwork.user(), previous, 0, 0)
        var saved: SessionSnapshot? = null
        val session = RefreshSession(AirRequests { _, action, _, _ ->
            if (action == "user") FakeAirNetwork.user() else changed
        }, initial, { saved = it })
        session.update(force = true)
        assertEquals("new servers must persist even when bootstrap is unusable", expected, parseManifest(saved!!.manifest).servers)
        var used: Bootstrap? = null
        val restored = RefreshSession(AirRequests { b, action, _, _ ->
            used = b
            if (action == "user") FakeAirNetwork.user() else previous
        }, saved, {})
        restored.update(force = true)
        assertEquals("last usable routing must survive restart", key.bootstrap(urls), used)
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun cases(): List<Array<String>> {
            val xml = FakeAirNetwork.fixture
            fun attr(name: String, value: String?) = xml.replace(Regex("$name=\"[^\"]*\""),
                value?.let { "$name=\"$it\"" } ?: "")
            fun url(value: String) = xml.replace(Regex("<urls>.*?</urls>", RegexOption.DOT_MATCHES_ALL),
                "<urls><url address=\"$value\"/></urls>")
            return listOf(
                arrayOf("missing modulus", attr("auth_rsa_modulus", null)),
                arrayOf("missing exponent", attr("auth_rsa_exponent", null)),
                arrayOf("missing urls", xml.replace(Regex("<urls>.*?</urls>", RegexOption.DOT_MATCHES_ALL), "")),
                arrayOf("empty urls", url("")),
                arrayOf("invalid base64", attr("auth_rsa_modulus", "!")),
                arrayOf("undersized RSA key", attr("auth_rsa_modulus", "AQAB")),
                arrayOf("invalid exponent", attr("auth_rsa_exponent", "AA==")),
                arrayOf("invalid URL", url("garbage")),
                arrayOf("local file URL", url("file:///etc/passwd")),
                arrayOf("URL credentials", url("https://user:secret@example.com/")),
            )
        }
    }
}
