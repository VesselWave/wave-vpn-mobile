# Wave VPN for Android 0.1.1

**Early release** · Android 8 (API 26) or newer · GPL-3.0-or-later

**v0.1.1 is an early release.** Anyone is welcome to use it, but it hasn't been fully tested on every device and system yet.

**Known issue, Samsung Android 16:** DNS can leak outside the VPN during a crash, Wi-Fi reconnect or force-stop, even with Always-on VPN and blocking enabled. If that matters for how you use a VPN, keep it in mind. See the [full Samsung test report](https://github.com/VesselWave/wave-vpn-mobile/blob/v0.1.1/docs/qa/RELEASE-SAMSUNG-STOCK.md).

## What's new

- **New look**, shared with the other Wave apps and websites: a blue-black dark theme, pill-shaped buttons and segments, and one consistent set of corner sizes.
- **Large text works on every screen.** At the biggest font and display sizes, labels wrap instead of getting crushed, long screens scroll, and buttons grow instead of clipping. At normal size nothing moves.

Only the interface changed. The VPN, protocol and account code is the same as in 0.1.0.

## Install or update

- **F-Droid:** add the [Wave repository](https://f-droid.vwave.me/fdroid/repo?fingerprint=47841BF725DA7B106B2D05DF097E4898C7B5576411804A23D6A5505C5AD17933) to get this and future updates automatically.
- **APK:** download `wave-vpn-android-0.1.1.apk` below. It's signed with the same key as 0.1.0 and installs over it, keeping your sign-in and settings.

## Downloads

| File | What it is |
| --- | --- |
| `wave-vpn-android-0.1.1.apk` | The app, signed with the production key |
| `wave-vpn-android-0.1.1.tar.gz` | Same APK with notices and build records |
| `wave-vpn-android-0.1.1-source.tar.gz` | Exact tagged source |
| `…-SHA256SUMS`, `…-SHA256SUMS.asc`, `…-signing-key.asc` | Checksums, their OpenPGP signature and the public key |

## Verify

```text
OpenPGP release key   4140 18C0 BBB8 C0A7 0A80  BF57 0AA3 2CCB 2C0B 3951
APK certificate       29:95:0A:57:56:87:67:59:B2:89:84:A0:E6:B4:4A:27:6E:FD:94:8F:8E:70:08:C3:FF:F4:71:D4:AD:41:88:21
```

```sh
gpg --recv-keys 414018C0BBB8C0A70A80BF570AA32CCB2C0B3951
gpg --verify wave-vpn-android-0.1.1-SHA256SUMS.asc wave-vpn-android-0.1.1-SHA256SUMS
sha256sum --ignore-missing -c wave-vpn-android-0.1.1-SHA256SUMS
apksigner verify --print-certs wave-vpn-android-0.1.1.apk
```

Compare the fingerprints with [SECURITY.md](https://github.com/VesselWave/wave-vpn-mobile/blob/v0.1.1/SECURITY.md) or [vpn.vwave.me](https://vpn.vwave.me/#verify), not with this page. Built and signed by the [release workflow](https://github.com/VesselWave/wave-vpn-mobile/blob/v0.1.1/.github/workflows/release.yml) from the `v0.1.1` tag.

Earlier notes: [0.1.0](https://github.com/VesselWave/wave-vpn-mobile/blob/v0.1.1/docs/RELEASE-v0.1.0.md) · [changelog](https://github.com/VesselWave/wave-vpn-mobile/blob/v0.1.1/CHANGELOG.md)
