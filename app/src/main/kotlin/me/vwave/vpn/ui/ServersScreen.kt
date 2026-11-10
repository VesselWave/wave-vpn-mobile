package me.vwave.vpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.vwave.vpn.core.Scoring
import me.vwave.vpn.core.Server
import me.vwave.vpn.data.Target
import me.vwave.vpn.vpn.TunnelState

/** The desktop's server views and sort columns. */
private enum class ServerView(val label: String) { All("All"), Favorites("Favorites"), Countries("Countries") }

private enum class SortColumn(val label: String) { Name("Server"), Country("Location"), Load("Load"), Users("Users"), Ping("Ping"), Score("Score") }

@Composable
fun ServersScreen(vm: AppViewModel, connect: () -> Unit, onConnected: () -> Unit) {
    val manifest by vm.manifest.collectAsStateWithLifecycle()
    val pings by vm.pings.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val pinging by vm.pinging.collectAsStateWithLifecycle()
    val state by vm.tunnel.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var view by rememberSaveable { mutableStateOf(ServerView.All) }
    var sort by rememberSaveable { mutableStateOf(SortColumn.Score) }
    var ascending by rememberSaveable { mutableStateOf(true) }
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    val account by vm.account.collectAsStateWithLifecycle()
    val t = tokens

    val servers = manifest?.servers.orEmpty()
    val factors = manifest?.factors
    val scores = remember(servers, pings, factors) {
        if (factors == null) emptyMap()
        else Scoring.scores(servers, pings, factors)
    }
    val best = remember(servers, pings, factors) {
        factors?.let { Scoring.rank(servers, pings, it, allowFailedFallback = false) { s -> s.warningOpen == null }.firstOrNull() }
    }
    val connectedName = (state as? TunnelState.Connected)?.server?.name
    val q = query.trim().lowercase()
    val matches: (Server) -> Boolean = {
        q.isEmpty() || q in it.name.lowercase() || q in it.location.lowercase() ||
            q in it.countryCode.lowercase() || q in countryName(it.countryCode).lowercase()
    }
    val comparator: Comparator<Server> = remember(sort, ascending, pings, scores) {
        val base: Comparator<Server> = when (sort) {
            SortColumn.Name -> compareBy { it.name.lowercase() }
            SortColumn.Country -> compareBy<Server> { it.countryCode.lowercase() }.thenBy { it.location.lowercase() }
            SortColumn.Load -> compareBy { it.loadPercent }
            SortColumn.Users -> compareBy { it.users }
            // Unanswered pings sort last either way.
            SortColumn.Ping -> compareBy { pings[it.name]?.takeIf { ping -> ping > 0 } ?: Int.MAX_VALUE }
            SortColumn.Score -> compareBy { scores[it.name] ?: Double.POSITIVE_INFINITY }
        }
        val ordered = if (ascending) base else base.reversed()
        if (sort == SortColumn.Ping) compareBy<Server> { (pings[it.name] ?: -1) <= 0 }.then(ordered)
        else ordered
    }
    val pick = { target: Target -> vm.choose(target) }
    val idle = state is TunnelState.Down || state is TunnelState.Failed

    // Under very large text the controls would leave room for barely one server, so they scroll
    // away with the list instead of staying pinned above it.
    val scrollingHeader = LocalDensity.current.fontScale >= 1.5f
    val header = @Composable { start: Dp, end: Dp ->
        // ── Search and refresh ──
        Row(
            Modifier.fillMaxWidth().padding(start = start, end = end, top = Dimens.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchField(query, { query = it }, "Search servers, countries, cities", Modifier.weight(1f))
            IconButton(onClick = { vm.refresh() }) {
                LucideIcon(Lucide.Refresh, t.muted, 18.dp, contentDescription = "Refresh server list")
            }
        }

        // ── View and sort (sort wraps below the views when large text needs the room) ──
        FlowRow(
            Modifier.fillMaxWidth().padding(start = start, end = end, top = 12.dp, bottom = Dimens.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            SegmentTrack {
                for (option in ServerView.entries) Segment(option.label, view == option, { view = option })
            }
            if (view != ServerView.Countries) {
                SortMenu(sort, ascending) { column ->
                    if (column == sort) ascending = !ascending else {
                        sort = column
                        ascending = column != SortColumn.Users
                    }
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        if (!scrollingHeader) header(Dimens.md, Dimens.sm)

        PullToRefreshBox(
            isRefreshing = refreshing || pinging,
            onRefresh = { vm.refresh() },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = Dimens.sm, end = Dimens.sm, bottom = Dimens.lg),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // The list already insets by Dimens.sm on both sides.
                if (scrollingHeader) item(key = "header") { Column { header(Dimens.md - Dimens.sm, 0.dp) } }
                if (servers.isEmpty()) {
                    item {
                        EmptyState(
                            if (refreshing) "Loading servers…" else "No servers loaded",
                            if (refreshing) "Fetching the list from AirVPN." else if (account == null) "Sign in to AirVPN to fetch servers. You can configure settings first." else "Pull down to fetch the server list.",
                        )
                    }
                }
                when (view) {
                    ServerView.All -> {
                        if (q.isEmpty() && servers.isNotEmpty()) {
                            item(key = "best") {
                                BestRow(best, selected = settings.target == Target.Best) { pick(Target.Best) }
                            }
                        }
                        val shown = servers.filter(matches).sortedWith(comparator)
                        items(shown, key = { it.name }) { server ->
                            ServerRow(server, pings[server.name]?.takeIf { it > 0 }, settings, connectedName == server.name, pick, vm::toggleFavorite)
                        }
                        if (shown.isEmpty() && q.isNotEmpty()) item { NoMatch(query) }
                    }
                    ServerView.Favorites -> {
                        val shown = servers.filter { it.name in settings.favorites && matches(it) }.sortedWith(comparator)
                        items(shown, key = { it.name }) { server ->
                            ServerRow(server, pings[server.name]?.takeIf { it > 0 }, settings, connectedName == server.name, pick, vm::toggleFavorite)
                        }
                        if (shown.isEmpty() && servers.isNotEmpty()) {
                            item {
                                if (settings.favorites.isEmpty()) {
                                    EmptyState("No favorites yet", "Star a server to keep it here.")
                                } else {
                                    NoMatch(query)
                                }
                            }
                        }
                    }
                    ServerView.Countries -> {
                        val countries = servers
                            .groupBy { it.countryCode.lowercase() }
                            .filter { (code, list) -> q.isEmpty() || q in code || q in countryName(code).lowercase() || list.any(matches) }
                            .toSortedMap(compareBy { countryName(it) })
                        for ((code, list) in countries) {
                            val open = code in expanded || q.isNotEmpty()
                            item(key = "country-$code") {
                                CountryRow(
                                    code = code,
                                    servers = list,
                                    bestPing = list.mapNotNull { pings[it.name]?.takeIf { ping -> ping > 0 } }.minOrNull(),
                                    selected = settings.target == Target.Country(code),
                                    open = open,
                                    onClick = { pick(Target.Country(code)) },
                                    onToggle = { expanded = if (code in expanded) expanded - code else expanded + code },
                                )
                            }
                            if (open) {
                                items(list.filter(matches).sortedWith(comparator), key = { "in-$code-" + it.name }) { server ->
                                    ServerRow(server, pings[server.name]?.takeIf { it > 0 }, settings, connectedName == server.name, pick, vm::toggleFavorite, indent = true)
                                }
                            }
                        }
                        if (countries.isEmpty() && q.isNotEmpty()) item { NoMatch(query) }
                    }
                }
            }
        }

        // ── Connect to the selection ──
        if (idle && servers.isNotEmpty()) {
            Rule()
            PrimaryButton(
                text = when (val target = settings.target) {
                    Target.Best -> "Connect to best server"
                    else -> "Connect to ${targetTitle(target)}"
                },
                onClick = {
                    connect()
                    onConnected()
                },
                icon = Lucide.Power,
                modifier = Modifier.fillMaxWidth().padding(Dimens.md),
            )
        }
    }
}

@Composable
private fun SortMenu(sort: SortColumn, ascending: Boolean, onSort: (SortColumn) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val t = tokens
    Box {
        GhostButton(sort.label, { open = true }, trailingIcon = if (ascending) Lucide.ChevronUp else Lucide.ChevronDown)
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = t.surface,
            shape = RoundedCornerShape(Dimens.radiusSm),
        ) {
            for (column in SortColumn.entries) {
                val active = column == sort
                DropdownMenuItem(
                    text = { Text(column.label, style = MaterialTheme.typography.bodyLarge, color = if (active) t.text else t.muted) },
                    trailingIcon = {
                        if (active) LucideIcon(if (ascending) Lucide.ChevronUp else Lucide.ChevronDown, t.text, 16.dp)
                    },
                    onClick = {
                        onSort(column)
                        open = false
                    },
                )
            }
        }
    }
}

/** The desktop's server_row style: soft accent fill and border when selected. */
@Composable
private fun Modifier.serverRow(selected: Boolean, enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val t = tokens
    val shape = RoundedCornerShape(Dimens.radiusSm)
    return this
        .fillMaxWidth()
        .heightIn(min = 60.dp)
        .clip(shape)
        .background(if (selected) t.accentSoft else Color.Transparent)
        .border(1.dp, if (selected) t.accent.copy(alpha = 0.55f) else Color.Transparent, shape)
        .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .semantics { this.selected = selected }
}

@Composable
private fun BestRow(best: Server?, selected: Boolean, onClick: () -> Unit) {
    val t = tokens
    Row(
        Modifier.serverRow(selected, onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(Dimens.radiusSm)).background(t.accentSoft),
            contentAlignment = Alignment.Center,
        ) { LucideIcon(Lucide.Zap, t.accentFg, 18.dp) }
        Column(Modifier.weight(1f)) {
            Text("Best server", style = MaterialTheme.typography.titleMedium)
            Text(
                best?.let { "Now: ${it.name}, ${it.countryCode.uppercase()}" } ?: "Lowest load and latency, anywhere",
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
            )
        }
    }
}

@Composable
private fun CountryRow(
    code: String,
    servers: List<Server>,
    bestPing: Int?,
    selected: Boolean,
    open: Boolean,
    onClick: () -> Unit,
    onToggle: () -> Unit,
) {
    val t = tokens
    val openServers = servers.filter { it.isOpen }
    val load = if (openServers.isEmpty()) 0 else openServers.sumOf { it.loadPercent } / openServers.size
    Row(
        Modifier.serverRow(selected, onClick = onClick).padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val stacked = stackedMetrics()
        // The name already says it; under very large text the room goes to the name instead.
        if (!stacked) CodeTag(code)
        Column(Modifier.weight(1f)) {
            Text(countryName(code), style = MaterialTheme.typography.titleMedium, maxLines = if (stacked) 2 else 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${openServers.size} ${if (openServers.size == 1) "server" else "servers"}",
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
            )
            if (stacked) Metrics(bestPing, load, dim = openServers.isEmpty(), inline = true)
        }
        if (!stacked) Metrics(bestPing, load, dim = openServers.isEmpty())
        IconButton(onClick = onToggle) {
            LucideIcon(
                if (open) Lucide.ChevronUp else Lucide.ChevronDown,
                t.muted,
                18.dp,
                contentDescription = if (open) "Hide servers" else "Show servers",
            )
        }
    }
}

@Composable
private fun ServerRow(
    server: Server,
    ping: Int?,
    settings: me.vwave.vpn.data.Settings,
    connected: Boolean,
    pick: (Target) -> Unit,
    toggleFavorite: (String) -> Unit,
    indent: Boolean = false,
) {
    val t = tokens
    val enabled = server.isOpen
    val favorite = server.name in settings.favorites
    val warning = server.warningClosed ?: server.warningOpen
    Row(
        Modifier
            .padding(start = if (indent) 48.dp else 0.dp)
            .serverRow(settings.target == Target.Server(server.name), enabled) { pick(Target.Server(server.name)) }
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val stacked = stackedMetrics()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                if (connected) Dot(DotKind.On, 7.dp)
                Text(
                    server.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (enabled && warning == null) t.text else t.faint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                warning ?: locationLabel(server.location, server.countryCode),
                style = MaterialTheme.typography.bodySmall,
                color = if (warning != null) t.warning else t.muted,
                maxLines = if (stacked) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (stacked) Metrics(ping, server.loadPercent, dim = !enabled || warning != null, inline = true)
        }
        if (!stacked) Metrics(ping, server.loadPercent, dim = !enabled || warning != null)
        IconButton(onClick = { toggleFavorite(server.name) }) {
            LucideIcon(
                if (favorite) Lucide.StarFilled else Lucide.Star,
                if (favorite) t.warning else t.faint,
                18.dp,
                contentDescription = if (favorite) "Remove from favorites" else "Add to favorites",
            )
        }
    }
}

/** Under very large text, rows put their metrics below the name so names and places stay whole. */
@Composable
private fun stackedMetrics() = LocalDensity.current.fontScale >= 1.5f

/** Ping over load, right-aligned, in the desktop's quality colors; [inline] puts them in one row. */
@Composable
private fun Metrics(ping: Int?, load: Int, dim: Boolean, inline: Boolean = false) {
    val t = tokens
    val pingText = @Composable {
        Text(
            ping?.let { "$it ms" } ?: "—",
            style = MonoSmall,
            color = when {
                ping == null || dim -> t.faint
                else -> Quality.fromPing(ping).color
            },
        )
    }
    val loadRow = @Composable {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LoadBar(load, 40.dp)
            Text("$load%", style = MonoSmall, color = if (dim) t.faint else t.text, modifier = Modifier.widthIn(min = 36.dp), maxLines = 1)
        }
    }
    if (inline) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.md)) {
            pingText()
            loadRow()
        }
    } else {
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            pingText()
            loadRow()
        }
    }
}

/** Two-letter country code in a small bordered tag. */
@Composable
private fun CodeTag(code: String) {
    val shape = RoundedCornerShape(Dimens.radiusSm)
    Box(
        Modifier.sizeIn(minWidth = 36.dp, minHeight = 26.dp).clip(shape).border(1.dp, tokens.borderStrong, shape).padding(horizontal = Dimens.xs),
        contentAlignment = Alignment.Center,
    ) {
        Text(code.uppercase(), style = MonoSmall, color = tokens.muted)
    }
}

@Composable
private fun NoMatch(query: String) {
    Text(
        "No servers match “${query.trim()}”.",
        style = MaterialTheme.typography.bodyMedium,
        color = tokens.muted,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = Dimens.lg),
    )
}
