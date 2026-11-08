package me.vwave.vpn.ui

import android.content.Intent
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import me.vwave.vpn.data.LogLevel
import me.vwave.vpn.data.Target
import me.vwave.vpn.vpn.NO_APPS_FAILURE
import me.vwave.vpn.vpn.TunnelState
import me.vwave.vpn.vpn.VpnInterruption

@Composable
fun OverviewScreen(
    vm: AppViewModel,
    connect: () -> Unit,
    onChooseServer: () -> Unit,
    onLockSettings: () -> Unit,
    onSplitTunneling: () -> Unit,
) {
    val state by vm.tunnel.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val manifest by vm.manifest.collectAsStateWithLifecycle()
    val account by vm.account.collectAsStateWithLifecycle()
    val since by vm.connectedSince.collectAsStateWithLifecycle()
    val count by vm.connectionCount.collectAsStateWithLifecycle()
    val log by vm.log.collectAsStateWithLifecycle()
    val interruption by vm.interruption.collectAsStateWithLifecycle()
    val blockingTraffic by vm.blockingTraffic.collectAsStateWithLifecycle()
    var deviceDialog by rememberSaveable { mutableStateOf(false) }
    val t = tokens
    val units = Units(settings.unitBits, settings.unitIec)

    // Ticks the uptime in the status chip.
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(since) {
        while (since != null) {
            now = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }

    val idle = state is TunnelState.Down || state is TunnelState.Failed
    // Not connected, yet Wave's blocking interface still holds every app's traffic. This is never
    // "Disconnected": the user has no internet until Wave connects or the block is released.
    val blocked = idle && blockingTraffic
    // Re-read on return from Android settings, where the user may have turned the block off.
    var resumes by remember { mutableLongStateOf(0L) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumes++ }
    val lockdown = remember(blocked, resumes) { blocked && vm.lockdownEnabled() }
    val server = when (val s = state) {
        is TunnelState.Connected -> s.server
        is TunnelState.Stalled -> s.server
        is TunnelState.Connecting -> s.server
        else -> null
    }

    // Wordmark on top, footer at the bottom, the action centered between them. When large text
    // makes that taller than the screen, the whole page scrolls rather than squeezing the middle.
    BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Wordmark(Modifier.padding(top = Dimens.md))

            Column(
                Modifier.fillMaxWidth().padding(vertical = Dimens.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Dimens.lg),
            ) {
                // ── The one action: connect, cancel or disconnect ──
                val kind = when (state) {
                    TunnelState.Down, is TunnelState.Failed -> DotKind.Off
                    is TunnelState.Connecting -> DotKind.Busy
                    is TunnelState.Stalled -> DotKind.Warn
                    is TunnelState.Connected -> DotKind.On
                }
                PowerButton(kind, onClick = if (idle) connect else vm::disconnect, blocked = blocked)

                // ── Status chip and headline: where traffic goes (or will go) ──
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (state) {
                        TunnelState.Down, is TunnelState.Failed ->
                            if (blocked) StatusChip(DotKind.Warn, "Blocking traffic") else StatusChip(DotKind.Off, "Disconnected")
                        is TunnelState.Connecting -> StatusChip(DotKind.Busy, "Connecting…")
                        is TunnelState.Stalled -> StatusChip(DotKind.Warn, "Not responding")
                        is TunnelState.Connected -> StatusChip(
                            DotKind.On,
                            since?.let { "Connected · ${formatUptime((now - it).coerceAtLeast(0) / 1000)}" } ?: "Connected",
                        )
                    }
                    if (server != null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(server.name, style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
                            Text(
                                "${server.location}, ${countryName(server.countryCode)}",
                                style = MaterialTheme.typography.bodyLarge,
                                color = t.muted,
                                textAlign = TextAlign.Center,
                            )
                        }
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(targetTitle(settings.target), style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.md, Alignment.CenterHorizontally)) {
                                LinkButton(
                                    if (settings.target == Target.Best) "Choose a server" else "Change server",
                                    onClick = onChooseServer,
                                )
                                if (settings.target != Target.Best && idle) {
                                    LinkButton("Use best server", onClick = { vm.choose(Target.Best) }, chevron = false)
                                }
                            }
                        }
                    }
                    val activity = log.lastOrNull()?.takeIf { state is TunnelState.Connecting && it.level != LogLevel.Debug }
                    when {
                        activity != null -> Text(
                            activity.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = t.muted,
                            textAlign = TextAlign.Center,
                        )
                        state is TunnelState.Stalled -> Text(
                            "The server stopped answering. Wave is keeping the tunnel in place while waiting for a response.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = t.muted,
                            textAlign = TextAlign.Center,
                        )
                    }
                    // The "No apps selected" panel below explains that refusal and links to the fix.
                    (state as? TunnelState.Failed)?.takeUnless { it.message == NO_APPS_FAILURE && settings.noAppsSelected }
                        ?.let { ErrorBanner("Couldn't connect. ${it.message}") }
                }

                // ── Traffic still blocked after a failure or disconnect: say so, and how to release it ──
                if (blocked) BlockingPanel(lockdown, onStopBlocking = vm::disconnect)

                // ── Android refused or took back the VPN: say so, and where to fix it ──
                interruption?.takeIf { idle }?.let { InterruptionPanel(it) }

                // ── Only selected with nothing checked: Connect refuses rather than route every app ──
                if (idle && settings.noAppsSelected) {
                    NoticePanel(
                        "No apps selected",
                        "Split tunneling is set to Only selected, but no apps are checked, so Wave won't connect. " +
                            "Check the apps that should use the VPN, or switch to All apps.",
                        "Choose apps",
                        onClick = onSplitTunneling,
                    )
                }

                // ── Device picker (only before connecting, only with a choice to make or a stale selection) ──
                val keys = account?.keys.orEmpty()
                val device = account?.key(settings.deviceKey)?.name
                if (idle && (keys.size > 1 || (keys.isNotEmpty() && device == null))) {
                    Panel {
                        ValueRow("Device", device, onClick = { deviceDialog = true })
                    }
                }

                // ── Live stats ──
                val s = stats
                if (s != null && (state is TunnelState.Connected || state is TunnelState.Stalled)) {
                    Panel {
                        StatRow(
                            Stat(Lucide.ArrowDown, "Download", formatSpeed(s.rxRate, units)),
                            Stat(Lucide.ArrowUp, "Upload", formatSpeed(s.txRate, units)),
                        )
                        Rule()
                        StatRow(
                            Stat(Lucide.ArrowDown, "Received", formatTransfer(s.rxBytes, units)),
                            Stat(Lucide.ArrowUp, "Sent", formatTransfer(s.txBytes, units)),
                        )
                    }
                }

                // ── Notices from AirVPN ──
                manifest?.messages?.takeIf { it.isNotEmpty() }?.let { messages ->
                    Panel {
                        messages.forEachIndexed { index, message ->
                            if (index > 0) Rule()
                            Text(
                                message.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = t.muted,
                                modifier = Modifier.padding(Dimens.md),
                            )
                        }
                    }
                }
            }

            // ── Footer: session count, Always-on VPN (Android's kill switch; "Network lock" on the desktop) ──
            Column {
                Rule()
                // Keep the action readable when large text leaves no room beside the count.
                FlowRow(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    horizontalArrangement = if (count > 0) Arrangement.SpaceBetween else Arrangement.End,
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    if (count > 0) {
                        Text(
                            "$count ${if (count == 1) "connection" else "connections"} this session",
                            style = MaterialTheme.typography.bodySmall,
                            color = t.muted,
                        )
                    }
                    GhostButton("Always-on VPN", onLockSettings, icon = Lucide.Lock, trailingIcon = Lucide.ChevronRight)
                }
            }
        }
    }

    if (deviceDialog) {
        ChoiceDialog(
            title = "Device",
            options = account?.keys?.map { it.name }.orEmpty(),
            selected = account?.key(settings.deviceKey)?.name,
            label = { it },
            onDismiss = { deviceDialog = false },
        ) { name -> vm.updateSettings { it.copy(deviceKey = name) } }
    }
}

/**
 * Stays until the next Connect or Disconnect. Android doesn't tell apps which VPN is Always-on,
 * so the copy names the likely causes instead of guessing one.
 */
@Composable
private fun InterruptionPanel(interruption: VpnInterruption) {
    val context = LocalContext.current
    val (title, body) = when (interruption) {
        VpnInterruption.PermissionDenied -> "Android didn't let Wave connect" to
            "If another app is set as Always-on VPN, Android blocks other VPNs until you turn that off. " +
            "Otherwise, tap Connect and allow Wave when Android asks."
        VpnInterruption.Revoked -> "Android turned off Wave's VPN" to
            "Another VPN app started, or the VPN was disconnected in Android settings. Tap Connect to reconnect."
    }
    NoticePanel(title, body, "Open VPN settings", onClick = { context.startActivity(Intent(AndroidSettings.ACTION_VPN_SETTINGS)) })
}

/**
 * Wave's blocking interface outlived the connection. Without Android lockdown, Wave holds the
 * block itself and can release it. With lockdown, Android would keep blocking anyway, so the
 * only honest release is in Android's settings.
 */
@Composable
private fun BlockingPanel(lockdown: Boolean, onStopBlocking: () -> Unit) {
    val context = LocalContext.current
    if (lockdown) {
        NoticePanel(
            "Apps have no internet",
            "Block connections without VPN is on for Wave, so Android blocks all traffic until Wave connects. " +
                "To go online without the VPN, turn it off in Android's VPN settings.",
            "Open VPN settings",
            onClick = { context.startActivity(Intent(AndroidSettings.ACTION_VPN_SETTINGS)) },
        )
    } else {
        NoticePanel(
            "Apps have no internet",
            "Wave is still blocking all traffic so nothing leaks outside the VPN. " +
                "Tap Connect to try again, or stop blocking to go online without the VPN.",
            "Stop blocking",
            chevron = false,
            onClick = onStopBlocking,
        )
    }
}

/** A warning with one way out: the Overview's shared shape for "something needs you". */
@Composable
private fun NoticePanel(title: String, body: String, action: String, chevron: Boolean = true, onClick: () -> Unit) {
    val t = tokens
    Panel {
        Column(
            Modifier.padding(start = Dimens.md, end = Dimens.md, top = 14.dp, bottom = Dimens.xs),
            verticalArrangement = Arrangement.spacedBy(Dimens.xs),
        ) {
            // Top-aligned so the icon stays beside the first line when large text wraps the title.
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                val titleStyle = MaterialTheme.typography.titleSmall
                val firstLine = with(LocalDensity.current) { titleStyle.lineHeight.toDp() }
                Box(Modifier.height(firstLine), contentAlignment = Alignment.Center) { LucideIcon(Lucide.Alert, t.warning, 16.dp) }
                Text(title, style = titleStyle)
            }
            Text(body, style = MaterialTheme.typography.bodyMedium, color = t.muted)
            LinkButton(action, onClick = onClick, chevron = chevron)
        }
    }
}

/** "Wave VPN", set like the desktop's wordmark. */
@Composable
fun Wordmark(modifier: Modifier = Modifier) {
    // A logo, not reading text: it stops growing at 1.5× to leave room for content.
    CapFontScale(1.5f) {
        Row(modifier) {
            Text("Wave", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
            Text(" VPN", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = tokens.muted)
        }
    }
}

/** What Connect will connect to, as a headline. */
fun targetTitle(target: Target): String = when (target) {
    Target.Best -> "Best server"
    is Target.Country -> countryName(target.code)
    is Target.Server -> target.name
}

private class Stat(val icon: ImageVector, val label: String, val value: String)

@Composable
private fun StatRow(left: Stat, right: Stat) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = Dimens.md, vertical = 14.dp)) {
        StatCell(left, Modifier.weight(1f))
        Box(Modifier.padding(horizontal = Dimens.md).width(1.dp).fillMaxHeight().background(tokens.border))
        StatCell(right, Modifier.weight(1f))
    }
}

@Composable
private fun StatCell(stat: Stat, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.xs)) {
            LucideIcon(stat.icon, tokens.muted, 12.dp)
            Text(stat.label, style = MaterialTheme.typography.labelSmall, color = tokens.muted)
        }
        Text(stat.value, style = MonoLarge, maxLines = 1)
    }
}

/**
 * The big round power button. Off: a ring in the accent; connected: filled accent; not
 * responding: filled amber; connecting: a spinner around it (tap to cancel).
 */
@Composable
private fun PowerButton(kind: DotKind, onClick: () -> Unit, blocked: Boolean = false) {
    val t = tokens
    val fill by animateColorAsState(
        when (kind) {
            DotKind.On -> t.accent
            DotKind.Warn -> t.warning
            else -> t.surface
        },
        label = "power-fill",
    )
    val ink by animateColorAsState(if (kind == DotKind.On || kind == DotKind.Warn) t.onAccent else t.accentFg, label = "power-ink")
    val ring = kind == DotKind.Off || kind == DotKind.Busy
    // Under very large text, a smaller button keeps the server name in view without scrolling.
    val compact = LocalDensity.current.fontScale >= 1.5f
    val outer = if (compact) 148.dp else 196.dp
    Box(Modifier.size(outer), contentAlignment = Alignment.Center) {
        // Drawn first and kept out of the accessibility tree: Compose drops nodes that a later
        // sibling fully covers, which used to hide "Cancel connecting" from TalkBack and Switch Access.
        if (kind == DotKind.Busy) {
            CircularProgressIndicator(
                Modifier.size(outer).clearAndSetSemantics {},
                color = t.accent,
                strokeWidth = 2.dp,
                trackColor = Color.Transparent,
            )
        }
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = fill,
            border = if (ring) BorderStroke(1.5.dp, t.accent.copy(alpha = 0.45f)) else null,
            shadowElevation = if (kind == DotKind.On) 10.dp else 0.dp,
            modifier = Modifier
                .size(outer - 20.dp)
                .semantics {
                    contentDescription = when (kind) {
                        DotKind.Off -> "Connect"
                        DotKind.Busy -> "Cancel connecting"
                        else -> "Disconnect"
                    }
                    stateDescription = when {
                        kind == DotKind.Busy -> "Connecting"
                        kind == DotKind.Warn -> "Not responding"
                        kind == DotKind.On -> "Connected"
                        blocked -> "Blocking traffic"
                        else -> "Disconnected"
                    }
                },
        ) {
            Box(contentAlignment = Alignment.Center) {
                LucideIcon(Lucide.Power, ink, if (compact) 52.dp else 64.dp)
            }
        }
    }
}
