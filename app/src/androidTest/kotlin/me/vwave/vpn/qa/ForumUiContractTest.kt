// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpn.qa

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.vwave.vpn.MainActivity
import me.vwave.vpn.data.SplitMode
import me.vwave.vpn.vpn.NO_APPS_FAILURE
import me.vwave.vpn.vpn.TunnelState
import me.vwave.vpn.vpn.WaveVpnService
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * AirVPN forum report items 3 and 4 (comment 268266, staff review of v0.1.3) and the hidden
 * Cancel action. These began as opt-in contracts that failed on v0.1.4; they now run on every
 * suite run and must pass. Each test first asserts that its fault injection really happened (a
 * harness failure), then reports every contract violation with Android's own routing evidence.
 * Screenshots/text records go to files/forum-ui/.
 */
@RunWith(AndroidJUnit4::class)
class ForumUiContractTest {
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val h = Harness

    @Before fun setUp() {
        h.cleanup()
        h.grantVpnConsent()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        h.waitForLabel("Connect")
    }

    @After fun tearDown() {
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
     * GoBackend.setState(UP). Connection.kt then re-establishes the blocking interface and reports
     * Failed. The screen must say traffic is blocked and offer Stop blocking, which must release it.
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
        // Precondition: this emulator has no Android lockdown, so Wave holds (and can release) the block.
        check(blocking && !WaveVpnService.lockdownEnabled()) { "Expected Wave's own blocking interface without lockdown: $vpn" }
        h.evidence("forum3-after-failure", "forum 3: after injected backend UP failure; blocking=$blocking other=${other.packageName} routed=${vpn.routes(other.uid)}")
        val violations = mutableListOf<String>()
        if (h.hasLabel("Disconnected")) violations += "Overview says 'Disconnected' while Android routes every app " +
            "(e.g. ${other.packageName} routed=${vpn.routes(other.uid)}) into Wave's blocking interface: $vpn"
        if (!h.hasLabel("Blocking traffic")) violations += "No 'Blocking traffic' status while blocked; screen=${h.screenText()}"
        if (!h.hasLabel("Apps have no internet")) violations += "No explanation that apps are offline; screen=${h.screenText()}"
        if (!h.hasLabel("Stop blocking")) violations += "No 'Stop blocking' action; screen=${h.screenText()}"
        else {
            h.clickOrFail("Stop blocking")
            runCatching { h.waitFor(10_000, "blocking interface released", 300) { !h.vpn().present } }
                .onFailure { violations += "'Stop blocking' did not remove the blocking interface: ${h.vpn()}" }
            runCatching { h.waitForLabel("Disconnected") }
                .onFailure { violations += "After release the screen does not say 'Disconnected': ${h.screenText()}" }
            if (WaveVpnService.instance?.isBlocking == true) violations += "App still records a blocking descriptor"
            h.evidence("forum3-after-release", "forum 3: after Stop blocking")
        }
        report("Forum 3 (failed start / blocking interface)", violations)
    }

    /** Forum item 4, through the screen: "Only selected" with nothing checked refuses to connect. */
    @Test fun forum4_onlySelectedWithNoAppsMustNotTunnelEveryApp() {
        h.app.settings.update { it.copy(splitMode = SplitMode.Include, splitApps = emptySet()) }
        h.assertNoRecoveryPending()
        h.waitForLabel("No apps selected") // Overview warns before the user taps Connect.
        h.clickOrFail("Connect")
        val violations = mutableListOf<String>()
        runCatching { h.waitFor(5_000, "refused connection", 200) { h.state() == TunnelState.Failed(NO_APPS_FAILURE) } }
            .onFailure { violations += "Connect was not refused with the empty-selection reason; state=${h.state()}" }
        // Watch for a late tunnel: none may appear at any point.
        val end = android.os.SystemClock.elapsedRealtime() + 3_000
        while (android.os.SystemClock.elapsedRealtime() < end) {
            val vpn = h.vpn()
            if (vpn.present) { violations += "A VPN appeared after Connect with no apps selected: $vpn"; break }
            SystemClockSleep(200)
        }
        h.evidence("forum4-refused", "forum 4: Only selected, zero apps checked, after Connect")
        if (!h.hasLabel("No apps selected") || !h.hasLabel("Choose apps"))
            violations += "Overview does not explain the refusal with a way to fix it; screen=${h.screenText()}"
        h.assertNoRecoveryPending()
        report("Forum 4 (empty Only selected, Connect)", violations)
    }

    /**
     * Forum item 4, entry points without the screen (Quick Settings tile, Always-on restart and
     * settings changes while connected all call TunnelManager.connect). A running all-apps tunnel
     * must be torn down, not kept, when the settings switch to an empty "Only selected".
     */
    @Test fun forum4_emptyOnlySelectedTearsDownARunningTunnel() {
        val (a, b) = h.otherApps()
        h.assertNoRecoveryPending()
        h.clickOrFail("Connect")
        h.waitFor(8_000, "WireGuard interface", 300) { h.vpn().isWaveTunnel }
        check(h.vpn().routes(a.uid)) { "Precondition: the all-apps tunnel routes ${a.packageName}: ${h.vpn()}" }
        h.app.settings.update { it.copy(splitMode = SplitMode.Include, splitApps = emptySet()) }
        h.instrumentation.runOnMainSync { h.app.tunnels.connect() }
        val violations = mutableListOf<String>()
        runCatching { h.waitFor(10_000, "tunnel removed and refusal reported", 300) {
            !h.vpn().present && h.state() == TunnelState.Failed(NO_APPS_FAILURE)
        } }.onFailure {
            val vpn = h.vpn()
            violations += "Empty Only selected left state=${h.state()} vpn=$vpn" +
                (if (vpn.routes(a.uid) || vpn.routes(b.uid)) " (unchecked apps still routed)" else "")
        }
        h.evidence("forum4-teardown", "forum 4: running tunnel after switching to empty Only selected")
        h.assertNoRecoveryPending()
        report("Forum 4 (empty Only selected, running tunnel)", violations)
    }

    /** Forum item 4, the screen: the copy must not promise per-app routing when nothing is checked. */
    @Test fun forum4_onlySelectedWithNoAppsMustExplainTheEffect() {
        h.app.settings.update { it.copy(splitMode = SplitMode.Off, splitApps = emptySet()) }
        h.clickOrFail("Settings")
        h.clickOrFail("Split tunneling")
        h.clickOrFail("Only selected")
        h.waitFor(5_000, "Include mode") { h.app.settings.current.splitMode == SplitMode.Include }
        // Precondition: the screen really shows Only selected (the accessibility tree can lag a frame).
        h.waitForLabel("Only checked apps use the VPN. Everything else connects directly.")
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
     * used to cover the 176 dp power button, and Compose omits fully covered nodes from the
     * accessibility tree, so TalkBack/Switch Access users could not reach "Cancel connecting".
     */
    @Test fun busyCancelIsExposedToAccessibility() {
        h.assertNoRecoveryPending()
        h.clickOrFail("Connect")
        h.waitFor(8_000, "WireGuard interface", 300) { h.vpn().isWaveTunnel }
        SystemClockSleep(500)
        val exposed = h.hasLabel("Cancel connecting")
        val busyScreen = h.screenText()
        h.evidence("a11y-connecting", "extra finding: accessibility tree while Connecting; cancel exposed=$exposed")
        // Cancel before the handshake deadline either way; a failed click falls back to the app's own disconnect.
        if (!exposed || !h.click("Cancel connecting")) h.instrumentation.runOnMainSync { h.app.tunnels.disconnect() }
        h.waitFor(10_000, "Down after cancel", 300) { h.state() is TunnelState.Down && !h.vpn().present }
        h.assertNoRecoveryPending()
        if (!exposed) fail("While Connecting, no accessibility node offers 'Cancel connecting'; screen while Connecting=$busyScreen")
    }

    private fun instrumentationIdle() { h.instrumentation.waitForIdleSync(); SystemClockSleep(500) }
}

@Suppress("FunctionName") private fun SystemClockSleep(ms: Long) = android.os.SystemClock.sleep(ms)
