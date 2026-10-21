package me.vwave.vpn.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// The desktop's building blocks (src/gui/theme.rs and views/settings.rs), for touch.

/** 1dp hairline. */
@Composable
fun Rule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(tokens.border))
}

/** Bordered panel grouping related content (stats, settings list, log). */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(Dimens.radiusLg)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tokens.surface)
            .border(1.dp, tokens.border, shape),
        content = content,
    )
}

class GroupScope {
    internal val rows = mutableListOf<@Composable () -> Unit>()
    fun row(content: @Composable () -> Unit) {
        rows += content
    }
}

/** A titled group of rows inside one panel, separated by hairlines. */
@Composable
fun Group(title: String, description: String? = null, modifier: Modifier = Modifier, build: GroupScope.() -> Unit) {
    val rows = GroupScope().apply(build).rows
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.sm)) {
        Column(Modifier.padding(horizontal = Dimens.xs)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = tokens.muted)
            }
        }
        Panel {
            rows.forEachIndexed { index, row ->
                if (index > 0) Rule()
                row()
            }
        }
    }
}

/** Label (and optional hint) on the left, control on the right. */
@Composable
fun SettingRow(
    label: String,
    hint: String? = null,
    onClick: (() -> Unit)? = null,
    labelColor: Color = tokens.text,
    role: Role? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(role = role, onClick = onClick) else Modifier)
            .padding(horizontal = Dimens.md, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.md),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = labelColor)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = tokens.muted)
        }
        trailing()
    }
}

/** A row that opens something: value in muted text, then a chevron. */
@Composable
fun ValueRow(label: String, value: String?, hint: String? = null, onClick: () -> Unit) {
    SettingRow(label, hint, onClick = onClick) {
        if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 160.dp),
            )
        }
        LucideIcon(Lucide.ChevronRight, tokens.faint, 18.dp)
    }
}

@Composable
fun ToggleRow(label: String, hint: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingRow(label, hint, onClick = { onChange(!checked) }, role = Role.Switch) {
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = tokens.accent,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = tokens.borderStrong,
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

// ── Buttons ──────────────────────────────────────────────────────────────

private val ButtonShape = RoundedCornerShape(Dimens.radiusMd)

/** Filled accent button: the one primary action on a screen. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    height: Dp = 48.dp,
) {
    val t = tokens
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = ButtonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = t.accent,
            contentColor = t.onAccent,
            disabledContainerColor = t.accent.copy(alpha = 0.35f),
            disabledContentColor = t.onAccent.copy(alpha = 0.7f),
        ),
        elevation = null,
        contentPadding = PaddingValues(horizontal = 20.dp),
        modifier = modifier.height(height),
    ) {
        if (icon != null) {
            LucideIcon(icon, if (enabled) t.onAccent else t.onAccent.copy(alpha = 0.7f), 18.dp)
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Outlined neutral button. */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, height: Dp = 48.dp) {
    val t = tokens
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = ButtonShape,
        border = BorderStroke(1.dp, if (enabled) t.borderStrong else t.border),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = t.text, disabledContentColor = t.faint),
        contentPadding = PaddingValues(horizontal = 20.dp),
        modifier = modifier.height(height),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** Borderless low-emphasis button. */
@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, trailingIcon: ImageVector? = null, color: Color = tokens.muted) {
    TextButton(
        onClick = onClick,
        shape = ButtonShape,
        colors = ButtonDefaults.textButtonColors(contentColor = color),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        modifier = modifier.heightIn(min = 40.dp),
    ) {
        if (icon != null) {
            LucideIcon(icon, color, 15.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyMedium)
        if (trailingIcon != null) {
            Spacer(Modifier.width(4.dp))
            LucideIcon(trailingIcon, color, 14.dp)
        }
    }
}

/** Inline text link in the accent color. */
@Composable
fun LinkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, chevron: Boolean = true) {
    val t = tokens
    TextButton(
        onClick = onClick,
        shape = ButtonShape,
        colors = ButtonDefaults.textButtonColors(contentColor = t.accentFg),
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
        modifier = modifier.heightIn(min = 40.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
        if (chevron) {
            Spacer(Modifier.width(2.dp))
            LucideIcon(Lucide.ChevronRight, t.accentFg, 16.dp)
        }
    }
}

// ── Status ───────────────────────────────────────────────────────────────

enum class DotKind { On, Busy, Off, Warn }

/** Small round status dot. Busy is a ring, the rest are filled. */
@Composable
fun Dot(kind: DotKind, size: Dp = 8.dp) {
    val t = tokens
    val fill by animateColorAsState(
        when (kind) {
            DotKind.On -> t.accent
            DotKind.Busy -> Color.Transparent
            DotKind.Off -> t.faint
            DotKind.Warn -> t.warning
        },
        label = "dot",
    )
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(fill)
            .then(if (kind == DotKind.Busy) Modifier.border(1.5.dp, t.accentFg, CircleShape) else Modifier),
    )
}

/** Status pill: the connected chip gets the soft accent fill. */
@Composable
fun StatusChip(kind: DotKind, label: String) {
    val t = tokens
    val active = kind == DotKind.On
    val shape = CircleShape
    Row(
        Modifier
            .clip(shape)
            .background(if (active) t.accentSoft else Color.Transparent)
            .then(if (active) Modifier else Modifier.border(1.dp, if (kind == DotKind.Warn) t.warning else t.borderStrong, shape))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.sm),
    ) {
        Dot(kind)
        Text(
            label,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize),
            color = when (kind) {
                DotKind.On -> t.accentFg
                DotKind.Warn -> t.warning
                else -> t.muted
            },
        )
    }
}

/** Error banner, as on the desktop. */
@Composable
fun ErrorBanner(text: String, modifier: Modifier = Modifier) {
    val t = tokens
    val shape = RoundedCornerShape(Dimens.radiusMd)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(t.dangerSoft)
            .border(1.dp, t.danger.copy(alpha = 0.35f), shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.sm),
    ) {
        LucideIcon(Lucide.Alert, t.danger, 16.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = t.danger)
    }
}

// ── Segmented control ────────────────────────────────────────────────────

/** Track behind a row of [Segment]s. */
@Composable
fun SegmentTrack(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(Dimens.radiusMd)
    Row(
        modifier
            .clip(shape)
            .background(tokens.hover)
            .border(1.dp, tokens.border, shape)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        content = content,
    )
}

/** One option inside a segmented control. */
@Composable
fun RowScope.Segment(label: String, active: Boolean, onClick: () -> Unit, fill: Boolean = false) {
    val t = tokens
    val shape = RoundedCornerShape(Dimens.radiusSm)
    Box(
        Modifier
            .then(if (fill) Modifier.weight(1f) else Modifier)
            .heightIn(min = 36.dp)
            .clip(shape)
            .background(if (active) t.bg else Color.Transparent)
            .border(1.dp, if (active) t.borderStrong else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Tab
                selected = active
            }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (active) t.text else t.muted,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

// ── Server quality ───────────────────────────────────────────────────────

enum class Quality {
    Good, Ok, Bad;

    companion object {
        fun fromLoad(percent: Int) = if (percent > 80) Bad else if (percent > 50) Ok else Good
        fun fromPing(ms: Int) = if (ms < 50) Good else if (ms < 100) Ok else Bad
    }
}

val Quality.color: Color
    @Composable get() = when (this) {
        Quality.Good -> tokens.good
        Quality.Ok -> tokens.ok
        Quality.Bad -> tokens.bad
    }

/** Load bar in the quality color. */
@Composable
fun LoadBar(percent: Int, width: Dp = 56.dp) {
    val fraction = percent.coerceIn(0, 100) / 100f
    val shape = RoundedCornerShape(2.dp)
    Box(Modifier.width(width).height(4.dp).clip(shape).background(tokens.border)) {
        Box(Modifier.width(width * fraction).height(4.dp).clip(shape).background(Quality.fromLoad(percent).color))
    }
}

// ── Inputs ───────────────────────────────────────────────────────────────

@Composable
fun waveFieldColors(): TextFieldColors {
    val t = tokens
    return OutlinedTextFieldDefaults.colors(
        focusedBorderColor = t.accent,
        unfocusedBorderColor = t.border,
        disabledBorderColor = t.border,
        errorBorderColor = t.danger,
        focusedContainerColor = t.bg,
        unfocusedContainerColor = t.bg,
        disabledContainerColor = t.surface,
        focusedTextColor = t.text,
        unfocusedTextColor = t.text,
        focusedPlaceholderColor = t.faint,
        unfocusedPlaceholderColor = t.faint,
        focusedLabelColor = t.accentFg,
        unfocusedLabelColor = t.muted,
        cursorColor = t.accent,
    )
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyLarge) },
        leadingIcon = { LucideIcon(Lucide.Search, tokens.muted, 18.dp) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge,
        shape = RoundedCornerShape(Dimens.radiusMd),
        colors = waveFieldColors(),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = modifier,
    )
}

/** Centered two-line empty state. */
@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(vertical = Dimens.xl, horizontal = Dimens.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.xs),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = tokens.muted, textAlign = TextAlign.Center)
    }
}
