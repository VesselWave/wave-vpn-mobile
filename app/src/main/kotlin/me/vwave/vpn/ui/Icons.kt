package me.vwave.vpn.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Line icons, the same set the desktop client draws (src/gui/icons.rs). Paths adapted from Lucide
 * (https://lucide.dev); see licenses/Lucide-LICENSE.txt. Circles and rects are written as paths.
 */
object Lucide {
    val Power = lucide("Power", "M12 2v10", "M18.4 6.6a9 9 0 1 1-12.77.04")
    val Globe = lucide(
        "Globe",
        CIRCLE_10,
        "M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20",
        "M2 12h20",
    )
    val Logs = lucide(
        "Logs",
        "M13 12h8", "M13 18h8", "M13 6h8", "M3 12h1", "M3 18h1", "M3 6h1", "M8 12h1", "M8 18h1", "M8 6h1",
    )
    val Sliders = lucide(
        "Sliders",
        "M10 5H3", "M12 19H3", "M14 3v4", "M16 17v4", "M21 12h-9", "M21 19h-5", "M21 5h-7", "M8 10v4", "M8 12H3",
    )
    val Lock = lucide("Lock", LOCK_BODY, "M7 11V7a5 5 0 0 1 10 0v4")
    val LockOpen = lucide("LockOpen", LOCK_BODY, "M7 11V7a5 5 0 0 1 9.9-1")
    val Refresh = lucide(
        "Refresh",
        "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8",
        "M21 3v5h-5",
        "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16",
        "M8 16H3v5",
    )
    val ArrowDown = lucide("ArrowDown", "M12 5v14", "m19 12-7 7-7-7")
    val ArrowUp = lucide("ArrowUp", "m5 12 7-7 7 7", "M12 19V5")
    val ChevronUp = lucide("ChevronUp", "m18 15-6-6-6 6")
    val ChevronDown = lucide("ChevronDown", "m6 9 6 6 6-6")
    val ChevronRight = lucide("ChevronRight", "m9 18 6-6-6-6")
    val ChevronLeft = lucide("ChevronLeft", "m15 18-6-6 6-6")
    val Alert = lucide("Alert", CIRCLE_10, "M12 8v4", "M12 16h.01")
    val Search = lucide("Search", "M3 11a8 8 0 1 0 16 0a8 8 0 1 0-16 0", "m21 21-4.34-4.34")
    val Check = lucide("Check", "M20 6 9 17l-5-5")
    val Zap = lucide("Zap", ZAP)
    val Eye = lucide(
        "Eye",
        "M2.062 12.348a1 1 0 0 1 0-.696 10.75 10.75 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.75 10.75 0 0 1-19.876 0",
        "M9 12a3 3 0 1 0 6 0a3 3 0 1 0-6 0",
    )
    val EyeOff = lucide(
        "EyeOff",
        "M10.733 5.076a10.744 10.744 0 0 1 11.205 6.575 1 1 0 0 1 0 .696 10.747 10.747 0 0 1-1.444 2.49",
        "M14.084 14.158a3 3 0 0 1-4.242-4.242",
        "M17.479 17.499a10.75 10.75 0 0 1-15.417-5.151 1 1 0 0 1 0-.696 10.75 10.75 0 0 1 4.446-5.143",
        "m2 2 20 20",
    )
    val Star = lucide("Star", STAR)
    val StarFilled = lucide("StarFilled", STAR, filled = true)
}

/** A Lucide icon at [size], tinted with [tint]. */
@Composable
fun LucideIcon(icon: ImageVector, tint: Color, size: Dp = 20.dp, contentDescription: String? = null, modifier: Modifier = Modifier) {
    Icon(icon, contentDescription, modifier.size(size), tint = tint)
}

private const val CIRCLE_10 = "M2 12a10 10 0 1 0 20 0a10 10 0 1 0-20 0"
private const val LOCK_BODY = "M5 11h14a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2z"
private const val ZAP =
    "M4 14a1 1 0 0 1-.78-1.63l9.9-10.2a.5.5 0 0 1 .86.46l-1.92 6.02A1 1 0 0 0 13 10h7a1 1 0 0 1 .78 1.63l-9.9 10.2a.5.5 0 0 1-.86-.46l1.92-6.02A1 1 0 0 0 11 14z"
private const val STAR =
    "M11.525 2.295a.53.53 0 0 1 .95 0l2.31 4.679a2.123 2.123 0 0 0 1.595 1.16l5.166.756a.53.53 0 0 1 .294.904l-3.736 3.638a2.123 2.123 0 0 0-.611 1.878l.882 5.14a.53.53 0 0 1-.771.56l-4.618-2.428a2.122 2.122 0 0 0-1.973 0L6.396 21.01a.53.53 0 0 1-.77-.56l.881-5.139a2.122 2.122 0 0 0-.611-1.879L2.16 9.795a.53.53 0 0 1 .294-.906l5.165-.755a2.122 2.122 0 0 0 1.597-1.16z"

private fun lucide(name: String, vararg paths: String, filled: Boolean = false): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        for (d in paths) {
            addPath(
                pathData = addPathNodes(d),
                fill = if (filled) SolidColor(Color.Black) else null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()
