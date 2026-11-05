// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpn.data

/** Ignore merely advertised INTERNET networks: cellular can exist without a usable route. */
internal data class RecoveryNetwork<T>(val network: T, val internet: Boolean,
    val nonVpn: Boolean, val validated: Boolean, val active: Boolean, val wifi: Boolean)

internal fun <T> recoveryNetwork(networks: List<RecoveryNetwork<T>>): T? = networks
    .filter { it.internet && it.nonVpn && it.validated }
    .sortedWith(compareByDescending<RecoveryNetwork<T>> { it.active }.thenByDescending { it.wifi })
    .firstOrNull()?.network
