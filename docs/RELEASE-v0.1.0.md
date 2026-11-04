# Wave VPN for Android 0.1.0

**Early release** · Android 8 (API 26) or newer · GPL-3.0-or-later

The first public build of an unofficial WireGuard client for AirVPN.

**v0.1.0 is an early release.** Anyone is welcome to use it, but it hasn't been fully tested on every device and system yet.

**Known issue, Samsung Android 16:** DNS can leak outside the VPN during a crash, Wi-Fi reconnect or force-stop, even with Always-on VPN and blocking enabled. If that matters for how you use a VPN, keep it in mind. See the [full Samsung test report](qa/RELEASE-SAMSUNG-STOCK.md).

## What's in it

- Sign in with your AirVPN account; credentials encrypted with Android Keystore
- Best-server choice, server search and favorites
- Split tunneling (bypass or tunnel only chosen apps)
- Live traffic status, connection log, Quick Settings tile
- Automatic recovery after the app's process dies or the phone reboots
- Works with Android's Always-on VPN and Block connections without VPN

## Downloads

| File | What it is |
| --- | --- |
| `wave-vpn-android-0.1.0.apk` | The app, signed with the production key, minified |
| `wave-vpn-android-0.1.0.tar.gz` | Same APK with notices and toolchain records |
| `wave-vpn-android-0.1.0-source.tar.gz` | Exact tagged source |
| `…-SHA256SUMS` and `…-SHA256SUMS.asc` | Checksums and their OpenPGP signature |

Verify the signature with the fingerprint in [SECURITY.md](../SECURITY.md) and the APK certificate with `apksigner` ([how](../README.md#verify-your-download)). The version code is 3, so it updates earlier development builds signed with the same key in place.

## How it was tested

The released APK passed an install, connect, disconnect and crash-recovery smoke test ([report](qa/RELEASE-EMULATOR-SMOKE.md)). Everything tested so far is in [verification](VERIFICATION.md).

Raw captures, account data and signing secrets are never published. There's no production support or security response-time commitment.
