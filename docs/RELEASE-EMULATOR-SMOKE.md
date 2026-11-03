# v0.1.0 released-APK emulator smoke test — 2026-11-03

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

> Privacy note: VPN exits use documentation addresses after the 2026-11-03 cleanup. Test timings and outcomes are unchanged.
