# Hotspot and tunneled IPv6 follow-up — 2026-10-24

The release remains blocked for an unrestricted privacy claim. The new hotspot
provides a reproducible external capture point and a working IPv6 Internet path;
it does not resolve the earlier stock-Android marked DNS escapes or establish
that every observed unmarked packet belongs to the owner-profile VPN.

## Network and evidence scope

lab host's unused AP-capable Wi-Fi radio first served a temporary bridged WPA2
hotspot. A phone-only IPv4 comparison completed for Wave and Proton. The remaining
Eddie attempt was interrupted and restored to prioritize the dual-stack network;
it is not a completed IPv4 hotspot comparison.

The home uplink has no native IPv6 default route. test relay's IPv6 HTTPS egress
was verified, then a temporary WireGuard tunnel supplied a separate network
namespace on lab host. Only the unused Wi-Fi radio moved into it. The phone obtained
IPv4 by DHCP and IPv6 ULA addresses by SLAAC; the VPS supplied NAT44/NAT66 egress.
Capture ran on that namespace's Wi-Fi interface **before the outer tunnel**.
This is a tunneled dual-stack uplink with NAT66, not a public IPv6 prefix assigned
to the phone, native ISP IPv6, IPv6-only/NAT64, or cellular capture.

The AP admitted one station with a generated private password. Exclusive-AP
capture retained rotating station MACs and changing IPv6 privacy addresses;
outgoing station packets were distinguished from the AP's own source MAC.
This mode is inappropriate for shared bridges/uplinks or multi-client APs.
Experimental DHCP/router advertisements stayed inside the test namespace.
lab host's wired management routes, persistent networking and household LAN DHCP/RA
were not reconfigured. The VPS used temporary narrow firewall rules and saved
IPv6 forwarding state. Both hosts had independent cleanup timers.

Unprotected phone controls required captured marked raw DNS/DoT for both families
and successful IPv6 application traffic. The first setup failed this prerequisite:
the router lifetime was 60 seconds, below the phone's observed minimum of 180.
The corrected network advertised 600 seconds and used MTU 1380 with TCP MSS
clamping in its own namespace. Both IPv4 and IPv6 controls then succeeded.
Failed setup attempts and interrupted runs remain in the evidence.

The signed Wave `0.2-dev` candidate and installed Eddie/Proton versions are the
same ones documented in [the comparison report](RELEASE-VPN-COMPARISON.md).
No production VPN code changed. The physical phone and policy/probe definitions
are also unchanged. Required IPv6 application traffic was retained as a functional
criterion, distinct from a marked escape or unassigned packet finding.

## Automation

[Running instructions](../tools/vpn-probe/RUNNING.md) cover the leased bridged AP,
isolated dual-stack VPS tunnel, independent cleanup, namespace capture, required
capture families and installed-app matrix. The new `wifi-reconnect` scenario
verifies Wi-Fi disabled, holds it off for ten seconds, re-enables it, and requires
return to the same BSSID. When a stronger saved household network won selection,
the test explicitly reselected the QA network through private ADB stdin. Results
record this selection; it is not an unattended Wi-Fi-selection claim. The VPN
app is not opened to pass radio recovery.

The tools are authored GPL-3.0-or-later QA material and standalone within Android.
They do not depend on the personal infrastructure checkout. SSH host keys remain
pinned by caller-supplied known-hosts files. Tor was rejected as a general test
uplink because its [SOCKS interface lacks UDP forwarding](https://spec.torproject.org/socks-extensions).

## Completed dual-stack comparison

All three apps established successful unprotected IPv4/IPv6 controls. No marked
probe DNS, raw-DNS or direct-probe transport escape was observed in their accepted
protected windows. This is sampled Pixel evidence, not a whole-device guarantee
or a resolution of the historical stock-platform failures.

- **Wave:** steady, disconnect, force-stop blocking and manual recovery passed.
  Reconnect, Wi-Fi recovery and induced crash restored the required traffic, but
  retained unsupported-packet findings. Wi-Fi recovery also contained **six**
  unassigned IPv4 DNS question packets. An earlier corrected-network reconnect
  attempt retained another unassigned-DNS finding; it is not erased by the later
  sample. Automatic crash recovery had successful IPv4, IPv6, DNS, direct HTTPS
  and DoH traffic after the action grace period.
- **Proton:** disconnect passed. Its protected working-traffic stages failed the
  required IPv6 application check despite a working unprotected IPv6 control.
  Wi-Fi recovery also contained **eight** unassigned IPv4 DNS question packets.
  Reconnect/radio recovery/force-stop retained unsupported forms. The crash window
  restored no required traffic after the action grace period; manual recovery
  restored IPv4 traffic but still failed the required IPv6 check. A blocked or
  unavailable IPv6 application check is not an IPv6 leak.
- **Eddie:** required traffic worked in steady, disconnect/reconnect and Wi-Fi
  recovery stages, but those windows retained unsupported forms. Wi-Fi recovery
  contained **eight** unassigned IPv4 DNS question packets. Crash recovery restored
  no required traffic after the grace period. The subsequent force-stop stage
  could not establish its connected-start prerequisite; it is not a protection
  pass. Remember me remained unselected, as in the earlier comparison.

The three radio-recovery captures share three DNS question names. Each includes
two question packets for GrapheneOS's public connectivity-check endpoint. The
[GrapheneOS FAQ](https://grapheneos.org/faq#default-connections) documents checks
on both underlying and VPN networks. This is evidence for common platform or
background traffic, not packet-to-UID proof or attribution of every remaining
question. Fixed HTTP/DoH endpoint names used by the independent probe were also
checked separately; they did not account for those radio-window questions.
Counts are question packets, including repeated questions, not independent leaks
or a severity ranking of six versus eight packets.

Independent offline inspection reassembled the sampled IPv4 UDP fragments:
Wave's 50 and Eddie's 175 complete datagrams all travelled **from the AP toward
the phone**, with payload structure consistent with WireGuard transport. Proton's
15 inspected datagrams were also inbound; 12 matched WireGuard transport
structure and three were other UDP/443 responses. These are not evidence of
outgoing plaintext. Radio captures additionally included IPv6 Hop-by-Hop/ICMPv6
143 packets consistent with [MLDv2 reports](https://www.rfc-editor.org/rfc/rfc3810.html).
The smaller test-network MTU can cause transport fragmentation. This header and
reassembly inspection does not authenticate ciphertext, attribute originating
apps, fully validate every extension header, or rewrite the original strict
unsupported-packet verdicts into passes.

## Remaining release gates

The hotspot closes the infrastructure gap for controlled dual-stack Wi-Fi
capture and automated radio recovery, while retaining actual failed results.
The following still need evidence or an explicit release scope decision:

- Historical marked DNS escapes on stock Android and the supported-platform policy.
- Originating app/profile/UID attribution of remaining unmarked traffic, with a
  documented policy for deliberate system connectivity checks.
- Native ISP IPv6, IPv6-only/NAT64 and captured cellular transitions; NAT66 uplink
  results cannot substitute for all of them.
- Physical pre-unlock boot, consent revocation, natural memory pressure,
  overnight reliability and remaining OEM/module coverage.
- The final versioned, signed/tagged release candidate and distribution gates.

See the [filtered manifest](device-tests/2026-10-24-hotspot-ipv6.json) for complete
and failed attempts, restoration state, capture/source hashes and the independent
fragment inspection. Raw PCAPs/configuration remain private under
`/tmp/wave-hotspot-20261024`. The prior stock and phone comparison remains in
[its separate manifest](device-tests/2026-10-24-comparison.json).

The final restoration check passed: original phone Wi-Fi/settings and Eddie's
notification permission were restored; QA networks/helpers and temporary local
AP/tunnel secrets were removed; all installed base/split APK hashes were unchanged;
all attempts had completed restoration; lab host's radio returned to managed/down,
unbridged, unnumbered and MTU 1500; its wired routes and Proxmox firewalls remained
intact; test relay's QA interface/rules disappeared, IPv6 forwarding returned to its
initial value and IPv6 HTTPS still worked. The reusable command also passed a
fresh prepare/AP/IPv4+IPv6 reachability/stop cycle. The installed `iw` and
`dnsmasq-base` test dependencies remain on lab host for reuse. No permanent hotspot
service or tunnel remains. Fifty-seven offline QA tests passed; Linux was not
changed. No release was published.
