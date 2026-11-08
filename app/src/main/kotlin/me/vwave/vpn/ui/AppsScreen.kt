package me.vwave.vpn.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.vwave.vpn.data.SplitMode

private class AppEntry(val packageName: String, val label: String, val icon: Drawable)

@Composable
fun AppsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    val apps by produceState<List<AppEntry>?>(null) {
        value = withContext(Dispatchers.IO) { launchableApps(context.packageManager, context.packageName) }
    }
    // Settings only take effect on the next connection, so reconnect once on the way out.
    val initial = remember { settings.splitMode to settings.splitApps }
    DisposableEffect(Unit) {
        onDispose { if (initial != (vm.settings.value.splitMode to vm.settings.value.splitApps)) vm.reconnectIfActive() }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = onBack) { LucideIcon(Lucide.ChevronLeft, tokens.text, 22.dp, contentDescription = "Back") }
            Text("Split tunneling", style = MaterialTheme.typography.titleLarge)
        }
        val list = apps
        val q = query.trim().lowercase()
        // Checked apps first so the current selection is visible at a glance.
        val shown = list.orEmpty()
            .filter { q.isEmpty() || q in it.label.lowercase() || q in it.packageName }
            .sortedBy { it.packageName !in settings.splitApps }
        // One list, so large text scrolls the explanations away instead of crowding out the apps.
        LazyColumn(Modifier.fillMaxSize()) {
            item(key = "intro") {
                Column {
                    SegmentTrack(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        val modes = listOf(SplitMode.Off to "All apps", SplitMode.Exclude to "Bypass", SplitMode.Include to "Only selected")
                        for ((mode, label) in modes) {
                            Segment(label, settings.splitMode == mode, { vm.updateSettings { it.copy(splitMode = mode) } }, fill = true)
                        }
                    }
                    Text(
                        when (settings.splitMode) {
                            SplitMode.Off -> "Every app's traffic goes through the VPN."
                            SplitMode.Exclude -> "Checked apps connect directly, outside the VPN."
                            SplitMode.Include -> "Only checked apps use the VPN. Everything else connects directly."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.muted,
                        modifier = Modifier.padding(16.dp),
                    )
                    // An empty selection would otherwise mean a tunnel for every app; Wave refuses it instead.
                    if (settings.noAppsSelected) {
                        Row(
                            Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val style = MaterialTheme.typography.bodyMedium
                            val firstLine = with(LocalDensity.current) { style.lineHeight.toDp() }
                            Box(Modifier.height(firstLine), contentAlignment = Alignment.Center) { LucideIcon(Lucide.Alert, tokens.warning, 16.dp) }
                            Text(
                                "No apps are checked yet. Wave won't connect until you check at least one.",
                                style = style,
                                color = tokens.warning,
                            )
                        }
                    }
                    Text(
                        "These settings apply to this Android profile. With Block connections without VPN enabled, apps outside the VPN are blocked.",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.muted,
                        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp),
                    )
                }
            }
            if (settings.splitMode == SplitMode.Off) return@LazyColumn

            item(key = "search") {
                SearchField(query, { query = it }, "Search apps", Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp))
            }
            if (list == null) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.padding(32.dp), color = tokens.accent, strokeWidth = 2.dp)
                    }
                }
                return@LazyColumn
            }
            items(shown, key = { it.packageName }) { app ->
                val checked = app.packageName in settings.splitApps
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(value = checked, role = Role.Checkbox) { selected ->
                            vm.updateSettings {
                                it.copy(splitApps = if (selected) it.splitApps + app.packageName else it.splitApps - app.packageName)
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(app.icon.toBitmap(96, 96).asImageBitmap(), contentDescription = null, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(app.label, style = MaterialTheme.typography.bodyLarge)
                        Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = tokens.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Checkbox(
                        checked = checked,
                        onCheckedChange = null,
                        colors = CheckboxDefaults.colors(checkedColor = tokens.accent, uncheckedColor = tokens.borderStrong, checkmarkColor = tokens.onAccent),
                    )
                }
            }
        }
    }
}

private fun launchableApps(pm: PackageManager, self: String): List<AppEntry> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .map { it.activityInfo.applicationInfo }
        .distinctBy { it.packageName }
        .filter { it.packageName != self }
        .map { AppEntry(it.packageName, pm.getApplicationLabel(it).toString(), pm.getApplicationIcon(it)) }
        .sortedBy { it.label.lowercase() }
}
