# Changelog

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
