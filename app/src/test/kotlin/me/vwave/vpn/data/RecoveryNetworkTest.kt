// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpn.data

import org.junit.Assert.*
import org.junit.Test

class RecoveryNetworkTest {
    private fun n(name: String, validated: Boolean = true, nonVpn: Boolean = true,
        active: Boolean = false, wifi: Boolean = false) =
        RecoveryNetwork(name, true, nonVpn, validated, active, wifi)

    @Test fun `unusable cellular preceding validated wifi does not win recovery`() {
        assertEquals("wifi", recoveryNetwork(listOf(n("cellular", validated = false), n("wifi", wifi = true))))
    }
    @Test fun `vpn and unvalidated networks never carry recovery credentials`() {
        assertNull(recoveryNetwork(listOf(n("vpn", nonVpn = false, active = true), n("cell", validated = false))))
    }
    @Test fun `active validated physical network wins and wifi breaks ties`() {
        assertEquals("activeCell", recoveryNetwork(listOf(n("wifi", wifi = true), n("activeCell", active = true))))
        assertEquals("wifi", recoveryNetwork(listOf(n("cell"), n("wifi", wifi = true))))
    }
}
