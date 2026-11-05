// SPDX-License-Identifier: GPL-3.0-or-later
// Schema reference: Eddie Service.cs, AirVPNManifest.java and AirVPNUser.java.
// Eddie copyright (C) 2014-2026 AirVPN; see docs/PROVENANCE.md.
package me.vwave.vpn.core

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

internal object Xml {
    fun root(text: String): Element {
        require(text.length <= 4 * 1024 * 1024) { "Document too large" }
        require(!text.contains("<!DOCTYPE", ignoreCase = true) && !text.contains("<!ENTITY", ignoreCase = true)) {
            "XML declarations are not supported"
        }
        val f = DocumentBuilderFactory.newInstance()
        f.isExpandEntityReferences = false
        runCatching { f.isXIncludeAware = false }
        // Android and the JVM support different parser features; the declaration rejection above
        // is mandatory even where a parser cannot implement one of these extra restrictions.
        for (feature in listOf("http://xml.org/sax/features/external-general-entities",
            "http://xml.org/sax/features/external-parameter-entities",
            "http://apache.org/xml/features/nonvalidating/load-external-dtd")) {
            runCatching { f.setFeature(feature, false) }
        }
        return f.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
        }.parse(InputSource(StringReader(text))).documentElement
    }
    fun children(e: Element, name: String): List<Element> = (0 until e.childNodes.length)
        .mapNotNull { e.childNodes.item(it) as? Element }.filter { it.tagName == name }
    fun descendants(e: Element, name: String): List<Element> = e.getElementsByTagName(name).let { n ->
        (0 until n.length).map { n.item(it) as Element }
    }
}

data class Factors(val speed: Double = 1.0, val load: Double = 1.0, val users: Double = 1.0)
data class WireguardMode(val port: Int, val entryIndex: Int)
data class Server(
    val name: String, val countryCode: String, val location: String,
    val entries: List<String>, val bandwidth: Long, val bandwidthMax: Long,
    val users: Int, val usersMax: Int, val scoreBase: Int,
    val warningOpen: String?, val warningClosed: String?,
) {
    val isOpen: Boolean get() = bandwidthMax > 0 && warningClosed == null
    val loadPercent: Int get() = if (bandwidthMax <= 0) 100 else
        (200.0 * kotlin.math.floor(bandwidth.toDouble() * 8 / 1_000_000) / bandwidthMax).toInt().coerceAtLeast(0)
    fun endpoint(index: Int, ipv6: Boolean): String = entries.filter { it.contains(':') == ipv6 }
        .getOrNull(index) ?: throw ProtocolException("Server has no matching entry address")
}
data class Notice(val text: String)
data class Manifest(val servers: List<Server>, val wireguardModes: List<WireguardMode>,
    val bootstrap: Bootstrap, val factors: Factors, val nextUpdateMinutes: Int,
    val messages: List<Notice> = emptyList())

fun parseManifest(text: String): Manifest {
    val root = Xml.root(text)
    require(root.tagName == "manifest") { "Expected manifest document" }
    val groups = Xml.descendants(root, "servers_group").associateBy { it.getAttribute("group") }
    val servers = Xml.descendants(root, "server").map { node ->
        fun attr(name: String): String = if (node.hasAttribute(name)) node.getAttribute(name)
            else groups[node.getAttribute("group")]?.getAttribute(name).orEmpty()
        Server(attr("name"), attr("country_code").lowercase(), attr("location"),
            attr("ips_entry").split(',').map(String::trim).filter(String::isNotEmpty),
            attr("bw").toLongOrNull() ?: 0, attr("bw_max").toLongOrNull() ?: 0,
            attr("users").toIntOrNull() ?: 0, attr("users_max").toIntOrNull() ?: 100,
            attr("scorebase").toIntOrNull() ?: 0,
            attr("warning_open").ifBlank { null }, attr("warning_closed").ifBlank { null })
    }
    fun factor(name: String) = root.getAttribute(name).toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: 1.0
    val modulus = root.getAttribute("auth_rsa_modulus")
    val exponent = root.getAttribute("auth_rsa_exponent")
    val urls = Xml.descendants(root, "url").map { it.getAttribute("address") }.filter(String::isNotBlank)
    require(modulus.isNotBlank() && exponent.isNotBlank() && urls.isNotEmpty()) { "Incomplete bootstrap manifest" }
    // Reject invalid advertised routing/key material before replacing the last usable snapshot.
    val bootstrap = Bootstrap(urls.distinct(), modulus, exponent)
    for (url in bootstrap.urls) {
        val uri = java.net.URI(url)
        require(uri.scheme in setOf("http", "https") && uri.host != null && uri.userInfo == null)
    }
    val rsaModulus = java.math.BigInteger(1, java.util.Base64.getDecoder().decode(modulus))
    val rsaExponent = java.math.BigInteger(1, java.util.Base64.getDecoder().decode(exponent))
    require(rsaModulus.bitLength() in 2048..8192 && rsaModulus.testBit(0))
    require(rsaExponent >= java.math.BigInteger.valueOf(3) && rsaExponent < rsaModulus && rsaExponent.testBit(0))
    RequestEnvelope(bootstrap).close()
    val modes = Xml.descendants(root, "mode").filter { it.getAttribute("type") == "wireguard" }.map {
        WireguardMode(it.getAttribute("port").toInt(), it.getAttribute("entry_index").toInt()).also { m ->
            require(m.port in 1..65535 && m.entryIndex in 0..15)
        }
    }
    return Manifest(servers, modes, bootstrap,
        Factors(factor("speed_factor"), factor("load_factor"), factor("users_factor")),
        (root.getAttribute("next_update").toIntOrNull() ?: 30).coerceIn(1, 1440),
        Xml.descendants(root, "message").map { Notice(it.getAttribute("text")) }.filter { it.text.isNotBlank() })
}

// Avoid generated toString() methods on objects carrying key material.
class DeviceKey(val name: String, val privateKey: String, val presharedKey: String,
    val ipv4: String, val ipv6: String, val dns4: String, val dns6: String)
class Account(val login: String, val expiration: String?, val publicKey: String, val keys: List<DeviceKey>) {
    fun key(name: String?): DeviceKey? = if (name.isNullOrBlank()) keys.firstOrNull() else keys.find { it.name == name }
}

fun parseUser(text: String, expectedLogin: String): Account {
    val r = Xml.root(text)
    require(r.tagName == "user") { "Expected account document" }
    if (r.getAttribute("error").isNotBlank() || r.getAttribute("message_action").isNotBlank())
        throw LoginException(r.getAttribute("message").ifBlank { "AirVPN rejected this login" })
    if (r.getAttribute("login") != expectedLogin) throw LoginException("AirVPN account identity mismatch")
    val keys = Xml.descendants(r, "key").map { k ->
        DeviceKey(k.getAttribute("name"), k.getAttribute("wg_private_key"), k.getAttribute("wg_preshared"),
            k.getAttribute("wg_ipv4"), k.getAttribute("wg_ipv6"),
            k.getAttribute("wg_dns_ipv4"), k.getAttribute("wg_dns_ipv6"))
    }
    require(keys.isNotEmpty()) { "Account contains no devices" }
    return Account(expectedLogin, r.getAttribute("expirationdate").ifBlank { null }, r.getAttribute("wg_public_key"), keys)
}

/** Eddie's base/load/user weighting, plus Wave's explicit latency tie-break preference. */
object Scoring {
    fun score(s: Server, ping: Int?, factors: Factors, usePing: Boolean = true): Double =
        if (!s.isOpen || s.warningOpen != null || (usePing && (ping != null && ping <= 0))) Double.POSITIVE_INFINITY else
            s.scoreBase / factors.speed + s.loadPercent / factors.load +
            100.0 * s.users / s.usersMax.coerceAtLeast(1) / factors.users +
            if (usePing) (ping ?: 2000).toDouble() else 0.0
    /** One fleet-relative neutral latency for both UI scores and connection ranking. */
    fun scores(servers: List<Server>, pings: Map<String, Int>, factors: Factors): Map<String, Double> {
        val measured = pings.values.filter { it > 0 }.sorted()
        val neutral = measured.getOrNull(measured.size / 2)
        return servers.associate { it.name to score(it, pings[it.name] ?: neutral, factors,
            neutral != null || pings[it.name] == -1) }
    }

    fun rank(servers: List<Server>, pings: Map<String, Int>, factors: Factors,
        allowFailedFallback: Boolean = true,
        include: ((Server) -> Boolean)? = null): List<Server> {
        val eligible = servers.filter { it.isOpen && (include?.invoke(it) ?: true) }
        val candidates = eligible.filter { pings[it.name] != -1 }
            .ifEmpty { if (include != null && allowFailedFallback) eligible else emptyList() }
        val scores = scores(servers, pings, factors)
        return candidates.sortedWith(compareBy<Server> { pings[it.name] == -1 }
            .thenBy { if (pings[it.name] == -1) score(it, null, factors, false) else scores[it.name] }
            .thenBy { pings[it.name] == null }.thenBy { it.name })
    }
}
