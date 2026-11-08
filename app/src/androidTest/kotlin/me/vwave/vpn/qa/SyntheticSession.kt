// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpn.qa

import android.content.Context
import me.vwave.vpn.core.Defaults
import me.vwave.vpn.core.parseManifest
import me.vwave.vpn.core.parseUser
import me.vwave.vpn.data.SessionVault
import me.vwave.vpn.data.SettingsStore
import me.vwave.vpn.data.SplitMode
import me.vwave.vpn.data.Target
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64

/**
 * A fabricated account and one-server manifest. Nothing here is a real credential:
 * keys are random per run and every address is in RFC 5737 TEST-NET-1 (192.0.2.0/24),
 * so WireGuard packets and the manifest's bootstrap URL go nowhere.
 *
 * Both documents are fresh (userAt/manifestAt = now, next_update = 1440 minutes), so the
 * app's ordinary refresh paths return without a request. The only remaining request path
 * is post-failed-handshake account recovery; ForumUi tests guard it (see Harness.assertNoRecoveryPending).
 */
object SyntheticSession {
    const val LOGIN = "wave-forum-qa"
    const val SERVER = "Qa-Synthetic"
    const val DEVICE = "qa-device"
    const val DEVICE_IPV4 = "10.128.0.2"
    const val ENDPOINT = "192.0.2.21"
    const val BOOTSTRAP = "http://192.0.2.10"
    /**
     * A second device whose DNS is the unspecified address. The profile passes Wave's and
     * WireGuard's config validation, but VpnService.Builder.addDnsServer rejects it inside
     * GoBackend's UP, which is exactly a "backend UP failure" after the blocking interface exists.
     * (An uninstalled "Only selected" package does not work on API 37: Android accepts it.)
     */
    const val BAD_DNS_DEVICE = "qa-device-unusable-dns"

    private fun key(): String = Base64.getEncoder().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

    fun userXml() = """<user login="$LOGIN" wg_public_key="${key()}" expirationdate="2099-01-01"><keys>""" +
        """<key name="$DEVICE" wg_private_key="${key()}" wg_preshared="${key()}" wg_ipv4="$DEVICE_IPV4/32" """ +
        """wg_ipv6="fd7d:76ee:e68f:a993::2/128" wg_dns_ipv4="10.128.0.1" wg_dns_ipv6="fd7d:76ee:e68f:a993::1"/>""" +
        """<key name="$BAD_DNS_DEVICE" wg_private_key="${key()}" wg_preshared="${key()}" wg_ipv4="10.128.0.3/32" """ +
        """wg_ipv6="" wg_dns_ipv4="0.0.0.0" wg_dns_ipv6=""/></keys></user>"""

    // The RSA key is the public AirVPN bootstrap key already shipped in Defaults (needed only to
    // pass manifest validation); the URL list points at TEST-NET so nothing reaches AirVPN.
    fun manifestXml() = """<manifest next_update="1440" auth_rsa_exponent="${Defaults.bootstrap.exponent}" """ +
        """auth_rsa_modulus="${Defaults.bootstrap.modulus}"><urls><url address="$BOOTSTRAP"/></urls>""" +
        """<modes><mode type="wireguard" port="1637" entry_index="0"/></modes><servers>""" +
        """<server name="$SERVER" country_code="nl" location="Test network" ips_entry="$ENDPOINT,192.0.2.22" """ +
        """bw="0" bw_max="1000" users="0" users_max="100" scorebase="0"/></servers></manifest>"""

    fun seed(context: Context) {
        val user = userXml(); val manifest = manifestXml()
        parseUser(user, LOGIN); parseManifest(manifest)
        val vault = SessionVault(context)
        vault.clear()
        val now = System.currentTimeMillis()
        vault.writeBootstrap(manifest)
        vault.write(JSONObject().put("login", LOGIN).put("password", "synthetic-not-a-password")
            .put("user", user).put("manifest", manifest).put("userAt", now).put("manifestAt", now))
        vault.writeBudget(0, 0)
        // No latency sweep during the run: probes would only hit TEST-NET, but keep logs quiet.
        check(context.getSharedPreferences("wave-probe-budget", Context.MODE_PRIVATE).edit()
            .putLong("nextSweep", now + 24 * 60 * 60_000L).commit())
        SettingsStore(context).apply {
            clear()
            update { it.copy(target = Target.Server(SERVER), deviceKey = DEVICE, splitMode = SplitMode.Off) }
        }
    }
}
