# Signed release and packet checks — 2026-10-22 UTC

> **In short:** First checks of the production-signed 0.2-dev build. Historical: the [follow-up](RELEASE-FOLLOWUP.md) supersedes its open items and found DNS escapes it didn't.

This is the initial round's historical record. The
[follow-up report](RELEASE-FOLLOWUP.md) supersedes its open physical-device blocker
and its negative application-DNS result: physical signed-release capture is now
available, and additional emulator process-death testing reproduced fresh probe
DNS queries outside the tunnel. No general leak-free result is claimed.

## Artifact and validation

- Package `me.vwave.vpn`, version `0.2-dev`, R8 minification and resource shrinking
  enabled; production release signer, not the debug certificate.
- APK SHA-256:
  `7e25686199ec333a2733425c06ec04aa858768f26df304d7808c000188ff2ea2`.
- Signer certificate SHA-256:
  `29950a5756876759b28984a0e6b44a276efd948f8e7008c3fff471d4ad418821`.
  `apksigner verify` passed and matched the published public certificate.
- Signed debug/release build and debug lint passed. Six offline core tests were
  subsequently rerun; the opt-in live test was skipped. Existing warnings remain.
- `zipalign -c -P 16 4` passed. No release was published.
- Production application code was not changed during this testing round. The
  added [independent QA tools](../../tools/vpn-probe/README.md) are outside app modules
  and are not shipped or required by either app's build.

[Filtered evidence](device-tests/2026-10-22-release-packets.json) contains packet
counts, capture hashes and runtime observations. Captures, private addresses,
credentials, test keys and private profiles are not included in source control.

## Capture method and positive control

An ordinary test app UID generated fresh system DNS, IPv4/IPv6 HTTPS exit checks,
direct-IP HTTPS, fresh marked UDP DNS packets every 200 ms, and TCP connection
attempts to DNS-over-TLS port 853. Port-853 probes check TCP reachability, not a
complete TLS/DNS exchange. Later probes also selected the underlying network
explicitly for DNS resolution and TCP connection attempts.

The Android 16/API 36 ARM64 emulator has security patch `2025-07-05`. Root access
was used only to capture the actual `wlan0` interface with an authored AF_PACKET
PCAP writer, send process signals and temporarily block peer traffic. The probe
and VPN still ran under their own application UIDs. This is an emulator result,
not physical upstream capture.

The emulator console capture produced empty captures despite active probes and
was discarded. The valid Wi-Fi positive control, with VPN/blocking off, observed
49 marked IPv4 DNS packets, 49 marked IPv6 DNS packets, 10 IPv4 and 10 IPv6 DoT-port
connection attempts, and five direct-IPv4 HTTPS connection attempts over about
ten seconds. Thus an empty protected probe result was not accepted without first
demonstrating that the capture could detect unprotected traffic.

## Signed-release results on Android 16

Always-on VPN and Block connections without VPN were enabled. Eleven valid
release captures covered steady operation, process death, disconnect/reconnect,
server switching, peer outage, force-stop, manual recovery, background-service
isolation and explicit underlying-network attempts. No marked probe DNS, DoT-port
connection attempt or direct-IP HTTPS connection attempt escaped in those
captures. This conclusion is limited to these probes and observation windows.

- **Steady connection:** IPv4, IPv6, DNS, direct-IP HTTPS and DoT-port TCP
  reachability worked through the tunnel.
- **SIGKILL:** the release process restarted in approximately 1.2 seconds and
  application traffic recovered. Temporary request failures were observed.
- **Disconnect/reconnect:** new application checks failed while disconnected;
  manual Connect restored traffic. Some requests completed in flight at the
  transition, retaining VPN exits.
- **Server switch:** selected Mintaka; both IP families and DNS continued working
  during the 30-second capture, without marked probe escape.
- **Peer outage with a usable underlying network:** a rule scoped to the release
  UID dropped WireGuard UDP traffic for 200 seconds. The rule's counters confirmed
  dropped packets before judging the result. The UI changed to “Not responding”
  and said traffic was blocked. Removing the rule restored the application
  checks; the full capture lasted approximately 230 seconds. An earlier attempt
  targeted the wrong UID and was excluded from pass evidence.
- **Force-stop:** traffic remained blocked. Automatic restart is not claimed for
  this intentional OS stop. Opening Wave and tapping Connect restored traffic.
- **Reboot:** the release process and VPN started automatically before Wave was
  opened. Subsequent application checks passed. This emulator has no unlock PIN;
  this does not establish protection before first unlock on a secured phone.
- **Explicit underlying network:** direct TCP socket binding was denied with
  `EPERM` for both IP families. Explicit-network DNS returned answers, but no
  corresponding cleartext probe DNS was observed on Wi-Fi in the capture window.

## DNS finding that prevents a blanket pass

Some transition captures included two cleartext DNS packets for a Google push
hostname, such as `mtalk.google.com`, `alt1-mtalk.google.com` or
`alt7-mtalk.google.com`, on the underlying Wi-Fi interface. These names are listed
in [Google's FCM network requirements](https://firebase.google.com/docs/cloud-messaging/network-configuration).
They were not generated by the probe. The originating UID was not captured, so
the responsible component and exact cause remain unconfirmed.

A SIGKILL capture with Google Play services temporarily disabled had no such
queries; another capture after restoring its default state also had none. This
does not prove attribution or that disabling a service fixes the issue. Google
Play services was restored afterward. No ordinary probe leak was reproduced,
including the explicit underlying-network attempts, but that does not erase the
observed non-probe DNS packets.

Do not claim that nothing leaves the whole device outside the VPN. The existing
absolute wording in the app's Always-on VPN explanation needs narrowing before
a privacy claim is published. Repeat with current platform modules and attribute
the background DNS; physical GrapheneOS/Pixel behavior must be checked separately.

## Additional release runtime coverage

- **Android 8.0/API 26, ARM64:** the same signed release installed, authenticated,
  established WireGuard and carried working IPv4, IPv6 and fresh DNS. The probe's
  Cloudflare direct-IP HTTPS check failed with `CertPathValidatorException: Trust
  anchor for certification path not found`. Other HTTPS exit checks worked.
  This old image's endpoint-specific trust failure is retained as a limitation;
  TLS verification was not bypassed.
- **Android 15/API 35, ARM64, 16 KB:** `getconf PAGE_SIZE` returned `16384`. The same
  signed release installed, authenticated, established the native WireGuard
  backend and passed IPv4, IPv6, DNS and direct-IP HTTPS checks. This is actual
  16 KB runtime coverage in addition to APK alignment validation.

These additional platforms received connection checks, not the entire Android
16 failure matrix. Their temporary test apps were uninstalled and the newly
created `Wave_QA_API26` and `Wave_QA_API35_16K` AVDs were stopped.

## Physical-device blocker and remaining work at the end of this initial round

Mac ADB port `127.0.0.1:5038` returned connection refused during this round. No
signed release was installed over the Pixel's existing production app, and no
Pixel settings were changed. Restore physical test laptop's forwarded ADB connection before
continuing physical signed-release and upstream-capture tests. A controlled
gateway is still needed for an unrooted phone; read-only devbox inspection was
performed, without enabling forwarding or changing its firewall.

Remaining gates include the background DNS finding, physical transition capture,
signed-release Pixel runtime, Private DNS/DoH interactions, other profiles and
applications, pre-unlock boot traffic, natural memory pressure, permission
revocation and overnight reliability. Earlier development-build Pixel results
remain in [the original device report](DEVICE-TESTING.md).
