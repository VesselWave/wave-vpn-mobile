# Changelog

## Unreleased — 0.2-dev

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
open. See [the current findings](docs/RELEASE-FOLLOWUP.md).

No public production release has been published. This list describes development
changes; it is not a release-readiness or general leak-protection claim.
