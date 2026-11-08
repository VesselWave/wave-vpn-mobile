// SPDX-License-Identifier: GPL-3.0-or-later
// Profile field mapping from Eddie Service.cs / AirVPNUser.getWireGuardProfile().
package me.vwave.vpn.core

import java.net.InetAddress
import java.util.Base64

/**
 * [onlySelected] records the user's "Only selected" choice separately from [includedApps]: an
 * empty IncludedApplications line would mean "every app", so that combination is refused.
 */
data class WgOptions(val port: Int = 1637, val entryIndex: Int = 2, val ipv6Endpoint: Boolean = false,
    val excludedApps: Set<String> = emptySet(), val includedApps: Set<String> = emptySet(),
    val onlySelected: Boolean = false)

/** "Only selected" with nothing checked. Never widened to a tunnel for every app. */
class NoAppsSelectedException : IllegalArgumentException(MESSAGE) {
    companion object { const val MESSAGE = "Only selected is on, but no apps are checked" }
}

/** Fails before any VPN change when the split-tunneling choice cannot be honored. */
fun requireAppRouting(onlySelected: Boolean, includedApps: Set<String>) {
    if (onlySelected && includedApps.isEmpty()) throw NoAppsSelectedException()
}

fun wgQuickConfig(account: Account, device: DeviceKey, server: Server, options: WgOptions): String {
    require(server.isOpen && options.port in 1..65535)
    require(options.excludedApps.isEmpty() || options.includedApps.isEmpty())
    require(!options.onlySelected || options.excludedApps.isEmpty())
    requireAppRouting(options.onlySelected, options.includedApps)
    fun key(value: String): String {
        require(Base64.getDecoder().decode(value).size == 32) { "Invalid WireGuard key" }
        return value
    }
    fun ip(value: String, cidr: Boolean): String {
        val parts = value.split('/')
        require(parts.size == if (cidr) 2 else 1) { "Invalid IP address" }
        val host = parts[0]
        require(host.matches(Regex("[0-9a-fA-F:.]+")) && ('.' in host || ':' in host)) { "IP literal required" }
        val parsed = InetAddress.getByName(host)
        if (cidr) require(parts[1].toInt() in 0..if (parsed.address.size == 4) 32 else 128)
        return value
    }
    fun apps(values: Set<String>) = values.sorted().joinToString(", ") {
        require(it.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))) { "Invalid package name" }; it
    }
    val host = ip(server.endpoint(options.entryIndex, options.ipv6Endpoint), false)
    val endpoint = if (':' in host) "[$host]" else host
    val addresses = listOf(device.ipv4, device.ipv6).filter(String::isNotBlank).map { ip(it, true) }
    val dns = listOf(device.dns4, device.dns6).filter(String::isNotBlank).map { ip(it, false) }
    require(addresses.isNotEmpty() && dns.isNotEmpty()) { "Device addresses and DNS are required" }
    return buildString {
        append("[Interface]\nPrivateKey = ${key(device.privateKey)}\n")
        append("Address = ${addresses.joinToString(", ")}\nDNS = ${dns.joinToString(", ")}\nMTU = 1320\n")
        if (options.excludedApps.isNotEmpty()) append("ExcludedApplications = ${apps(options.excludedApps)}\n")
        if (options.includedApps.isNotEmpty()) append("IncludedApplications = ${apps(options.includedApps)}\n")
        append("\n[Peer]\nPublicKey = ${key(account.publicKey)}\nPresharedKey = ${key(device.presharedKey)}\n")
        append("Endpoint = $endpoint:${options.port}\nAllowedIPs = 0.0.0.0/0, ::/0\nPersistentKeepalive = 15\n")
    }
}
