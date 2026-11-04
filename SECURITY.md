# Security

**v0.1.0 is an early release.** Anyone is welcome to use it, but it hasn't been fully tested on every device and system yet. There's no security maintenance promise yet; see [verification](docs/VERIFICATION.md) for what has been tested.

## Reporting a vulnerability

Email **vesselwave@protonmail.com** (monitored by the maintainer). Include the app version, Android version and steps to reproduce. Don't put passwords, private profiles, WireGuard keys, account names or unredacted logs in public issues. There's no response-time commitment yet.

## Known issue

**Known issue, Samsung Android 16:** DNS can leak outside the VPN during a crash, Wi-Fi reconnect or force-stop, even with Always-on VPN and blocking enabled. If that matters for how you use a VPN, keep it in mind. See the [full Samsung test report](docs/qa/RELEASE-SAMSUNG-STOCK.md).

## Verifying releases

Two separate identities sign each release:

| | Fingerprint |
| --- | --- |
| OpenPGP key that signs the checksums ([public key](docs/keys/wave-release-signing.asc), expires 2028-10-21) | `4140 18C0 BBB8 C0A7 0A80 BF57 0AA3 2CCB 2C0B 3951` |
| APK signing certificate, SHA-256 ([certificate](docs/keys/wave-android-release.pem)) | `29:95:0A:57:56:87:67:59:B2:89:84:A0:E6:B4:4A:27:6E:FD:94:8F:8E:70:08:C3:FF:F4:71:D4:AD:41:88:21` |

The OpenPGP key belongs to **Wave VPN Releases &lt;vesselwave@protonmail.com&gt;** and is also on [keys.openpgp.org](https://keys.openpgp.org/search?q=414018C0BBB8C0A70A80BF570AA32CCB2C0B3951) and [vpn.vwave.me](https://vpn.vwave.me/#verify). It verifies downloads; it isn't for encrypting reports. Debug builds use a different package name and certificate.

A valid signature proves who built the APK, not that it's leak-proof. Private keys, keystores and passphrases are never in source control. See [release signing](docs/RELEASE-SIGNING.md).
