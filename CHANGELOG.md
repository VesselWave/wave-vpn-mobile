# Changelog

## 0.1.0 — 2026-11-02 (experimental prerelease)

- Independent GPL-3.0-or-later Kotlin/Compose WireGuard client for AirVPN.
- Account storage protected by Android Keystore, server selection, favorites,
  application exclusions, traffic status and connection recovery.
- Automatic recovery after process death and post-unlock reboot verified in the
  recorded device tests, including the production-signed minified APK.
- Standalone device scenario runner with positive-control packet capture,
  artifact/signature checks, filtered reports and resumable cleanup.

Known release blocker: Android 16 emulator process-death testing exposed fresh
application DNS outside the tunnel despite Always-on VPN and blocking. Pixel
background DNS/DoT attribution and broader platform/reliability coverage remain
open. See [the current findings](docs/qa/RELEASE-FOLLOWUP.md).

This experimental prerelease does not establish production readiness or general
leak protection. See [v0.1.0 release notes](docs/RELEASE-v0.1.0.md).

- Authored blocking IPv4/IPv6 TUN handoff and early lockdown startup blocking;
  explicit-disconnect recovery guard and truthful stop-error state handling.
- Stock Samsung external captures confirm unresolved DNS escapes during crash,
  Wi-Fi recovery and force-stop, including with Private DNS Off. Blocking-link
  verification now handles Samsung's stale VPN session names and pins the AP.
