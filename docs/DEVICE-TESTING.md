# Android device verification — 2026-10-22 UTC

This is a development-build test record, not a complete leak-protection or release
certification. No application code was changed for this run.

## Build and devices

- Pixel 7: Android 17 / API 37, build `2026101601`, security patch `2026-10-05`.
  USB ADB on physical test laptop was forwarded to the Mac at `127.0.0.1:5038`.
- Emulator: ARM64 Android 16 / API 36 (`T3_Pixel_API_36`), local ADB port 5037.
- Development package: `me.vwave.vpn.dev`, version `0.2-dev+debug`; the existing
  production package was retained.
- Source base: `470a3d2fad3f2c9cab64126bfaa2e1144ff29a9f`, with existing uncommitted
  workspace changes. This is not verification of an immutable release tag.
- Tested APK SHA-256:
  `ceb40f268b0f194d1b3b53cc7ea358160e8d64f8f665ffec779e049b17a17f92`.
- `./gradlew :core:test :app:lintDebug :app:assembleDebug` succeeded. A subsequent
  `./gradlew :core:test --rerun-tasks` freshly passed all six offline core tests;
  the opt-in live test was skipped. Existing lint and Gradle deprecation warnings
  remain.

## Method and boundaries

Authenticated through the app using the previously authorized local test account.
Credentials and private profiles were not added to the repository or report.
Android's Always-on VPN and Block connections without VPN were enabled for the
development package during physical-device failure tests.

A separately installed, authored test APK (`me.vwave.vpnprobe`) used its own app
UID and a foreground service. It made independent HTTPS IPv4/IPv6 exit checks
against `api4.ipify.org` and `api6.ipify.org`, direct-IP HTTPS checks against
`https://1.1.1.1/cdn-cgi/trace`, and fresh system DNS queries under
`1-1-1-1.sslip.io`. Each worker waited two seconds between completed attempts;
HTTPS connect/read timeouts were 2.5 seconds each. It also recorded its effective
network and DNS configuration. These are sampled checks, not a continuous packet
trace. Request completion timestamps can include responses started before a
transition; successful transition responses retained VPN exit addresses.

The early probe without a foreground service stopped being a reliable observer
when backgrounded. Only the later foreground-service run is used for lifecycle
conclusions. The forwarded USB connection also disappeared twice; those transport
interruptions are not counted as VPN test failures.

The main Android user/profile was tested. A separate work-profile VPN existed;
its routing and applications were not included in these results.

## Physical Pixel results

- **Authenticated connection:** WireGuard handshake, changed public IPv4,
  working IPv6, DNS resolution and direct-IP HTTPS. Initial VPN exit was
  `192.0.2.102`; later connections used Saiph (`192.0.2.103`) and Flegetonte
  (`192.0.2.104`). Distinct public IPv6 exits were also observed.
- **Wi-Fi → cellular → Wi-Fi:** 18-second cellular and 15-second Wi-Fi recovery
  windows. The probe observed CELLULAR/VPN then WIFI/VPN, with the same VPN exit
  and working IPv4/IPv6/DNS. Mobile data was restored to its initially disabled
  state afterward.
- **Airplane mode:** 12-second interruption; requests timed out while the VPN
  interface remained installed. IPv4/IPv6/DNS recovered in the 15-second return
  window after airplane mode was disabled and Wi-Fi restored.
- **Process death: protection passed, automatic recovery failed.** Sent SIGKILL
  to the development app's process using `run-as`. The process remained absent,
  the probe had no active network, and new DNS, IPv4, IPv6 and direct-IP HTTPS
  requests failed. No automatic recovery occurred in the initial 20-second
  window or the additional 15-second observation window. Opening Wave restored
  the saved account, but required tapping Connect to restore traffic.
- **Explicit disconnect:** in a 12-second window, new DNS/IPv4/IPv6/direct-IP
  requests failed with blocking enabled. Reconnecting restored all probes.
- **Server switch:** selected Flegetonte from Saiph in the running UI. Both IP
  families changed to the new VPN exit and DNS continued working in the
  12-second observation window. No non-VPN exit was observed.
- **Rotation:** requested landscape and restored portrait. Traffic continued
  through the VPN during the eight-second observation. Activity recreation was
  not independently instrumented, so this is not full recreation coverage.
- **Screen off:** traffic continued through the same VPN exit during a
  12-second screen-off check.
- **Forced Doze:** simulated unplugging, turned the screen off, and forced deep
  idle. `dumpsys deviceidle` confirmed `IDLE`. IPv4/IPv6/DNS/direct-IP traffic
  continued during the approximately 21-second window and the 12-second wake
  window. Forced idle and battery simulation were cleared afterward. This is a
  short forced-idle check, not an overnight battery/reliability test.
- **Split tunneling with blocking enabled:** excluding the probe blocked its
  network access; including it restored VPN traffic. All apps mode and an empty
  selection were restored afterward.
- **Reboot:** Android started the VPN service automatically after the user
  unlocked the phone, without launching Wave or tapping Connect. Post-unlock
  traffic verification passed: five successful observations each for IPv4,
  IPv6, fresh DNS and direct-IP HTTPS in a 12-second window, with no failures.

Scenario counts and public VPN exits are recorded in the
[filtered evidence](device-tests/2026-10-22-pixel7.json).

## Emulator results

The development APK installed and opened; authenticated sign-in and VPN consent
worked. Khambalia established a WireGuard handshake. A separate app observed
IPv4 exit `192.0.2.105`, IPv6 exit
`2001:db8:1::4`, successful fresh DNS resolution and
direct-IP HTTPS through that exit. Emulator checks do not replace the physical
network-transition results above.

## Remaining release gates

- Unexpected-process-death recovery failed in this original run; the development
  fix and successful targeted repeats are recorded below. Signed-release and
  broader reliability verification remain pending.
- Physical upstream packet capture, including transition gaps and fresh DNS/DoT
  attempts. DNS resolution and configured tunnel DNS alone do not establish
  absence of DNS leaks. No physical packet-capture pass is claimed.
- Provider DNS-receipt challenge, Private DNS modes and browser/application DoH
  interactions; arbitrary excluded apps and work-profile combinations.
- Pre-first-unlock boot traffic, long outages/stale-handshake status, overnight
  idle, natural memory pressure, permission revocation, competing VPNs and app
  upgrade behavior. Force-stop and OS crash-dialog paths are separate from the
  SIGKILL test.
- Repeat against the signed release APK and additional supported OS versions.

The filtered evidence contains scenario counts and public VPN exits only; raw
probe output, screenshots, credentials, serial numbers and private profiles are
not committed.

The temporary probe was uninstalled from both devices. The Pixel was left
connected to Flegetonte with Always-on VPN and blocking enabled, All apps mode,
Wi-Fi enabled and mobile data disabled. Battery simulation/forced idle were
cleared and portrait restored. No release was published.

## Process-death fix and targeted repeat — 2026-10-22 UTC

The new independently authored `ConnectionService` owns the connection lifetime
as a sticky foreground service, separately from WireGuard's TUN service. Device
logs showed Android unbinding the VPN service as the interface disappeared,
before process-death restart bookkeeping. A preliminary attempt to make only
the VPN service foreground still failed on the emulator and was discarded.
WireGuard's embedded VPN service remains unchanged. Explicit Disconnect stops
the connection service; system restart restores the saved account/selection.
The foreground notification says to open Wave for status, without claiming that
an unanswered or reconnecting tunnel is already connected.

The tested development APK SHA-256 is
`d1a068377b0f88f1a914838b56d8715fccac3a44700e2b3b4c7ca926b39f3fe7`.
It was installed over the existing development package on both devices. The
Pixel automatically reconnected after the update. The existing production
package was retained. The same independent foreground probe described above was
temporarily reinstalled. Filtered results are in
[recovery evidence](device-tests/2026-10-22-recovery.json).

- **Android 16/API 36 emulator:** background SIGKILL with Always-on VPN and
  blocking enabled automatically restarted Wave in approximately 2.1 seconds.
  During the restart plus 15-second follow-up, IPv4 and IPv6 each had five
  successful observations, DNS seven and direct-IP HTTPS six. One IPv4 request
  failed during the interruption. Successful IP checks showed VPN exits.
- **Android 17/API 37 Pixel 7:** background SIGKILL with Always-on VPN and
  blocking enabled automatically restarted Wave in approximately 2.5 seconds.
  During the restart plus 15-second follow-up, IPv4 had six successes/two
  failures, IPv6 seven/one, direct-IP HTTPS seven/two, and DNS six successes.
  Successful IP checks retained Flegetonte's VPN exits. Wave displayed a fresh
  connected session afterward. No opening Wave or Connect tap was used to
  recover from either SIGKILL.
- **Pixel explicit Disconnect:** the recovery service stopped and did not
  reconnect during the 12-second observation. All new probe checks failed:
  seven each for IPv4, IPv6 and direct-IP HTTPS, six for DNS. Manual Connect
  restored the tunnel and the UI showed Connected to Flegetonte.

An earlier emulator recovery run without Android blocking also restarted Wave,
but exposed ordinary non-VPN traffic during the gap. The foreground service is
recovery machinery; Android's blocking setting is still required to protect
process-death/teardown gaps. No private uplink addresses are included in evidence.
The probes sample request completions and cannot rule out unsampled leaks or
distinguish every in-flight request at a transition. Physical upstream packet
capture is still pending. This targeted run does not repeat the earlier full
handoff/Doze/reboot matrix or establish force-stop recovery.

`:core:test :app:lintDebug :app:assembleDebug` pass; a subsequent
`:core:test --rerun-tasks` freshly passed six offline protocol tests with the
opt-in live test skipped. No Linux code changed. Temporary probes were removed
from both devices. The Pixel was left connected with Always-on VPN and blocking
enabled. No signed release was tested or published.

## Later signed-release and packet checks

The subsequent [signed-release packet report](RELEASE-DEVICE-TESTING.md) covers
Android 8.0, Android 15 with 16 KB pages and Android 16. Application probe capture
checks passed in the exercised scenarios, but background Google-host DNS was
observed outside the tunnel. Physical capture remains pending because the
Pixel's forwarded ADB listener became unavailable. No signed-release Pixel pass
or device-wide leak-free result is claimed.

## Physical signed-release follow-up — 2026-10-22 UTC

The [follow-up report](RELEASE-FOLLOWUP.md) supersedes the earlier physical-access
blocker. It records a working upstream IPv4 positive control, production-signed
Pixel traffic/recovery captures, and Private DNS/DoH checks. An additional older
emulator run exposed fresh application DNS outside the tunnel, and a physical
peer-outage capture contained non-probe DNS of unconfirmed UID/profile. A general
leak-free result is not claimed; native IPv6 uplink and broader reliability gates
remain. The shipped protection/status wording was narrowed to match the evidence.

> Privacy note: VPN exits use documentation addresses after the 2026-11-03 cleanup. Test timings and outcomes are unchanged.
