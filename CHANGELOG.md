# Changelog

## Unreleased

**Fixed**

- Sign-in headings and the wordmark inherit the correct text color in dark mode; all screens share a themed root surface
- Search placeholders, log timestamps and log level labels use readable secondary text colors in both themes

## 0.1.3 · 2026-11-05 · early release

Lower infrastructure load and more reliable recovery, following [AirVPN Staff feedback](https://airvpn.org/forums/topic/82017-wave-vpn-open-source-wireguard-client-for-android-and-linux-unofficial-v011/?do=findComment&comment=268064). [Release notes](docs/RELEASE-v0.1.3.md).

**Changed**

- Latency scans sample representative locations with at most four workers, cache results and cooldowns across restarts, and pause while connecting, connected or in the background
- Sign-in retains the latest bootstrap addresses and RSA key, fairly rotates fallback addresses, and backs off during outages
- Cached account and server data reduce repeated requests; manual refresh and cooldown messages keep account updates accessible

**Fixed**

- Best server selection handles missing and failed samples without treating unreachable servers as zero latency; a failed TCP sample no longer blocks an explicitly chosen server
- Recovery chooses a validated physical network when Wi-Fi and cellular coexist, and keeps a retained tunnel visible after failed recovery
- Cancellation prevents late responses from overwriting newer state; logout promptly deletes credentials

**Verification and limits**

- Offline protocol/controller regressions and emulator recovery checks: [remediation evidence](docs/STAFF-REMEDIATION.md)
- Best remains a latency/load heuristic; throughput and long-term reliability are unverified
- Android platform notes and device test results remain in [installation and support](docs/SUPPORT.md)

## 0.1.1 · 2026-11-03 · early release

New look; interface only. [Release notes](docs/RELEASE-v0.1.1.md).

**Changed**

- VesselWave family look shared with Wave Remote and both websites: blue-black dark theme, pill buttons and segments, one corner scale (8/12/16)

**Fixed**

- Every screen stays usable at the largest font and display sizes: labels wrap instead of being crushed, long screens scroll, and buttons grow instead of clipping

## 0.1.0 · 2026-11-02 · early release

First public build. [Release notes](docs/RELEASE-v0.1.0.md).

**Added**

- WireGuard client for AirVPN, written in Kotlin with Jetpack Compose
- Android Keystore account storage, best server, server search and favorites
- Split tunneling, live traffic status and connection log
- Automatic recovery after process death and after reboot (once unlocked)
- A blocking interface that holds traffic during tunnel changes and at startup when Android's blocking is on
- Disconnect stays disconnected: Android's Always-on restart can't silently reconnect
- Device test runner with packet-capture positive controls, signed-artifact checks, filtered reports and resumable cleanup

**Known issue**

- Samsung Android 16: DNS can leak outside the VPN during a crash, Wi-Fi reconnect or force-stop, even with Always-on VPN and blocking enabled ([report](docs/qa/RELEASE-SAMSUNG-STOCK.md)).
