# Android VPN comparison — 2026-10-24 UTC

> **In short:** AirVPN's own Eddie leaked app DNS on the same stock emulator, so the failure isn't unique to Wave. The exact cause is still unknown.

The release remains **blocked** for an unrestricted privacy claim. Official
Eddie reproduced marked ordinary application DNS outside the VPN on the stock
Android emulator. This demonstrates that the observed failure is not exclusive
to Wave. It does not establish the exact kernel/resolver cause or certify any
Android version as universally safe. No production VPN implementation changed.

The later [hotspot and tunneled IPv6 follow-up](RELEASE-HOTSPOT-IPV6.md) adds
controlled dual-stack Wi-Fi and radio-recovery evidence without replacing these
historical results.

The [filtered evidence](device-tests/2026-10-24-comparison.json) retains successful,
failed, incomplete and restored attempts, APK identities, launch-time runner
snapshots where available, capture hashes and the final cleanup checks. Private
PCAPs, credentials-free source snapshots, observations and recovery journals are
under `/tmp/wave-comparison-20261024`. Raw account/device data stays outside Git.

The tested Wave production APK was the existing signed `0.2-dev` candidate,
SHA-256 `3636d6522f23958a511c1b74c00f16cd0c7ac52e7dafb267304f2a3b0f6785b8`.
Eddie 4.1.0 and Proton VPN 5.20.98.0 came from the existing phone installations;
the comparator verified their configured signing identities and archived base
and split APK hashes without replacing them. The same vendor APK sets were
installed on the emulator. Eddie used the `.env` AirVPN account through a private
credential file. Proton used its existing phone account and the user-supplied
private credentials for emulator sign-in. No account values appear in this report.
Eddie's Remember me option was not selected; its recovery result is scoped to
that configuration.

The physical device was a Pixel 7 with GrapheneOS Android 17, incremental
`2026102101`, security patch `2026-10-05`. A managed Linux gateway captured only
the phone's IPv4 uplink. The Google APIs API 37.0 ARM64 emulator used fingerprint
`google/sdk_gphone64_arm64/emu64a:17/CE2A.260420.051.A3/16571990:userdebug/dev-keys`,
security patch `2026-08-05`, and a dedicated local ADB serial/port. The Pixel used
the forwarded ADB server; it was never rooted. Separate physical and emulator
runs could overlap, but no two test drivers shared one device during accepted
capture windows. Preparation/cleanup diagnostics are separate from accepted tests.

Every protected run first required a working unprotected capture positive
control. Workloads included ordinary fresh resolver requests, explicitly bound
requests, raw DNS, IPv4/tunnel IPv6, direct HTTPS and real DoH. Always-on and
blocking were checked around protected windows; saved settings do not prove
continuous enforcement at each packet. Disconnect required Android to report
an inactive target VPN. Process death used root SIGKILL on the emulator and
`am crash` on the signed physical apps. Force-stop was intentional and followed
by separately labelled manual recovery. These are short sampled windows.

On the stock emulator:

- Eddie's two accepted disconnect windows each captured **one ordinary resolver
  question packet and two bound-resolver question packets** outside the tunnel.
  The first also contained one unassigned cleartext DNS packet; both contained
  an unassigned DoT transport attempt. Saved blocking remained enabled. These
  are question packets, not unique names or proof of successful DNS answers.
- Wave's two new disconnect samples had no captured marked escape. Its crash
  window contained one unassigned cleartext DNS packet; automatic traffic
  recovery worked. These samples do not erase earlier ordinary DNS escapes on
  this SDK image or the Android 16 image.
- Proton's disconnect sample had no captured marked escape. Its reconnect,
  crash and force-stop windows contained respectively **two, one and two**
  unassigned DNS packets. Reconnect also contained two IPv4 UDP fragments that
  the strict analyzer does not reassemble. No fresh marked probe DNS escape was
  observed in these Proton samples. Unassigned packets are not attributed to
  Proton, another app, the probe or the system without further evidence.
- Eddie and Proton did not restore any required traffic after their observed
  process replacement within the 50-second crash window. Wave recovered.
  This is a bounded runtime comparison, not a claim that those products never
  reconnect. Standalone connected-start force-stop and manual recovery checks
  passed for Wave and Eddie. Proton's stop window retained its DNS finding;
  its manual recovery passed.

On the Pixel:

- Wave passed steady traffic, disconnect/reconnect, induced crash recovery,
  force-stop blocking and manual recovery, with no captured DNS/probe transport
  findings in these windows.
- Eddie passed steady, disconnect and reconnect captures. Its crash window had
  no captured probe/DNS finding, but required traffic did not recover within
  the window. The subsequent stop scenario failed its connected-start
  prerequisite; it is not a protection pass.
- Proton passed steady and disconnect captures. Its reconnect window contained
  two unassessed IPv4 UDP fragments; the crash window contained one unassigned
  DNS packet and failed recovery. No marked probe escape was observed. Its
  connected-start force-stop and manual recovery checks passed.

The fragments are retained as an assessment gap, not asserted to be DNS leaks.
Other-profile/system origin and expected VPN control traffic remain possible
sources of unassigned packets. Neither a matching endpoint nor a clean short
window supplies originating-UID attribution. No unrelated background service or other
profile was disabled to obtain a pass. There is no product ranking from these
small, sequential, timing-sensitive samples.

This is consistent with a shared platform limitation. [Mullvad's original
investigation](https://mullvad.net/en/blog/dns-traffic-can-leak-outside-the-vpn-tunnel-on-android)
describes DNS escape during Android VPN transitions despite blocking.
[GrapheneOS documents additional resolver and multicast leak blocking](https://grapheneos.org/features#improved-vpn-leak-blocking).
Those references provide context; they do not prove the mechanism of these
specific SDK packets. The observed marked Eddie queries are the independent
runtime evidence that this problem is broader than Wave.

The new [installed-app comparator and repeated matrix](../../tools/vpn-probe/RUNNING.md#comparing-installed-vpn-applications)
reuse the same probes, controls and failure rules. Each matrix snapshots/hashes
its runner and configuration, preserves every repetition, rotates target order,
and stops on incomplete cleanup. The vendor adapter supports the five basic
connection/lifecycle scenarios and explicitly skips Wave-specific operations.
The broader Wave runner remains available. New tests reject missing connected
starts, retained blocking tunnels mistaken for teardown, hidden stage failures,
credential overwrites and unsafe interruption. The final offline suite passed
**42 tests**. Gradle `:core:test :app:lintDebug :app:assembleDebug` completed
successfully, with mostly cached outputs; helper Java/DEX/APK builds were also
exercised live. Linux source was untouched.

Several initial attempts failed before protected capture because of explicit
ADB selection, notification/selected-tab UI prerequisites or revoked vendor
service state. One interrupted cleanup was restored separately. They are
retained in the manifest and do not count as passes. The first Eddie emulator
force-stop result inherited failed recovery; its later connected-start
confirmation supersedes that coverage. Vendor preparation now explicitly
restarts the target after the unprotected control, before establishing the
protected baseline. It never restarts/opens an app to pass crash recovery.

The device-panel emulator launch returned “Device boot failed: Could not
communicate with device support. Try refreshing devices.” It restarted during
preparation; that setup was discarded. The accepted emulator captures used a
separate explicitly addressed instance. Only owned instances and test packages
were stopped/removed. Final cleanup restored the phone's initial owner VPN,
DNS and radio settings and DHCP, removed helpers/gateway rules, restored Eddie's
temporary notification grant, retained all original phone APKs, removed the
owned emulator apps, and stopped the emulator. The temporary Proton credential
file was removed. Final verification is recorded in the manifest.

Still required before an unrestricted release:

- Resolve or deliberately constrain the supported-platform/privacy policy for
  the demonstrated DNS failure. No general leak-free guarantee is justified.
- Attribute the historical/current unassigned DNS and transport observations,
  account for other profiles/browser DoH, and assess unsupported packet forms.
- Capture a usable native IPv6 uplink, cellular traffic and Wi-Fi/cellular handoff
  with successful corresponding positive controls.
- Exercise physical pre-unlock boot, VPN-consent revocation, natural LMK pressure,
  overnight idle and multi-hour reliability, plus stock/OEM production hardware
  and current module combinations. Synthetic crash is not natural LMK coverage.
- Select the release version and retest the final signed/minified candidate and
  distribution artifacts from its exact tagged source. Confirm the remaining
  signing/support/distribution gates in the shared checklist.

The automation covers the observed regressions; it does not claim that every
remaining release gate is automated or passed. No release was published.
