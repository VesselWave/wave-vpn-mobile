// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpn.ui

import java.util.Locale

fun countryName(code: String): String = runCatching {
    Locale("", if (code.equals("uk", true)) "GB" else code.uppercase()).displayCountry
}.getOrDefault(code.uppercase())
fun locationLabel(location: String, countryCode: String) = "$location, ${countryCode.uppercase()}"
data class Units(val bits: Boolean, val iec: Boolean)
private fun amount(value: Double, units: Units, rate: Boolean): String {
    var n = if (units.bits) value * 8 else value
    val base = if (units.iec) 1024 else 1000
    var i = 0
    while (n >= base && i < 4) { n /= base; i++ }
    val prefix = listOf("", "K", "M", "G", "T")[i] + if (units.iec && i > 0) "i" else ""
    val suffix = (if (units.bits) "b" else "B") + if (rate) "/s" else ""
    return String.format(Locale.ROOT, "%.2f %s%s", n, prefix, suffix)
}
fun formatTransfer(bytes: Long, units: Units) = amount(bytes.toDouble(), units, false)
fun formatSpeed(bytesPerSecond: Double, units: Units) = amount(bytesPerSecond, units, true)
fun formatUptime(totalSeconds: Long): String = String.format(Locale.ROOT, "%02d:%02d:%02d",
    totalSeconds / 3600, totalSeconds / 60 % 60, totalSeconds % 60)
