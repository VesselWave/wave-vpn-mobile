// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Forum item 4: "Only selected" with nothing checked must never become a tunnel for every app. */
class AppRoutingTest {
    private val wgKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    private val account = Account("tester", null, wgKey,
        listOf(DeviceKey("phone", wgKey, wgKey, "10.1.2.3/32", "", "10.1.0.1", "")))
    private val server = Server("Test", "nl", "City", listOf("192.0.2.1"), 1, 100, 1, 10, 0, null, null)
    private fun profile(options: WgOptions) = wgQuickConfig(account, account.keys.first(), server, options)

    @Test fun `only selected with no apps is refused instead of routing every app`() {
        val e = assertThrows(NoAppsSelectedException::class.java) { profile(WgOptions(entryIndex = 0, onlySelected = true)) }
        assertEquals(NoAppsSelectedException.MESSAGE, e.message)
        assertThrows(NoAppsSelectedException::class.java) { requireAppRouting(onlySelected = true, includedApps = emptySet()) }
    }

    @Test fun `only selected with apps lists exactly those apps`() {
        val text = profile(WgOptions(entryIndex = 0, includedApps = setOf("org.example.b", "org.example.a"), onlySelected = true))
        assertTrue(text.contains("IncludedApplications = org.example.a, org.example.b\n"))
        assertFalse(text.contains("ExcludedApplications"))
    }

    @Test fun `all apps and empty bypass keep a full tunnel`() {
        for (options in listOf(WgOptions(entryIndex = 0), WgOptions(entryIndex = 0, excludedApps = emptySet()))) {
            val text = profile(options)
            assertFalse(text.contains("IncludedApplications"))
            assertFalse(text.contains("ExcludedApplications"))
        }
        requireAppRouting(onlySelected = false, includedApps = emptySet())
    }

    @Test fun `only selected cannot carry bypassed apps`() {
        assertThrows(IllegalArgumentException::class.java) {
            profile(WgOptions(entryIndex = 0, excludedApps = setOf("org.example.a"), onlySelected = true))
        }
    }
}
