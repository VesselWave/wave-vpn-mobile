# Changelog

## 0.1.0 · 2026-11-02 · experimental prerelease

First public build. [Release notes](docs/RELEASE-v0.1.0.md).

**Added**

- WireGuard client for AirVPN, written in Kotlin with Jetpack Compose
- Android Keystore account storage, best server, server search and favorites
- Split tunneling, live traffic status and connection log
- Automatic recovery after process death and after reboot (once unlocked)
- A blocking interface that holds traffic during tunnel changes and at startup when Android's blocking is on
- Disconnect stays disconnected: Android's Always-on restart can't silently reconnect
- Device test runner with packet-capture positive controls, signed-artifact checks, filtered reports and resumable cleanup

**Known issues**

- Stock Samsung Android 16: DNS observed outside the tunnel during crash, Wi-Fi recovery and force-stop, with Always-on, blocking and Private DNS off ([report](docs/qa/RELEASE-SAMSUNG-STOCK.md)).
- Android 16 emulator: app DNS escaped during process death despite Always-on and blocking ([follow-up](docs/qa/RELEASE-FOLLOWUP.md)).
- Not yet covered: attributing Pixel background DNS/DoT, and broader devices and long-running reliability.
