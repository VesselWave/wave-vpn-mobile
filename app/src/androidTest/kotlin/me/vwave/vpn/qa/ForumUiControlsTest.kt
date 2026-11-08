// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpn.qa

import android.os.Process
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.vwave.vpn.MainActivity
import me.vwave.vpn.data.SplitMode
import me.vwave.vpn.vpn.TunnelState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Positive controls for the forum UI checks. These must pass on any build: they prove the
 * synthetic session reaches a real WireGuard backend, that Android's reported per-app routing
 * is read correctly, and that the harness can see and use a release (Disconnect) action.
 * If a control fails, the contract results in ForumUiContractTest are not meaningful.
 */
@RunWith(AndroidJUnit4::class)
class ForumUiControlsTest {
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var powerButton: android.graphics.Rect
    private val h = Harness

    @Before fun setUp() {
        h.cleanup()
        h.grantVpnConsent()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        h.waitForLabel("Connect")
        powerButton = checkNotNull(h.boundsOf("Connect"))
    }

    @After fun tearDown() {
        h.cleanup()
        h.app.settings.update { it.copy(splitMode = SplitMode.Off, splitApps = emptySet()) }
        scenario.close()
    }

    /** Connects through the real power button and waits for the WireGuard (not blocking) interface. */
    private fun connectToTunnel(): VpnSnapshot {
        h.assertNoRecoveryPending()
        h.clickOrFail("Connect")
        h.waitFor(8_000, "WireGuard interface", 300) { h.vpn().isWaveTunnel }
        return h.vpn()
    }

    /**
     * Cancels with a real tap on the power button before the 10 s handshake deadline, then checks
     * the release. A tap, not an accessibility click: while Connecting the spinner hides the button
     * from the accessibility tree (see ForumUiContractTest.busyCancelIsExposedToAccessibility).
     */
    private fun cancelAndCheckReleased(name: String) {
        h.evidence("$name-connecting", "control: tunnel up, before cancel")
        h.tap(powerButton)
        h.waitFor(10_000, "Down state and no VPN after cancel", 300) { h.state() is TunnelState.Down && !h.vpn().present }
        h.waitForLabel("Disconnected")
        h.waitForLabel("Connect")
        h.assertNoRecoveryPending()
    }

    @Test fun includeRoutesOnlyTheSelectedApp() {
        val (selected, other) = h.otherApps()
        h.app.settings.update { it.copy(splitMode = SplitMode.Include, splitApps = setOf(selected.packageName)) }
        val vpn = connectToTunnel()
        assertTrue("selected ${selected.packageName} must be routed: $vpn", vpn.routes(selected.uid))
        assertFalse("unselected ${other.packageName} must not be routed: $vpn", vpn.routes(other.uid))
        assertFalse("Wave itself must not be routed: $vpn", vpn.routes(Process.myUid()))
        cancelAndCheckReleased("control-include")
    }

    @Test fun bypassExcludesOnlyTheSelectedApp() {
        val (selected, other) = h.otherApps()
        h.app.settings.update { it.copy(splitMode = SplitMode.Exclude, splitApps = setOf(selected.packageName)) }
        val vpn = connectToTunnel()
        assertFalse("bypassed ${selected.packageName} must not be routed: $vpn", vpn.routes(selected.uid))
        assertTrue("other ${other.packageName} must be routed: $vpn", vpn.routes(other.uid))
        cancelAndCheckReleased("control-bypass")
    }

    @Test fun allAppsRoutesEveryApp() {
        val (a, b) = h.otherApps()
        val vpn = connectToTunnel()
        assertTrue("$vpn", vpn.routes(a.uid) && vpn.routes(b.uid))
        cancelAndCheckReleased("control-all")
    }

    /** The harness's view of the Split tunneling screen matches what the settings store records. */
    @Test fun splitTunnelingScreenDrivesSettings() {
        // The first row in the screen's own order (label), so it is visible without scrolling.
        val pm = h.app.packageManager
        val first = pm.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0).map { it.activityInfo.applicationInfo }
            .filter { it.packageName != h.app.packageName }
            .minBy { pm.getApplicationLabel(it).toString().lowercase() }
        val app1 = TestApp(first.packageName, first.uid)
        val label = pm.getApplicationLabel(first).toString()
        h.clickOrFail("Settings")
        h.clickOrFail("Split tunneling")
        h.clickOrFail("Only selected")
        h.waitFor(5_000, "Include mode") { h.app.settings.current.splitMode == SplitMode.Include }
        h.waitForLabel("Only checked apps use the VPN. Everything else connects directly.")
        h.clickOrFail(label)
        h.waitFor(5_000, "app checked") { app1.packageName in h.app.settings.current.splitApps }
        h.evidence("control-split-screen", "control: one app checked in Only selected mode")
        h.clickOrFail(label)
        h.waitFor(5_000, "app unchecked") { h.app.settings.current.splitApps.isEmpty() }
        assertEquals(SplitMode.Include, h.app.settings.current.splitMode)
    }
}
