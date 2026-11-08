// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpn.core

import org.junit.Assert.*
import org.junit.Test

class ForumManifestControlsContractTest {

    @Test fun `valid bootstrap rotation and server list still accepted`() {
        val key = FakeAirNetwork.keyB
        val urls = listOf("https://rotated.example/")
        val actual = parseManifest(FakeAirNetwork.manifest(key, urls))
        assertEquals(key.bootstrap(urls), actual.bootstrap)
        assertEquals(parseManifest(FakeAirNetwork.fixture).servers, actual.servers)
    }

    @Test fun `malformed XML still rejected`() {
        assertThrows(Exception::class.java) { parseManifest("<manifest><servers>") }
    }

    @Test fun `wrong document and entity declarations still rejected`() {
        assertThrows(Exception::class.java) { parseManifest("<user/>") }
        assertThrows(Exception::class.java) {
            parseManifest("<!DOCTYPE manifest [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><manifest>&x;</manifest>")
        }
    }
}
