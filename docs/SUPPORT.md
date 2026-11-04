# Installation and support

**Wave VPN is in early release.** Anyone is welcome to use it, but it hasn't been fully tested on every device and system yet.

**Known issue, Samsung Android 16:** DNS can leak outside the VPN during a crash, Wi-Fi reconnect or force-stop, even with Always-on VPN and blocking enabled. If that matters for how you use a VPN, keep it in mind. See the [full Samsung test report](qa/RELEASE-SAMSUNG-STOCK.md).

## Devices

Android 8 or newer. The release build has been run on:

| Device | Android | Depth |
| --- | --- | --- |
| Pixel 7 (GrapheneOS) | 17 | Connections, dual-stack traffic, network handoffs, reboot recovery |
| Galaxy S24 Ultra (stock Samsung) | 16 | External packet capture; DNS escapes during crash, Wi-Fi recovery and force-stop ([report](qa/RELEASE-SAMSUNG-STOCK.md)) |
| Emulator | 17 | Release APK smoke test, crash recovery with and without Always-on ([report](qa/RELEASE-EMULATOR-SMOKE.md)) |
| Emulators | 8, 15 (16 KB pages), 16 | Runtime checks of the signed, minified build |

Everything tested so far is in [verification](VERIFICATION.md).

## Installing and updating

1. Download the APK, checksums and signature from the same release on [vpn.vwave.me](https://vpn.vwave.me).
2. Verify them as in the [README](../README.md#verify-your-download) or [release signing](RELEASE-SIGNING.md). Check the fingerprint against at least one source other than the download page: a key sitting next to the APK proves nothing by itself.
3. Install the APK.

Release builds use the package `me.vwave.vpn`; debug builds use `me.vwave.vpn.dev` and can be installed alongside. Updates install in place as long as they're signed with the same certificate. An older build signed differently can't be updated: uninstalling it deletes its saved account and settings, so only do that deliberately, never just to troubleshoot a connection error.

## Recommended settings

Turn on **Always-on VPN** and **Block connections without VPN** (Settings → Open Android VPN settings). Android then blocks traffic whenever the tunnel is down. Some Android versions can still let DNS out during tunnel changes.

- With blocking on, apps you exclude through split tunneling are blocked rather than sent around the VPN. Test your exclusions with blocking enabled.
- After you press Disconnect, or after Android closes the app following repeated crashes, traffic stays blocked and the app shows Disconnected. Open Wave VPN and tap Connect.
- Force-stopping the app stops the VPN on purpose; it won't restart by itself.

## Reporting a problem

Open bugs as [GitHub issues](https://github.com/VesselWave/wave-vpn-mobile/issues). Ask questions in the [AirVPN forum thread](https://airvpn.org/forums/topic/82017-wave-vpn-open-source-wireguard-client-for-android-and-linux-unofficial-v011/).

Report security issues privately to **vesselwave@protonmail.com** ([SECURITY.md](../SECURITY.md)).

For ordinary bugs, include:

- app version, Android version and security patch, device
- network type (Wi-Fi, mobile, hotspot) and your Always-on / blocking settings
- what you did, what happened, and whether it was before sign-in, while connecting or after connecting
- how you recovered

Check screenshots and logs before sharing them. Never post your account name, password, WireGuard profiles or keys, packet captures, app lists or DNS hostnames. Android has no reviewed export for safe diagnostics yet, so don't paste raw logs publicly.

## Scope

WireGuard only. OpenVPN, obfuscation, and AirVPN account-side port forwarding, DDNS and blocklists are outside this release.
