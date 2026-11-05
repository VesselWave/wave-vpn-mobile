package me.vwave.vpn.ui

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.vwave.vpn.BuildConfig
import me.vwave.vpn.data.SplitMode

/** Same titled groups and row shapes as the desktop's Settings tab. */
@Composable
fun SettingsScreen(vm: AppViewModel, onSplitTunneling: () -> Unit) {
    val account by vm.account.collectAsStateWithLifecycle()
    val manifest by vm.manifest.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    val t = tokens

    val ports = manifest?.wireguardModes?.map { it.port }?.distinct()?.ifEmpty { null } ?: listOf(1637, 47107, 51820)
    val keys = account?.keys.orEmpty()
    val device = account?.key(settings.deviceKey)?.name
    // Devices are created in the AirVPN Client Area; only offer a choice when there is one,
    // or when the saved device no longer exists on the account.
    val showDevice = keys.size > 1 || (keys.isNotEmpty() && device == null)

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(Dimens.md),
        verticalArrangement = Arrangement.spacedBy(Dimens.lg),
    ) {
        Group("Account") {
            row { SettingRow("Signed in as") { Text(account?.login.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = t.accentFg) } }
            account?.expiration?.let { expiry ->
                row { SettingRow("Plan ends") { Text(expiry.substringBefore(' '), style = MaterialTheme.typography.bodyMedium, color = t.muted) } }
            }
            if (showDevice) row { ValueRow("Device", device, hint = "Each device has its own WireGuard key", onClick = { dialog = "device" }) }
            row { SettingRow("Sign out", labelColor = t.danger, onClick = { dialog = "logout" }) }
        }

        Group("Connection") {
            row {
                ValueRow("WireGuard port", "UDP ${settings.port}", hint = "Try another if your network blocks this one", onClick = { dialog = "port" })
            }
            row {
                ToggleRow("Reach servers over IPv6", "Only if your network has working IPv6", settings.ipv6Endpoint) { on ->
                    vm.updateSettings { it.copy(ipv6Endpoint = on) }
                    vm.reconnectIfActive()
                }
            }
            row {
                ValueRow(
                    "Split tunneling",
                    when (settings.splitMode) {
                        SplitMode.Off -> "Off"
                        SplitMode.Exclude -> "${settings.splitApps.size} bypass"
                        SplitMode.Include -> "${settings.splitApps.size} only"
                    },
                    hint = "Choose which apps use the VPN",
                    onClick = onSplitTunneling,
                )
            }
        }

        Group(
            "Always-on VPN",
            "Turn on Always-on VPN and Block connections without VPN for Wave VPN. Android enforces this protection, but DNS may escape during tunnel changes on affected Android versions.",
        ) {
            row { ValueRow("Open Android VPN settings", null, onClick = { context.startActivity(Intent(AndroidSettings.ACTION_VPN_SETTINGS)) }) }
        }

        Group("Display") {
            row {
                ValueRow("Speed and transfer unit", if (settings.unitBits) "bits" else "bytes", onClick = { dialog = "unit" })
            }
            row {
                ToggleRow("Binary units", "KiB/MiB instead of KB/MB", settings.unitIec) { on -> vm.updateSettings { it.copy(unitIec = on) } }
            }
        }

        Group("About") {
            row { SettingRow("Version") { Text(BuildConfig.VERSION_NAME, style = MonoSmall, color = t.muted) } }
            row {
                Text(
                    "Independent client for AirVPN, not made by AirVPN. Free software under the GPLv3; uses WireGuard (Apache 2.0).",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                    modifier = Modifier.padding(horizontal = Dimens.md, vertical = 14.dp),
                )
            }
        }
    }

    when (dialog) {
        "device" -> ChoiceDialog(
            title = "Device",
            options = keys.map { it.name },
            selected = device,
            label = { it },
            onDismiss = { dialog = null },
        ) { name ->
            vm.updateSettings { it.copy(deviceKey = name) }
            vm.reconnectIfActive()
        }
        "port" -> ChoiceDialog(
            title = "WireGuard port",
            options = ports,
            selected = settings.port,
            label = { "UDP $it" },
            onDismiss = { dialog = null },
        ) { port ->
            vm.updateSettings { it.copy(port = port) }
            vm.reconnectIfActive()
        }
        "unit" -> ChoiceDialog(
            title = "Speed and transfer unit",
            options = listOf(false, true),
            selected = settings.unitBits,
            label = { if (it) "bits" else "bytes" },
            onDismiss = { dialog = null },
        ) { bits -> vm.updateSettings { it.copy(unitBits = bits) } }
        "logout" -> AlertDialog(
            onDismissRequest = { dialog = null },
            containerColor = t.surface,
            shape = RoundedCornerShape(Dimens.radiusMd),
            title = { Text("Sign out?", style = MaterialTheme.typography.titleLarge) },
            // Dialog text doesn't scroll on its own; large text would cut the message off.
            text = {
                Text(
                    "This disconnects and removes your AirVPN login and keys from this phone.",
                    color = t.muted,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { dialog = null; vm.logout() }) { Text("Sign out", color = t.danger) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel", color = t.text) } },
        )
    }
}

/** Single choice from a short list, the phone's counterpart of the desktop's dropdown. */
@Composable
fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onDismiss: () -> Unit,
    onChoose: (T) -> Unit,
) {
    val t = tokens
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = t.surface,
        shape = RoundedCornerShape(Dimens.radiusMd),
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (option in options) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(option == selected, role = Role.RadioButton) {
                                onChoose(option)
                                onDismiss()
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = option == selected,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = t.accent, unselectedColor = t.borderStrong),
                        )
                        Text(label(option), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = t.text) } },
    )
}
