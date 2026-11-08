// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpn.qa

import android.content.Intent
import android.graphics.Bitmap
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import me.vwave.vpn.WaveApp
import me.vwave.vpn.vpn.TunnelManager
import me.vwave.vpn.vpn.TunnelState
import me.vwave.vpn.vpn.WaveVpnService
import org.junit.Assume.assumeTrue
import java.io.File

/** What Android itself reports about the VPN network owned by this package. */
data class VpnSnapshot(val present: Boolean, val iface: String?, val addresses: String?, val mtu: Int?,
    val uidRanges: List<LongRange>, val raw: String, val session: String? = null) {
    /** True when Android routes this uid (user 0) through the VPN. */
    fun routes(uid: Int) = uidRanges.any { uid.toLong() in it }
    val isWaveTunnel get() = present && addresses?.contains(SyntheticSession.DEVICE_IPV4) == true
    val isBlockingInterface get() = present && addresses?.contains("192.0.2.1/32") == true
    override fun toString() = if (!present) "no VPN network for this app (other VPN agents: ${raw.ifEmpty { "none" }})" else
        "VPN session=$session iface=$iface addresses=$addresses mtu=$mtu uids=${uidRanges.joinToString { "${it.first}-${it.last}" }}"
}

data class TestApp(val packageName: String, val uid: Int)

object Harness {
    val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val app get() = ApplicationProvider.getApplicationContext<WaveApp>()
    private val evidenceDir by lazy { File(app.getExternalFilesDir(null), "forum-ui").apply { mkdirs() } }

    /** Opt-in desired-behavior contracts: `-e waveForumContracts 1`. They fail on the confirmed defects. */
    val contractsEnabled get() = InstrumentationRegistry.getArguments().getString("waveForumContracts") == "1"
    fun requireContracts() = assumeTrue(
        "Forum UI contracts are opt-in: pass -e waveForumContracts 1 (scripts/forum-ui-emulator.py --contracts)",
        contractsEnabled)

    fun shell(command: String): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** Grants VPN consent without the system dialog (the qa package only). */
    fun grantVpnConsent() {
        shell("appops set ${app.packageName} ACTIVATE_VPN allow")
        check(VpnService.prepare(app) == null) { "VPN consent was not granted to ${app.packageName}" }
    }

    fun vpn(): VpnSnapshot {
        val dump = shell("dumpsys connectivity")
        val myUid = Process.myUid()
        // One NetworkAgentInfo per line; pick the VPN agent this app owns.
        val line = dump.lineSequence().map(String::trim).firstOrNull {
            it.startsWith("NetworkAgentInfo") && Regex("Transports: [A-Z_|]*VPN").containsMatchIn(it) && Regex("OwnerUid: $myUid\\b").containsMatchIn(it)
        } ?: return VpnSnapshot(false, null, null, null, emptyList(),
            dump.lineSequence().filter { "VPN" in it && "NetworkAgentInfo" in it }.joinToString("\n").take(4000))
        val ranges = Regex("(?<![A-Za-z])Uids: <\\{([^}]*)\\}>").find(line)?.groupValues?.get(1).orEmpty()
            .split(',').map(String::trim).filter(String::isNotEmpty).map { r ->
                val p = r.split('-').map(String::toLong); p.first()..p.last()
            }
        return VpnSnapshot(true, Regex("InterfaceName: (\\S+)").find(line)?.groupValues?.get(1),
            Regex("LinkAddresses: \\[ ([^\\]]*) \\]").find(line)?.groupValues?.get(1),
            Regex("MTU: (\\d+)").find(line)?.groupValues?.get(1)?.toInt(), ranges, line,
            Regex("sessionId=([^,}]*)").find(line)?.groupValues?.get(1))
    }

    /** Two launchable regular apps that are neither this app nor another Wave build. */
    fun otherApps(): Pair<TestApp, TestApp> {
        val pm = app.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.applicationInfo }.distinctBy { it.packageName }
            .filter { it.uid >= Process.FIRST_APPLICATION_UID && !it.packageName.startsWith("me.vwave.")}
            .sortedBy { it.packageName }.map { TestApp(it.packageName, it.uid) }
        check(apps.size >= 2) { "Need two launchable apps with their own uid; found $apps" }
        return apps[0] to apps[1]
    }

    fun waitFor(timeoutMs: Long, what: String, poll: Long = 100, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) { if (condition()) return; SystemClock.sleep(poll) }
        runCatching { File(evidenceDir, "timeout-${System.currentTimeMillis()}-dumpsys.txt").writeText(shell("dumpsys connectivity")) }
        throw AssertionError("Timed out after ${timeoutMs}ms waiting for $what; state=${state()} ${vpn()}")
    }

    fun state(): TunnelState = app.tunnels.state.value

    /**
     * After an unanswered handshake TunnelManager schedules account recovery, which on the next
     * connect would send the synthetic login to the real AirVPN fallback bootstrap. Tests cancel
     * before the 10 s handshake deadline; this guard proves that stayed true.
     */
    fun assertNoRecoveryPending() {
        val field = TunnelManager::class.java.getDeclaredField("handshakeRefresh").apply { isAccessible = true }
        val policy = field.get(app.tunnels)
        val pending = policy.javaClass.getMethod("getRecoveryNeeded").invoke(policy) as Boolean
        check(!pending) { "Account recovery is pending; another connect would contact AirVPN. Aborting run." }
    }

    /** Leaves no VPN owned by this app, using the app's own disconnect path. */
    fun cleanup() {
        try {
            if (vpn().present || state() !is TunnelState.Down) {
                instrumentation.runOnMainSync { app.tunnels.disconnect() }
                waitFor(10_000, "VPN teardown") { !vpn().present && state() is TunnelState.Down }
            }
        } finally {
            // Always try release, but never hide a failed teardown as a passing run.
            WaveVpnService.instance?.releaseBlock()
            waitFor(5_000, "VPN release") { !vpn().present }
        }
    }

    // ── The real UI, driven through accessibility (as TalkBack or a user would) ──
    // Compose test idling never settles while the Connecting spinner animates, so the tests use
    // the accessibility tree instead of ComposeTestRule.

    private fun root(): AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow

    private fun walk(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        out += node
        for (i in 0 until node.childCount) walk(node.getChild(i), out)
    }

    private fun nodes(): List<AccessibilityNodeInfo> = mutableListOf<AccessibilityNodeInfo>().also { walk(root(), it) }

    /** Every visible text and content description on screen, in tree order. */
    fun screenText(): List<String> = nodes().flatMap { listOfNotNull(it.text?.toString(), it.contentDescription?.toString()) }
        .filter { it.isNotBlank() }

    fun hasLabel(label: String) = nodes().any { it.text?.toString() == label || it.contentDescription?.toString() == label }

    fun waitForLabel(label: String, timeoutMs: Long = 10_000) =
        waitFor(timeoutMs, "label '$label' on screen; screen=${screenText()}", 200) { hasLabel(label) }

    /** Clicks the clickable node carrying [label] (or its nearest clickable ancestor). */
    fun click(label: String): Boolean {
        val target = nodes().firstOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label }
            ?: return false
        var node: AccessibilityNodeInfo? = target
        while (node != null && !node.isClickable) node = node.parent
        return (node ?: target).performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun boundsOf(label: String): android.graphics.Rect? = nodes()
        .firstOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label }
        ?.let { n -> android.graphics.Rect().also(n::getBoundsInScreen) }

    /** A real touch at a screen position, for controls the accessibility tree does not expose. */
    fun tap(rect: android.graphics.Rect) {
        shell("input tap ${rect.centerX()} ${rect.centerY()}")
        instrumentation.waitForIdleSync()
    }

    fun clickOrFail(label: String) {
        waitForLabel(label)
        check(click(label)) { "Could not click '$label'; screen=${screenText()}" }
        instrumentation.waitForIdleSync()
    }

    /** Saves a screenshot and a text record under the app's external files (pulled by the runner script). */
    fun evidence(name: String, notes: String) {
        runCatching {
            instrumentation.uiAutomation.takeScreenshot()?.let { bmp ->
                File(evidenceDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        File(evidenceDir, "$name.txt").writeText(buildString {
            appendLine(notes)
            appendLine("state=${state()}")
            appendLine("blocking=${WaveVpnService.instance?.isBlocking}")
            appendLine("vpn=${vpn()}")
            appendLine("screen=${screenText()}")
            appendLine("tree=" + nodes().joinToString("\n  ", "\n  ") { n ->
                val r = android.graphics.Rect().also(n::getBoundsInScreen)
                "${n.className} text=${n.text} desc=${n.contentDescription} click=${n.isClickable} $r"
            })
            appendLine("log=" + app.log.entries.value.takeLast(8).joinToString(" | ") { "${it.level}: ${it.message}" })
        })
    }
}
