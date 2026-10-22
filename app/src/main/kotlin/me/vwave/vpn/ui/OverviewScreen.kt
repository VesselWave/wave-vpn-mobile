package me.vwave.vpn.ui

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import me.vwave.vpn.data.LogLevel
import me.vwave.vpn.data.Target
import me.vwave.vpn.vpn.TunnelState

@Composable
fun OverviewScreen(vm: AppViewModel, connect: () -> Unit, onChooseServer: () -> Unit, onLockSettings: () -> Unit) {
    val state by vm.tunnel.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val manifest by vm.manifest.collectAsStateWithLifecycle()
    val account by vm.account.collectAsStateWithLifecycle()
    val since by vm.connectedSince.collectAsStateWithLifecycle()
    val count by vm.connectionCount.collectAsStateWithLifecycle()
    val log by vm.log.collectAsStateWithLifecycle()
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
    val server = when (val s = state) {
        is TunnelState.Connected -> s.server
        is TunnelState.Stalled -> s.server
        is TunnelState.Connecting -> s.server
        else -> null
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp)) {
        Wordmark(Modifier.padding(top = Dimens.md))

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = Dimens.lg),
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
                PowerButton(kind, onClick = if (idle) connect else vm::disconnect)

                // ── Status chip and headline: where traffic goes (or will go) ──
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (state) {
                        TunnelState.Down, is TunnelState.Failed -> StatusChip(DotKind.Off, "Disconnected")
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
                            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.md)) {
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
                    (state as? TunnelState.Failed)?.let { ErrorBanner("Couldn't connect. ${it.message}") }
                }

                // ── Device picker (only before connecting, only with a choice to make) ──
                val keys = account?.keys.orEmpty()
                if (idle && keys.size > 1) {
                    Panel {
                        ValueRow("Device", account?.key(settings.deviceKey)?.name, onClick = { deviceDialog = true })
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
        }

        // ── Footer: session count, Always-on VPN (Android's kill switch; "Network lock" on the desktop) ──
        Rule()
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
            if (count > 0) {
                Text(
                    "$count ${if (count == 1) "connection" else "connections"} this session",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                )
            }
            Spacer(Modifier.weight(1f))
            GhostButton("Always-on VPN", onLockSettings, icon = Lucide.Lock, trailingIcon = Lucide.ChevronRight)
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

/** "Wave VPN", set like the desktop's wordmark. */
@Composable
fun Wordmark(modifier: Modifier = Modifier) {
    Row(modifier) {
        Text("Wave", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
        Text(" VPN", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = tokens.faint)
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
private fun PowerButton(kind: DotKind, onClick: () -> Unit) {
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
    Box(Modifier.size(196.dp), contentAlignment = Alignment.Center) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = fill,
            border = if (ring) BorderStroke(1.5.dp, t.accent.copy(alpha = 0.45f)) else null,
            shadowElevation = if (kind == DotKind.On) 10.dp else 0.dp,
            modifier = Modifier
                .size(176.dp)
                .semantics {
                    contentDescription = when (kind) {
                        DotKind.Off -> "Connect"
                        DotKind.Busy -> "Cancel connecting"
                        else -> "Disconnect"
                    }
                },
        ) {
            Box(contentAlignment = Alignment.Center) {
                LucideIcon(Lucide.Power, ink, 64.dp)
            }
        }
        if (kind == DotKind.Busy) {
            CircularProgressIndicator(Modifier.size(196.dp), color = t.accent, strokeWidth = 2.dp, trackColor = Color.Transparent)
        }
    }
}
