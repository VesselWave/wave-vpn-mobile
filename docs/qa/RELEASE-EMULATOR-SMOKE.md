# v0.1.0 released-APK emulator smoke test — 2026-11-03

> **In short:** The published v0.1.0 APK passes install, connect, disconnect and crash recovery on an Android 17 emulator. With Always-on and blocking on, a crash leaked nothing in the probe; two cases need you to tap Connect again.

Artifact: `wave-vpn-android-0.1.0.apk` downloaded from the v0.1.0 GitHub release,
SHA-256 `5db2792df49e77e3aafbc3591036022b3bbd3f7c123e128f6ae1806f9c1f4943`.
Before install: OpenPGP manifest signature, trusted fingerprint
`414018C0BBB8C0A70A80BF570AA32CCB2C0B3951`, all checksums and the APK certificate
(SHA-256 `29:95:0A:…:88:21`) verified.

Device: Android Emulator `Wave_QA_Current_API37` (Android 17, arm64) on macOS.
The emulator's underlay is the Mac's NAT (the home public IPv4, omitted here; no
native IPv6). This is an emulator, not a physical or OEM device, and it does
not address the Samsung DNS failures.

| Step | Result |
| --- | --- |
| Install | PASS. In-place update over a same-certificate `0.2-dev` build (versionCode 2 → 3). Existing sign-in and settings preserved. |
| Launch | PASS. Overview shows Disconnected / Best server. |
| Connect | PASS. Connected to Sualocin (Toronto); `tun1` with IPv4 and IPv6 addresses; AirVPN DNS (`10.128.0.1`, `fd7d:…::1`) on the VPN network. |
| Tunnel exit | PASS. Chrome reported IPv4 `192.0.2.101` and IPv6 `2001:db8:1::6`, both AirVPN Toronto, not the underlay. Hostname resolution worked through the tunnel. |
| Disconnect | PASS. `tun1` removed; Chrome reported the underlay IPv4 again. |
| Process crash (`am crash`) | PASS with caveat. Android restarted `ConnectionService` after 1 s and the tunnel re-established automatically; the UI showed Connected; Chrome exit `192.0.2.107` (AirVPN, a different best server). The VPN network was down for about 1.1 s (VPN DISCONNECTED 12:51:45.398, CONNECTED 12:51:46.509). Always-on VPN was **off** in this run, so traffic during that gap was not blocked. |
| Cleanup | Disconnected; no `tun` interface left. App data kept. |

Observations:

- The first tap on the power button after a screen change was sometimes not
  registered by the automation tool. Coordinate taps worked reliably. No user-visible
  problem was confirmed, but watch for it in manual testing.
- From `adb shell` (UID 2000), ICMP to 1.1.1.1 worked through the tunnel but plain
  TCP via toybox `nc` returned nothing. Chrome traffic was normal. Not
  investigated; shell UID behaviour is not a user path.

Not covered: Always-on/lockdown crash behaviour, packet capture of the crash
gap, network handoff, reboot, physical devices and OEM builds.

## Crash test with Always-on VPN and "Block connections without VPN"

Same artifact and emulator, Always-on and lockdown enabled through Android
Settings. A shell-UID ping probe sent ICMP to 1.1.1.1 every 0.2 s with
timestamps. The shell UID is not exempt from lockdown, so any reply received
while no tunnel existed would mean traffic left outside the VPN. No packet
capture was available on this host (no root/BPF access), so this is a probe
result, not a full capture.

| Scenario | Result |
| --- | --- |
| Single crash (`am crash`) while connected | PASS. VPN down 1.091 s; Android restarted `ConnectionService` after 1 s; the app's blocking interface and then the tunnel were re-established automatically. Probe: 20 replies before, **0 during the gap**, 142 after recovery. |
| Second crash within minutes, then **Close app** in Android's "keeps stopping" dialog | NO LEAK, NO RECOVERY. Probe: 17 replies before, 0 in the following 27 s. Android did not restart the service; there was no tunnel and no app process. Reopening the app showed **Disconnected** and did not reconnect, although Always-on was set and the user had not disconnected. Traffic stayed blocked by Android until Connect was pressed. |
| Enabling Always-on after a manual Disconnect | Android started the VPN service with the Always-on intent, but the app did not connect (its Always-on callback requires the "reconnect" flag, which a manual Disconnect clears). The device stayed blocked and offline until Connect was pressed. |
| Disconnect with lockdown on | Expected: the "Wave — blocking traffic" interface remains, so nothing leaves outside the VPN. |

Assessment after reviewing the Samsung investigation
([RELEASE-SAMSUNG-STOCK.md](RELEASE-SAMSUNG-STOCK.md)):

1. **No app-side leak fix applies.** On this AOSP emulator, lockdown held through
   every gap. The Samsung DNS leaks occur while the app's process, and therefore
   its VPN interface, is dead; that investigation concluded an ordinary VPN app
   cannot keep its interface alive or firewall other UIDs during that interval.
2. **Ignoring Always-on after a manual Disconnect is intentional.** The Samsung
   work added this guard so service and Always-on callbacks cannot reconnect after
   an explicit Disconnect. Android's start intent is identical for boot, service
   restart and the user enabling Always-on, so the app cannot tell those apart.
   Traffic stays blocked; the user presses Connect.
3. **No recovery after repeated crashes plus "Close app" is Android behaviour.**
   Android declined to restart the crashed service; the app process did not exist
   to act. The app is exempt from lockdown itself, so it cannot detect from its own
   process that Android is blocking other apps, and reconnecting on launch would
   override the user's "Close app" choice. Traffic stays blocked; the user presses
   Connect.

These are documented as known behaviours rather than changed in code.

Settings were restored afterwards: Always-on off, lockdown off, app disconnected.

> Privacy note: VPN exits use documentation addresses after the 2026-11-03 cleanup. Test timings and outcomes are unchanged.
