# Independent VPN traffic probe

For the repeatable scenario runner, example profiles and interruption recovery,
start with [RUNNING.md](RUNNING.md). The commands below describe the individual
probe/capture components for manual investigation.

Authored GPL-3.0-or-later QA tools; not part of the shipped app. No account or
private profile is needed by this probe. It uses a separate Android app UID and
foreground service, and never establishes its own VPN.

With JDK 17 and Android SDK platform 37.0 / build-tools 37.0.0:

```sh
ANDROID_HOME=/path/to/sdk ./tools/vpn-probe/build.sh /tmp/private-wave-probe
adb install /tmp/private-wave-probe/probe.apk
adb shell am start -n me.vwave.vpnprobe/.Probe --es phase connected
```

The build creates a local test key in the output directory. Reusing that output
preserves the probe's install identity. Uninstall an older probe with a different
signer first. Never commit output APKs, keys or raw observations.

Independent workers check IPv4 and IPv6 exits using HTTPS to ipify, direct HTTPS
to Cloudflare `1.1.1.1`, fresh system DNS resolution under `1-1-1-1.sslip.io`, and
TCP connections to Cloudflare's IPv4/IPv6 DNS-over-TLS port. The port-853 worker
proves TCP reachability only; it does not perform a TLS or DNS-over-TLS exchange.
A separate worker uses Cloudflare's HTTPS JSON DNS endpoint with a fresh
example.com query and validates the returned question and DNS status (NOERROR or
NXDOMAIN). New UDP
sockets send a marked DNS query to Cloudflare IPv4/IPv6 resolvers every 200 ms.
That marked traffic makes an upstream packet capture sensitive to routing gaps.
Additional workers explicitly select an underlying network for DNS lookup and
TCP connections, to exercise network-binding bypass attempts. A successful DNS
lookup alone does not establish which path carried it; examine the capture.
DNS queries use the reserved example.com domain. Network requests use public
third-party services and consume data. The probe's private results file includes
observed public IPs and network configuration; keep it private.

`capture.c` is a minimal Ethernet PCAP writer for a rooted ARM64 emulator. Set
`ANDROID_NDK_HOME` to build it alongside the APK. On a rooted test emulator:

```sh
adb push /tmp/private-wave-probe/capture /data/local/tmp/wave-capture
adb shell chmod 700 /data/local/tmp/wave-capture
adb shell /data/local/tmp/wave-capture wlan0 /data/local/tmp/wave.pcap 20
adb pull /data/local/tmp/wave.pcap /tmp/private-wave-probe/wave.pcap
python3 tools/vpn-probe/summarize.py /tmp/private-wave-probe/wave.pcap
```

The capture interface must be the actual underlying interface. Always run a
positive control with the VPN and blocking disabled on a dedicated test device:
marked packets must appear there. Empty captures are not protection evidence.
Repeat with VPN and blocking enabled while connecting, killing the VPN process,
switching servers, disconnecting and recovering. A non-rooted physical phone
requires an upstream capture point, such as a controlled gateway; do not root a
personal phone for this tool. IPv4/IPv6 success in the probe does not establish
native IPv6 capability on the underlying network.

The parser accepts Ethernet PCAP, ordinary IPv4 and IPv6 without extension
headers; it is scoped to these small probe packets. It counts port-1637 traffic
as expected WireGuard transport, not independent cryptographic validation. Use a
full packet analyzer for other ports, VLANs, extension headers or other traffic.
Raw captures may include other device traffic; keep them private and publish only
filtered findings. Check that IPv4/IPv6 application probes actually recover;
zero marked packets during an outage alone only proves observed blocking.

After collecting results, uninstall `me.vwave.vpnprobe`, remove the capture
binary/files from the device, and restore its original networking settings.

For an unrooted phone, capture at a controlled gateway filtered to that phone.
Record and restore its Wi-Fi configuration and the gateway's forwarding/firewall
settings, and arrange automatic gateway cleanup before changing routing. A
working IPv4 positive control does not establish native IPv6 capture coverage.
Account for other Android profiles: their VPN policies are independent, and
background packets from a whole-phone capture cannot automatically be attributed
to the owner-profile probe.

The summary includes all UDP DNS, TCP/53 SYN, TCP/853 SYN and TCP/443 SYN counts
in addition to the marked probes. Non-probe HTTPS may be legitimate VPN control
traffic, server latency probes, or traffic from another profile; investigate it
rather than treating all port-443 traffic as the direct-IP probe. Use the DoH
worker's privately recorded endpoint addresses when checking for DoH transport
outside WireGuard. Neither a DNS lookup nor a returned DoH answer by itself
proves routing protection.

DoH request format reference: [Cloudflare's JSON API documentation](https://developers.cloudflare.com/1.1.1.1/encryption/dns-over-https/make-api-requests/dns-json/).
No Cloudflare implementation code is included.
