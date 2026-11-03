# External emulator VPN comparison — 2026-10-24

These results **do not establish that all Android VPNs leak**. The fresh external
capture observed no marked application-probe DNS escape in Wave, Eddie or Proton
across one accepted steady, disconnect, reconnect and crash window per app.
It did confirm unmarked cleartext DNS during Wave's crash and Proton's disconnect.
Their originating UID remains unassigned. Historical marked DNS escapes in Wave
and Eddie remain valid evidence; a short new sample does not erase a race.
The unrestricted privacy release gate remains blocked.

[Filtered evidence](device-tests/2026-10-24-external-emulator.json) retains all
attempt reports, external per-window summaries and hashes, packet assessment and
cleanup checks. Raw PCAPs, runner/config snapshots, analysis script and recovery
state remain private under `/tmp/wave-quick-compare-20261024`. No production VPN
code changed. The physical Pixel and Linux application were untouched.

## Method and capture coverage

The existing Google APIs ARM64 Android 17 / API 37 emulator used fingerprint
`google/sdk_gphone64_arm64/emu64a:17/CE2A.260420.051.A3/16571990:userdebug/dev-keys`
and security patch `2026-08-05`. This is a stock SDK image, not a production
stock/OEM phone. Tested APKs were Wave `0.2-dev`, Eddie `4.1.0` and Proton
`5.20.98.0`; verified hashes and signers are retained in the evidence.

The emulator console recorded the virtual Ethernet LAN to a host-side PCAP,
outside Android's VPN. Android-interface captures ran alongside it. Crucially,
the initial host capture **missed Wi-Fi probe traffic**: four host-side Ethernet
packets versus 2,285 packets in the Android Wi-Fi positive control. That setup
was rejected. Wi-Fi was disabled and runner profiles changed to `eth0`.
Three pings and three replies then appeared externally; each accepted app run
also passed the full unprotected application/capture control.

The completed host PCAP contains **53,935 packets**, retaining inbound and
outbound traffic without a DNS-only filter. Its slices use each runner report's
wall-clock window and the actual guest Ethernet source MAC. All three accepted
external positive controls contain 18 ordinary resolver and 18 explicitly bound
resolver question packets, 88–89 marked raw IPv4 DNS packets and corresponding
IPv4 transport attempts. Replies and successful application controls establish
reachability. External slices start earlier than the narrower Android captures,
so their packet totals need not match.

IPv4 is externally validated. IPv6 attempts were visible on the virtual LAN,
and IPv6 application probes worked inside the tunnels, but there was no
successful unprotected IPv6 Internet control. This is not external IPv6 leak
coverage. Root was allowed only for emulator capture and induced SIGKILL;
this does not certify every stock hardware/kernel/module combination.
Always-on and blocking snapshots were enabled around all protected windows;
these do not prove continuous kernel enforcement at every packet.

## Observations

- **Wave:** steady passed. No marked DNS, marked raw DNS, marked direct-HTTPS or
  marked DoT probe escape appeared in the external protected windows. Crash
  contained **two unmarked outgoing IPv4 DNS question packets** and **one unmarked
  outgoing DoT-port SYN**. Origin remains unassigned. Its process restarted in
  about 1.15 seconds and required application traffic recovered.
- **Eddie:** steady passed. No marked probe DNS escape or unmarked DNS/DoT finding
  appeared externally in these protected windows. Required application traffic
  did not recover within the 50-second crash window despite process replacement.
  Remember-me/account persistence was not enabled for this test, matching the
  earlier comparator configuration. This is a bounded recovery result.
- **Proton:** steady passed. No marked probe DNS escape appeared externally.
  Disconnect contained **two unmarked outgoing IPv4 DNS question packets**;
  origin remains unassigned. Required application traffic did not recover within
  the 50-second crash window.

All apps' strict transition reports retained unsupported-packet failures. A
separate inspection of every flagged IPv6 extension packet in these captures
identified hop-by-hop headers followed by ICMPv6 type 143 multicast listener
reports (MLDv2), rather than DNS or application transport. External windows
contain a few more such packets because capture begins earlier. Original
failure states are preserved. Other HTTPS traffic is retained but is not
attributed to the independent probe simply from destination or timing.
These are question-packet counts, not unique names or proof that DNS answers
were received. A zero marked count is a sampled observation, not a leak-free
certification or basis for a product ranking.

## Preparation, restoration and repeatability

The first UI-driven attempt failed after the positive control. The device-panel
snapshot helper was stopped before retrying the independent UiAutomation driver;
the live device stream stayed open. Wave's next run completed its scenarios but
needed a separate successful cleanup retry before vendor testing continued.
Eddie temporarily received notification permission. Proton's first preparation
hit a first-connection notification suggestion; no protected results from that
attempt were accepted. After dismissing it, a fresh run completed all scenarios.
The user signed into Proton directly; no Proton password was requested/exported.

All six attempt journals now report no pending cleanup or cleanup errors.
Wi-Fi was restored enabled, owner-profile Always-on is unset, no userspace VPN
is active, QA probe/driver packages are absent, and Eddie's notification grant
was revoked. The host capture was stopped and its files moved to private storage.
Vendor accounts/apps remain available in the emulator. Original failed reports
retain their initial state, with successful restoration recorded separately.

[External capture commands](../tools/vpn-probe/RUNNING.md#external-emulator-ethernet-capture)
document the exercised setup and its Wi-Fi limitation. Documentation/evidence
changes passed `git diff --check`; app code was unchanged, so Kotlin/Rust builds
were not repeated. No release was published.
