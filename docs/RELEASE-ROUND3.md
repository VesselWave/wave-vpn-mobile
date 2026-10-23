# Independent release testing review (Round 3) — 2026-10-22 UTC

This report reviews the independent Round 3 evidence gathered across the physical
Pixel 7 (GrapheneOS Android 17) and Google APIs SDK emulators (API 36, API 37.0 4 KB,
and API 37.2 16 KB). Evidence was assembled from raw captures and reports in
private temporary storage (`/tmp/wave-android-round3-20261022`) following the prior
investigation run. The release gate remains **blocked**
(`blocked_by_privacy_findings_and_unverified_scopes`). Filtered evidence is
recorded in [2026-10-22-round3.json](device-tests/2026-10-22-round3.json).

This review builds on the baseline in [RELEASE-FOLLOWUP.md](RELEASE-FOLLOWUP.md) and
the constraints in [RELEASE-PLATFORM-CONSTRAINTS.md](RELEASE-PLATFORM-CONSTRAINTS.md).
As established by parent review, no root cause for platform DNS behavior is
proved, no operating-system workaround is shipped in Wave, and no broad leak-free
or production certification is claimed.

Parent review independently verified all 65 capture hashes/sizes and the 11
unchanged source reports. The physical manual-split cleanup was subsequently
completed after user reconnection: the current saved state has no pending cleanup,
no errors and no temporary helpers. Historical report cleanup fields are retained
unchanged. Subsequent split-app accessibility/explanation changes in source require
a new signed candidate; these captures identify the earlier APK below.

## Tested artifact and QA validation

The tested production-signed release APK is unchanged from Round 2:
- **APK SHA-256**: `3636d6522f23958a511c1b74c00f16cd0c7ac52e7dafb267304f2a3b0f6785b8`
- **Signer SHA-256**: `29950a5756876759b28984a0e6b44a276efd948f8e7008c3fff471d4ad418821`
- **Package**: `me.vwave.vpn` (versionCode 2, versionName "0.2-dev", non-debuggable)
- **Signature & Alignment**: APK Signature Scheme v2 verified (`apk-verification.log` SHA-256 `68bca181cb2beeb0a588b39a319fbc41042b36b5ecfe079a4a7541f92eef859a`); 16 KB page alignment verified (`zipalign -c -P 16 4`).
- **Offline Protocol Tests**: 6 Kotlin unit tests passed, 1 live bootstrap skipped (`kotlin-tests-fresh.log`).
- **Gradle Build & Lint**: Debug lint and build passed (`android-checks-final.log` SHA-256 `eef3df4ee271db884bfa4d8df5ca32fc93dd1c07eb02e1ec7374b3ea32938096`).
- **QA Runner Suite**: 25 runner safety and packet evidence tests passed (`qa-tests-final.log` SHA-256 `d4bb2e811bc3bcdaaa2bba4073010b991448b115456ceb21d51a6659c049d5a7`).

Repeatability note: Local QA helper scripts (`permission.py`, `pressure.py`,
`directboot.py`, `browser-rendered.py`, `manual-split.py`), custom native binaries
(`memory-pressure`), and test helper APKs (`bootprobe.apk`) reside in private scratch
storage, not the production release repository. These are documented with their
exact SHA-256 digests in [2026-10-22-round3.json](device-tests/2026-10-22-round3.json)
as repeatability records.

## Evaluated scopes and capture manifest

Round 3 evaluated 11 runtime scopes comprising 65 verified PCAP capture files.
All 65 capture files exist on disk, their SHA-256 digests match their respective
manifest records, and their byte sizes are verified.

| Scope | Platform / Image | Result Status | Scenarios (Pass / Fail) | Captures | Key Observations & Retained Findings |
|---|---|---|---|---|---|
| `emulator-matrix` | Android 16 / API 36 (ARM64, build `BE2A.250530.026.F3`) | **fail** | 4 pass / 9 fail | 13 | Cleartext DNS observed during crash, disconnect, kill-stress, Private DNS strict, and force-stop; non-probe DoT transport observed. |
| `pixel-functional` | Physical Pixel 7 (GrapheneOS Android 17, `2026101601`) | **fail** | 7 pass / 1 fail | 0 (no cap) | Functional connectivity checks passed; force-stop failed because independent probe process did not launch. |
| `pixel-capture` | Physical Pixel 7 (IPv4 gateway capture) | **fail** | 7 pass / 7 fail | 14 | 900s active traffic soak captured (14.07 MB, 60,888 packets, 4 cleartext IPv4 DNS); peer-outage contained 2 DNS packets; Private DNS auto/strict and force-stop contained shared-endpoint transport findings without UID attribution; split automation failed. |
| `api37-matrix-final` | Google APIs API 37.0 rev 8 (4 KB pages, build `CE2A.260420.051.A3`) | **fail** | 6 pass / 7 fail | 13 | Disconnect exposed 8 fresh probe DNS queries. Other windows retained DNS/transport findings or unassessed MLDv2 headers; these are distinct failure reasons. |
| `api37-2-matrix-final` | Google APIs API 37.2 rev 6 (16 KB pages, build `CP41.260831.007`) | **fail** | 4 pass / 7 fail | 11 | Post-crash traffic recovery failed. Disconnect exposed 8 fresh probe DNS queries; other windows retained DNS/transport or unassessed-header findings. |
| `emulator-permission-retry` | Android 16 / API 36 | **pass_for_exercised_scope** | 3 pass / 0 fail | 3 | Verified Settings Forget/regrant user flow across positive control, revoked state, and regrant recovery. |
| `emulator-pressure` | Android 16 / API 36 | **pass_for_exercised_scope** | 2 pass / 0 fail | 2 | Induced allocation pressure (1.94 MB capture, 8,137 packets, 4,613 WG); Wave process did not trigger an LMK exit record. |
| `directboot-final` | API 37.2 (16 KB pages, dedicated emulator) | **fail** | 3 pass / 1 fail | 4 | Ordinary `directBootAware` helper (`bootprobe`) verified helper function before unlock; pre-unlock capture observed cleartext DNS and non-probe DoT outside tunnel. |
| `browser-rendered` | API 37.0 Chrome 145.0.7632.218 | **pass_for_exercised_scope** | 2 pass / 0 fail | 2 | Cloudflare Secure DNS diagnostic rendered page reported DoH active in Chrome session; screenshot SHA-256 verified. |
| `pixel-manual-split` | Physical Pixel 7 (split-tunnel capture) | **fail** | 1 pass / 2 fail | 2 | Initial bypass setup was invalid due to stale UI reference before checkpoint; ADB dropped during included-app test. Saved cleanup was subsequently completed by the parent. |
| `pixel-manual-bypass-repeat` | Physical Pixel 7 (split-tunnel repeat) | **pass_for_exercised_scope** | 1 pass / 0 fail | 1 | Repeat bypass pass: blocking policy confirmed (`always_on_target_matches: true`, `blocking_enabled: true`), probe checkbox visually verified, 1 bypass app active; shared parent gateway cleanup. |

The current API 37.0 and 37.2 disconnect captures each contain eight fresh
probe DNS questions outside the tunnel. This is workload-specific failure
evidence, distinct from the unassigned background DNS and shared-endpoint SYNs.
It does not identify an exact operating-system mechanism. The original scenarios
and failure states remain unchanged in the JSON record.

## Methodological disciplines and factual limits

1. **Failure status retained after header inspection**: Inspection of unparsed
   packet headers (`packet-form-inspection.json`) categorized IPv6 multicast listener
   reports (`ipv6_header_0_next_58_icmp_type_143`). In accordance with testing
   standards, header inspection never alters the as-run failure status. All scenario
   failures remain strictly preserved.
2. **Transport endpoint matching vs. UID attribution**: Captures detecting SYN packets
   to shared HTTPS/DoT endpoints outside the tunnel do not identify the originating
   UID. These findings are recorded as `origin_not_assumed` rather than attributed
   to specific applications or services. Marked DNS payloads provide workload evidence
   separately from transport endpoint matching.
3. **Physical gateway capture scope**: Physical gateway captures record aggregate
   IPv4 Ethernet traffic from the physical device. They do not provide per-UID or
   per-profile packet attribution. Existing work-profile configuration remains an
   unconfirmed potential factor; the historical six DNS queries and original DoT
   SYN remain unassigned. No GrapheneOS leak-free guarantee exists.
4. **Platform coverage**: The evaluated images are Google APIs SDK emulator builds
   with recorded module versions (`com.google.android.resolv` / `com.google.android.tethering`
   version 360527520 on API 36, 370546200 on API 37.0, 372899999 on API 37.2).
   They do not represent the full spectrum of stock OEM devices, carriers, or
   OTA module combinations.
5. **Native IPv6 and cellular uplink limitations**: Direct native IPv6 positive
   controls failed due to the absence of a native IPv6 route on the physical test
   network. Cellular functional recovery was observed, but without an upstream
   packet capture gateway. The planned `pixel-handoff` scenario was not executed
   and is preserved as missing.
6. **Direct-boot testing distinction**: The direct-boot test used a dedicated
   API 37.2 emulator and an ordinary `directBootAware` helper (`bootprobe`). While
   the helper confirmed pre-unlock execution, the recovery phase used credential
   removal for automated cleanup rather than interactive PIN unlock, and does not
   cover physical Pixel pre-unlock behavior.
7. **Memory pressure and reliability**: Synthetic resident allocation pressure
   did not trigger an Android low-memory killer (LMK) termination of the Wave
   process. Natural LMK termination and recovery remain unverified. The 900-second
   (15-minute) active traffic soak is an active workload test, not an overnight or
   idle stability test.
8. **Browser diagnostic scope**: Chrome 145 rendered diagnostic confirms Secure DNS
   functionality within that specific browser session; it does not constitute proof
   of behavior across all third-party DoH clients or system profiles.
9. **Manual split-tunnel verification**: The initial manual split-tunneling run
   encountered an invalid setup due to an expired UI reference before checkpoint
   advancement, followed by an ADB disconnection. The repeat run (`pixel-manual-bypass-repeat`)
   verified the bypass blocking policy with 1 app configured, but ADB disconnection
   initially left cleanup pending. The saved restore has since completed; test
   failures are retained.

## Cleanup and environment status

- The physical gateway required resumed DHCP cleanup after initial routing changes;
  selected DHCP and route removal were independently verified.
- Restoration of the original physical VPN policy completed after explicit
  disconnect and reconnect actions.
- The latest `pixel-manual-split` run was interrupted when ADB became unavailable.
  After user reconnection, the parent completed its saved restore and independently
  checked Wave's Always-on/blocking policy and running process. Original Wi-Fi and
  split settings are restored; temporary helpers and gateway rules are removed.

## Remaining release gates

The release gate remains **BLOCKED** by the following unverified scopes and privacy findings:
1. **Platform privacy findings**: Cleartext IPv4 DNS packets and DoT/HTTPS transport
   SYN packets observed outside the tunnel during tunnel outages, process crashes,
   Private DNS transitions, force-stop, and pre-unlock direct boot across both
   physical and emulator platforms.
2. **Packet attribution**: Attribution of historical physical non-probe DNS queries
   and transport endpoint SYNs without correlated UID tracking.
3. **Native IPv6 uplink capture**: Lack of physical upstream packet capture on a native
   IPv6 network with positive control validation.
4. **Cellular capture & transition**: Absence of upstream packet capture on cellular
   uplink and live cellular/Wi-Fi handoff.
5. **OEM and production device diversity**: Verification across non-SDK stock OEM
   hardware and commercial OTA module combinations.
6. **Pre-unlock physical device protection**: Absence of physical pre-unlock capture
   under credential-locked state with PIN unlock.
7. **Natural memory pressure**: Absence of verified Wave process termination and
   recovery under natural Android LMK conditions.
8. **Overnight and idle stability**: Overnight idle soak and multi-hour reliability
   remain unverified.


## Subsequent split-app accessibility verification — 2026-10-23 UTC

The app now exposes each split-app row as a checked checkbox control and explains
that Android blocking also blocks apps outside the VPN in this profile. A debug
APK run on the original Android 16 emulator passed steady traffic, bypass blocking
and included-app traffic with Always-on/blocking verified at each window start.
Cleanup completed. This was functional testing without capture, not a privacy pass
or a signed-candidate result. The runner also now resolves the installed launcher,
fixing debug application IDs whose activity class uses the production namespace.
Twenty-six runner tests pass. [Filtered result](device-tests/2026-10-23-split-accessibility.json).
