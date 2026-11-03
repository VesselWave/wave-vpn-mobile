# Security

Wave VPN 0.1.0 is an experimental prerelease. No version has a production support or security maintenance promise yet. See [verification](docs/VERIFICATION.md) for what has and hasn't been tested.

## Reporting a vulnerability

Email **vesselwave@protonmail.com** (monitored by the maintainer). Include the app version, Android version and steps to reproduce. Don't put passwords, private profiles, WireGuard keys, account names or unredacted logs in public issues. There's no response-time commitment yet.

## Known limitation

Wave VPN doesn't promise device-wide leak prevention on every Android version:

- **Stock Samsung Android 16:** unencrypted DNS was captured outside the tunnel during a crash, Wi-Fi recovery and force-stop, even with Private DNS off ([report](docs/qa/RELEASE-SAMSUNG-STOCK.md)).
- **Android 16 emulator:** app DNS names appeared on the underlying network while the VPN process was dead, despite Always-on and blocking.
- **Android 17 (API 37.0 and 37.2) emulator images:** fresh probe DNS appeared during disconnect ([review](docs/qa/RELEASE-ROUND3.md)).

Physical-device results and remaining checks are in the [follow-up](docs/qa/RELEASE-FOLLOWUP.md).

## Verifying releases

Two separate identities sign each release:

| | Fingerprint |
| --- | --- |
| OpenPGP key that signs the checksums ([public key](docs/keys/wave-release-signing.asc), expires 2028-10-21) | `4140 18C0 BBB8 C0A7 0A80 BF57 0AA3 2CCB 2C0B 3951` |
| APK signing certificate, SHA-256 ([certificate](docs/keys/wave-android-release.pem)) | `29:95:0A:57:56:87:67:59:B2:89:84:A0:E6:B4:4A:27:6E:FD:94:8F:8E:70:08:C3:FF:F4:71:D4:AD:41:88:21` |

The OpenPGP key belongs to **Wave VPN Releases &lt;vesselwave@protonmail.com&gt;** and is also on [keys.openpgp.org](https://keys.openpgp.org/search?q=414018C0BBB8C0A70A80BF570AA32CCB2C0B3951) and [vpn.vwave.me](https://vpn.vwave.me/#verify). It verifies downloads; it isn't for encrypting reports. Debug builds use a different package name and certificate.

A valid signature proves who built the APK, not that it's production-qualified. Private keys, keystores and passphrases are never in source control. See [release signing](docs/RELEASE-SIGNING.md).
