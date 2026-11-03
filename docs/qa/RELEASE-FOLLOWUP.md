# Physical release capture and DNS follow-up — 2026-10-22 UTC

> **In short:** Physical Pixel capture: the signed build carried probe traffic through the tunnel, but other DNS appeared during an outage, and an Android 16 emulator leaked app DNS while the VPN process was dead. No general leak-free claim.

The forwarded Pixel connection was restored and physical upstream capture is now
available. The production-signed release carried protected probe traffic in the
exercised physical IPv4 captures, while non-probe DNS still appeared during an
outage. The older Android 16 emulator also exposed fresh application
DNS names during process death despite Always-on VPN and blocking. A general
leak-free release claim remains unsupported.

This report supersedes the physical-access blocker and the negative application
DNS result in the [initial release report](RELEASE-DEVICE-TESTING.md). Filtered
results are in [the follow-up evidence](device-tests/2026-10-22-followup.json).
Raw captures, device/account details and probe results remain private.

## Artifact and physical setup

The initial physical matrix used exactly the previously tested minified release
APK, SHA-256
`7e25686199ec333a2733425c06ec04aa858768f26df304d7808c000188ff2ea2`.
Its certificate SHA-256 matches the [published production identity](../../SECURITY.md).
The existing production package had a different legacy certificate. After the
user explicitly authorized replacement, it was uninstalled and this APK was
installed as `me.vwave.vpn`. The development package was retained.

The phone is a Pixel 7 running GrapheneOS Android 17, incremental `2026101601`,
security patch `2026-10-05`. This is not a stock-Android Pixel result. Tests used
the owner profile; other existing profiles and VPN apps were not reconfigured.
The independent probe ran under a separate ordinary application UID.

A temporary static Wi-Fi gateway routed the phone through a controlled Linux
host on the same LAN. IPv4 forwarding, a phone-scoped masquerade rule and disabled
ICMP redirects made that host a capture point. `tcpdump` captured Ethernet traffic
filtered to the phone's IPv4 address. Automatic cleanup was armed before routing
changed. The phone was not rooted. No credentials or private profiles were sent
to the gateway.

The uplink has no usable native IPv6 route. Direct IPv6 probes failed with the
VPN off. IPv6 working inside WireGuard demonstrates tunnel IPv6 support, not
native IPv6 leak protection. These captures also do not cover cellular traffic
or every other profile's routing policy.

## Physical results

- **Positive control, VPN and blocking off:** the 12-second capture detected 54
  marked DNS packets, 12 system probe DNS packets, 11 DoT-port TCP attempts and
  11 direct-HTTPS TCP attempts on IPv4. All ordinary IPv4/HTTPS/DNS checks worked.
  Thus a protected empty result was compared with a functioning capture.
- **Steady signed-release connection:** IPv4, tunnel IPv6, fresh DNS, direct-IP
  HTTPS and DoT-port TCP checks worked. Explicit underlying-network TCP attempts
  failed. Explicit-network DNS returned answers without corresponding cleartext
  probe queries appearing at the gateway.
- **Induced production-process crash:** `am crash --user 0 me.vwave.vpn`
  restarted the process in about 2.7 seconds without opening Wave or tapping
  Connect. IPv4/IPv6/HTTPS temporarily failed and then recovered; DNS recovered.
  This is a system-induced Java crash, not a claim about naturally occurring
  memory pressure or a root-issued SIGKILL on this non-debuggable release.
- **Explicit disconnect and reconnect:** new requests failed while disconnected;
  manual Connect restored IPv4, IPv6 and DNS. Some requests completed in flight
  at the transition. Blocking stayed enabled.
- **Server switch:** the final artifact received a direct visible-row selection
  test from Mintaka to Saiph, with the resulting Connected Overview checked.
  Earlier search-driven attempts were excluded because the intended final
  active server was not established by their UI checks.

No marked DNS, system probe DNS, direct-HTTPS SYN or DoT-port SYN escaped onto the
captured IPv4 uplink in those protected windows; the final switch also kept real
DoH requests protected. No other cleartext UDP DNS was
seen in the steady, crash, disconnect or reconnect windows. Broader TCP/443 SYN counts are retained
in the evidence, including non-probe traffic during transitions. They have not
been equated with ordinary probe leakage or a device-wide pass. This is scoped
to the observed traffic and network;
it is not a claim that the whole phone is leak-free.

Earlier server-switch attempts did not establish the intended final UI state
and were excluded. An initial peer-outage attempt was also excluded after the
selected peer changed during the observation and its final UI snapshot failed.
Those attempts are not counted as passes.

## Physical peer outage and encrypted DNS

A verified 200-second drop rule restricted to the phone and its selected
WireGuard peer counted 1,878 dropped packets. No marked raw DNS, system probe DNS,
direct-IP HTTPS or DoT-port probe escaped. After removing the rule, the last
20-second window recorded eight IPv4, eight IPv6, seven fresh DNS and eight
direct-HTTPS successes without failures. The physical stale-status UI was not
verified because the snapshot was still on the server-search screen; this is not
a pass or a failure of the stale-status display. The initial emulator report
contains the separately verified stale-status result.

**Six non-probe cleartext DNS queries were observed at the physical gateway during
this outage window.** Hostnames are omitted from source-controlled evidence to
avoid disclosing personal application usage. The source UID/profile is unknown
on this unrooted phone. Another profile had its own active WireGuard VPN, and
profile policies were not changed. Neither a bypass of owner-profile lockdown
nor harmless attribution to another profile is established. This is an unresolved
finding preventing a whole-device claim, even though the owner-profile probes
remained protected.

The final application artifact (installed-APK hash independently rechecked) also
received captures with Private DNS Off,
Automatic and strict hostname mode (`one.one.one.one`). Fresh DNS and real DoH
requests returned valid responses in all three short windows; IPv4 and tunnel
IPv6 remained usable. DoH requests use Cloudflare's documented HTTPS JSON API
and validate the returned question and DNS status. DoT-port probes still check
TCP reachability only. No probe DNS, direct-IP HTTPS or DoH HTTPS connection was
observed outside WireGuard in these windows. The Automatic-mode transition did
produce one non-probe TCP/853 SYN to the secondary underlying resolver. It is
retained as an observed platform DNS-transport exception, not counted as a
probe leak or erased from the results. No cleartext DNS appeared in these three
windows. The original Private DNS mode and saved hostname were restored.

## Emulator DNS failure and attribution

The same original release APK was reinstalled on the API 36/Android 16 emulator
used in the initial report. Its older platform image and security patch remain
as documented there. Always-on VPN and Block connections without VPN were
verified enabled. Google Play services remained in its normal enabled state.

A follow-up capture covering three completed repeated SIGKILLs observed **five
fresh probe DNS queries to the VPN's DNS server on `wlan0`**, outside WireGuard.
It also observed four Google push-host DNS queries to the underlying resolver.
No marked raw-UDP probe escape was observed in that sample. The run had planned
eight kills; its fourth iteration found no restarted process after seven seconds.
The partial capture is retained as positive failure evidence, not a full pass of
the planned stress run.

Temporary `iptables` mangle counters matched outgoing UDP/53 on `wlan0`, with
individual rules for all installed package UIDs, selected system UIDs, and a
fallback. Only the **UID 0** rule counted packets (11 over the encompassing
counter window; nine DNS packets were in the capture). This attributes the
observed socket traffic to a root/system path; it does not identify which app
requested each Google hostname. Fresh probe names identify the application DNS
workload independently of socket ownership. No blocking rule was added to hide
the finding. The counters were removed afterward.

A separate approximately 79-second capture with emulator Private DNS explicitly
Off covered three SIGKILLs, with restart observations of approximately 1.2, 4.2
and 16.3 seconds. Five Google-host DNS packets still appeared on Wi-Fi; fresh
probe DNS escape was not observed in that sample. This does not establish that
Private DNS Off fixes the failure. The original mode was restored afterward.
The increasing restart delays also limit claims about rapid repeated failures.

The observations are consistent with a platform resolver/routing problem, but
do not prove an exact upstream defect. GrapheneOS documents
[stricter DNS blocking and a VPN DNS routing fix](https://grapheneos.org/releases)
and separately tracks [VPN transition issues](https://github.com/GrapheneOS/os-issue-tracker/issues/5273).
Those references provide context; they do not substitute for this app's captures
or justify extrapolating one platform's behavior to another. No upstream code
was copied and no root firewall workaround is shipped in Wave.

## Application wording and validation

The Settings explanation now explicitly says affected Android versions can
expose DNS during tunnel changes. The stale-handshake explanation describes
keeping the tunnel in place rather than claiming all traffic is blocked.
No tunnel or protocol implementation was changed in this follow-up.

Kotlin core tests, debug lint, debug build and the production-signed minified
release build pass. The final APK SHA-256 is
`3636d6522f23958a511c1b74c00f16cd0c7ac52e7dafb267304f2a3b0f6785b8`.
Its signature matches the published identity and
`zipalign -c -P 16 4` passes. No release was published.

## Final reboot, cleanup and remaining gates

The final signed artifact was rebooted on the Pixel after restoring its original
Wi-Fi/DHCP routing and Private DNS settings. The user unlocked it without opening
Wave or tapping Connect. Before launching Wave, ADB confirmed its process and
owner-profile VPN were already active, with Always-on VPN and blocking enabled.
A subsequent independent probe run recorded 20 IPv4, 20 IPv6, 18 fresh DNS,
20 direct-HTTPS and 20 DoH successes, without failures, over about 52 seconds.
This establishes recovery after unlock, not protection before first unlock.

Temporary probes were uninstalled from both devices. The Pixel's original DHCP,
Private DNS mode/saved hostname and display density were restored. The final
signed release remains installed and connected, with Always-on VPN and blocking
enabled. The development app remains installed. Other profiles' VPN settings were
not edited. The gateway's temporary nftables table and cleanup timer were removed;
IPv4 forwarding and redirect settings returned to their recorded original values.
The emulator's original Always-on/lockdown settings were restored, temporary
release/probe packages removed, capture files removed and ADB returned to non-root.
Private captures and logs remain outside both repositories for investigation.

Remaining release gates are the emulator application-DNS leak, attribution of
physical non-probe DNS and DNS-transport exceptions, capture on a native IPv6
uplink and cellular transitions, current stock-Android/module coverage, other
profiles/apps and browser DoH, pre-unlock traffic, natural memory pressure,
permission revocation and overnight reliability. The short DoH worker and
post-unlock reboot check do not close those broader scenarios. Narrowing the
wording does not fix the observed platform behavior or close the privacy gates.
