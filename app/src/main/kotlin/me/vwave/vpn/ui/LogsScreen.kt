package me.vwave.vpn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.vwave.vpn.data.LogLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The desktop's Logs tab: level filters, Clear, newest at the bottom. */
@Composable
fun LogsScreen(vm: AppViewModel) {
    val entries by vm.log.collectAsStateWithLifecycle()
    // Same defaults as the desktop: debug hidden.
    var shown by rememberSaveable { mutableStateOf(setOf(LogLevel.Info, LogLevel.Warn, LogLevel.Error)) }
    val time = remember { SimpleDateFormat("HH:mm:ss", Locale.ROOT) }
    val t = tokens

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(Dimens.md), verticalArrangement = Arrangement.spacedBy(Dimens.md)) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            SegmentTrack {
                for (level in LogLevel.entries) {
                    Segment(level.name, level in shown, { shown = if (level in shown) shown - level else shown + level })
                }
            }
            GhostButton("Clear", vm::clearLog)
        }

        val visible = entries.filter { it.level in shown }
        Panel(Modifier.weight(1f)) {
            if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        "No log entries",
                        if (entries.isNotEmpty()) "Entries exist but are hidden by the level filters."
                        else "What the app does will appear here as it happens.",
                    )
                }
            } else {
                // Reversed so the list stays pinned to the newest entry.
                LazyColumn(Modifier.fillMaxSize(), reverseLayout = true, contentPadding = PaddingValues(vertical = Dimens.sm)) {
                    items(visible.asReversed()) { entry ->
                        val levelColor = when (entry.level) {
                            LogLevel.Warn -> t.warning
                            LogLevel.Error -> t.danger
                            else -> t.faint
                        }
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                                Text(time.format(Date(entry.timeMillis)), style = MonoSmall.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize), color = t.faint)
                                Text(
                                    entry.level.name.uppercase(),
                                    style = MonoSmall.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize, fontWeight = FontWeight.Medium),
                                    color = levelColor,
                                )
                            }
                            Text(
                                entry.message,
                                style = MonoSmall,
                                color = when (entry.level) {
                                    LogLevel.Debug -> t.muted
                                    LogLevel.Error -> t.danger
                                    else -> t.text
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
