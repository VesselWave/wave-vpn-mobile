# Wave VPN for Android 0.1.0

**Experimental prerelease** · Android 8 (API 26) or newer · GPL-3.0-or-later

The first public build of an unofficial WireGuard client for AirVPN. It's for testing, not yet production-qualified VPN protection.

## What's in it

- Sign in with your AirVPN account; credentials encrypted with Android Keystore
- Best-server choice, server search and favorites
- Split tunneling (bypass or tunnel only chosen apps)
- Live traffic status, connection log, Quick Settings tile
- Automatic recovery after the app's process dies or the phone reboots
- Works with Android's Always-on VPN and Block connections without VPN

## Known problem: DNS on stock Samsung

On a stock Samsung Galaxy S24 Ultra running Android 16, an external packet capture saw unencrypted DNS outside the VPN while the app **crashed**, while **Wi-Fi recovered**, and after a **force-stop**. This happened with Always-on VPN, blocking, and Private DNS off. Which process sent the packets is unknown.

Several app-side changes (a blocking interface during tunnel changes, blocking at startup, recovery guards) didn't stop it. **Don't rely on this build where that exposure is unacceptable.** Keeping Always-on VPN and blocking on still reduces the chances. For a hard boundary on Wi-Fi, a separate VPN gateway that blocks IPv4 and IPv6 traffic outside its tunnel, with mobile data off, is stronger; this app doesn't set that up. Evidence: [Samsung report](qa/RELEASE-SAMSUNG-STOCK.md).

## Downloads

| File | What it is |
| --- | --- |
| `wave-vpn-android-0.1.0.apk` | The app, signed with the production key, minified |
| `wave-vpn-android-0.1.0.tar.gz` | Same APK with notices and toolchain records |
| `wave-vpn-android-0.1.0-source.tar.gz` | Exact tagged source |
| `…-SHA256SUMS` and `…-SHA256SUMS.asc` | Checksums and their OpenPGP signature |

Verify the signature with the fingerprint in [SECURITY.md](../SECURITY.md) and the APK certificate with `apksigner` ([how](../README.md#verify-your-download)). The version code is 3, so it updates earlier development builds signed with the same key in place.

## How it was tested

The released APK passed an install, connect, disconnect and crash-recovery smoke test on an Android 17 emulator, with and without Always-on ([report](qa/RELEASE-EMULATOR-SMOKE.md)). Earlier physical-device results were collected on 0.2-dev builds and are kept separate. Not yet covered: native IPv6 uplinks, captured mobile data, before-first-unlock boot and overnight runs. See [verification](VERIFICATION.md).

Raw captures, account data and signing secrets are never published. There's no production support or security response-time commitment.
