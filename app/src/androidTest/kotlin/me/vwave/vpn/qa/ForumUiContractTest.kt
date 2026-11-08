// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpn.qa

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.vwave.vpn.MainActivity
import me.vwave.vpn.data.SplitMode
import me.vwave.vpn.vpn.TunnelState
import me.vwave.vpn.vpn.WaveVpnService
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Desired behavior for AirVPN forum report items 3 and 4 (comment 268266, staff review of v0.1.3).
 * Opt-in (`-e waveForumContracts 1`) because they are expected to FAIL until those defects are
 * fixed; they are never inverted into green "expected broken" checks. Each test first asserts that
 * its fault injection really happened (a harness failure), then reports every contract violation
 * with Android's own routing evidence. Screenshots/text records go to files/forum-ui/.
 */
@RunWith(AndroidJUnit4::class)
class ForumUiContractTest {
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val h = Harness

    @Before fun setUp() {
        h.requireContracts()
        h.cleanup()
        h.grantVpnConsent()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        h.waitForLabel("Connect")
    }

    @After fun tearDown() {
        if (!h.contractsEnabled) return
        h.cleanup()
        h.app.settings.update { it.copy(splitMode = SplitMode.Off, splitApps = emptySet(), deviceKey = SyntheticSession.DEVICE) }
        scenario.close()
    }

    private fun report(name: String, violations: List<String>) {
        if (violations.isNotEmpty()) fail("$name violates ${violations.size} contract(s):\n- " + violations.joinToString("\n- "))
    }

    /**
     * Forum item 3. A WireGuard backend UP failure is injected without production changes: the
     * synthetic account's second device has DNS 0.0.0.0, which VpnService.Builder rejects inside
     * GoBackend.setState(UP). Connection.kt then re-establishes the blocking interface and reports Failed.
     */
    @Test fun forum3_failedStartMustNotHideABlockingInterface() {
        val (other, _) = h.otherApps()
        h.app.settings.update { it.copy(deviceKey = SyntheticSession.BAD_DNS_DEVICE) }
        h.assertNoRecoveryPending()
        h.clickOrFail("Connect")
        h.waitFor(15_000, "Failed state from the injected backend UP failure", 300) { h.state() is TunnelState.Failed }
        // Injection precondition: the failure came from starting WireGuard, not from setup.
        val log = h.app.log.entries.value.map { it.message }
        check("Unable to start WireGuard" in log && log.any { it.startsWith("Connecting to ${SyntheticSession.SERVER}") }) {
            "Injection did not reach the WireGuard backend; log=$log"
        }
        SystemClockSleep(1_000)
        val vpn = h.vpn()
        val blocking = vpn.isBlockingInterface
        h.evidence("forum3-after-failure", "forum 3: after injected backend UP failure; blocking=$blocking other=${other.packageName} routed=${vpn.routes(other.uid)}")
        val violations = mutableListOf<String>()
        if (blocking) {
            if (h.hasLabel("Disconnected")) violations += "Overview says 'Disconnected' while Android routes every app " +
                "(e.g. ${other.packageName} routed=${vpn.routes(other.uid)}) into Wave's blocking interface: $vpn"
            val release = h.screenText().firstOrNull { it == "Disconnect" || Regex("(?i)release|stop blocking|allow traffic").containsMatchIn(it) }
            if (release == null) violations += "No action releases the blocking interface; the only action is " +
                "'${if (h.hasLabel("Connect")) "Connect" else "?"}'. screen=${h.screenText()}"
            else {
                h.click(release)
                runCatching { h.waitFor(10_000, "blocking interface released", 300) { !h.vpn().present } }
                    .onFailure { violations += "'$release' did not remove the blocking interface: ${h.vpn()}" }
            }
        } else if (WaveVpnService.instance?.isBlocking == true) {
            violations += "App records a blocking descriptor that Android does not report as a VPN network: $vpn"
        }
        report("Forum 3 (failed start / blocking interface)", violations)
    }

    /** Forum item 4, effective routing: "Only selected" with nothing checked. */
    @Test fun forum4_onlySelectedWithNoAppsMustNotTunnelEveryApp() {
        val (a, b) = h.otherApps()
        h.app.settings.update { it.copy(splitMode = SplitMode.Include, splitApps = emptySet()) }
        h.assertNoRecoveryPending()
        h.clickOrFail("Connect")
        // Either outcome is acceptable to the contract: refuse to connect, or route no unchecked app.
        h.waitFor(8_000, "WireGuard interface or a refused connection", 300) {
            h.vpn().isWaveTunnel || (h.state().let { it is TunnelState.Failed || it is TunnelState.Down } && !h.vpn().present)
        }
        val vpn = h.vpn()
        h.evidence("forum4-routing", "forum 4: Only selected, zero apps checked")
        h.cleanup() // Cancel before the 10 s handshake deadline (keeps account recovery unarmed).
        h.assertNoRecoveryPending()
        val violations = mutableListOf<String>()
        if (vpn.isWaveTunnel) for (app in listOf(a, b)) if (vpn.routes(app.uid))
            violations += "Unchecked ${app.packageName} (uid ${app.uid}) is routed through the VPN: $vpn"
        report("Forum 4 (empty Only selected routing)", violations)
    }

    /** Forum item 4, the screen: the copy must not promise per-app routing when nothing is checked. */
    @Test fun forum4_onlySelectedWithNoAppsMustExplainTheEffect() {
        h.app.settings.update { it.copy(splitMode = SplitMode.Off, splitApps = emptySet()) }
        h.clickOrFail("Settings")
        h.clickOrFail("Split tunneling")
        h.clickOrFail("Only selected")
        h.waitFor(5_000, "Include mode") { h.app.settings.current.splitMode == SplitMode.Include }
        // Precondition: the screen really shows Only selected (the accessibility tree can lag a frame).
        h.waitForLabel("Only selected")
        instrumentationIdle()
        val text = h.screenText()
        h.evidence("forum4-split-screen", "forum 4: Split tunneling screen, Only selected, zero apps checked")
        val notice = Regex("(?i)no apps? (is |are )?(checked|selected)|select at least one|choose at least one|" +
            "nothing is (checked|selected)|until you (check|select)|all apps (will )?use the vpn")
        val violations = mutableListOf<String>()
        if (text.none { notice.containsMatchIn(it) }) violations += "With zero apps checked the screen gives no " +
            "empty-selection notice; it says: ${text.filter { "VPN" in it }}"
        report("Forum 4 (empty Only selected copy)", violations)
    }

    /**
     * Found while building these checks (not a forum item): while Connecting, the 196 dp spinner
     * fully covers the 176 dp power button, and Compose omits fully covered nodes from the
     * accessibility tree, so TalkBack/Switch Access users cannot reach "Cancel connecting".
     */
    @Test fun busyCancelIsExposedToAccessibility() {
        h.assertNoRecoveryPending()
        val button = checkNotNull(h.boundsOf("Connect"))
        h.clickOrFail("Connect")
        h.waitFor(8_000, "WireGuard interface", 300) { h.vpn().isWaveTunnel }
        SystemClockSleep(500)
        val exposed = h.hasLabel("Cancel connecting")
        val busyScreen = h.screenText()
        h.evidence("a11y-connecting", "extra finding: accessibility tree while Connecting; cancel exposed=$exposed")
        h.tap(button) // Cancel before the handshake deadline either way.
        h.waitFor(10_000, "Down after cancel", 300) { h.state() is TunnelState.Down && !h.vpn().present }
        h.assertNoRecoveryPending()
        if (!exposed) fail("While Connecting, no accessibility node offers 'Cancel connecting' (a touch at the " +
            "button still cancels); screen while Connecting=$busyScreen")
    }

    private fun instrumentationIdle() { h.instrumentation.waitForIdleSync(); SystemClockSleep(500) }
}

@Suppress("FunctionName") private fun SystemClockSleep(ms: Long) = android.os.SystemClock.sleep(ms)
