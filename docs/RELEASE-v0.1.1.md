A refreshed interface and better support for large text make Wave VPN easier to use on your phone.

Early release · Android 8 or newer (API 26+) · Testing across devices is still in progress.

## What's new

- **Refreshed interface:** blue-black dark theme, pill-shaped controls and consistent corners, shared with the other Wave apps.
- **Better large-text support:** labels wrap, buttons grow and longer screens scroll at the largest font and display sizes.

This is an interface update; VPN, protocol and account behavior is unchanged from 0.1.0.

## Get Wave VPN

**[Download APK · 12 MB](https://github.com/VesselWave/wave-vpn-mobile/releases/download/v0.1.1/wave-vpn-android-0.1.1.apk)**

For ongoing updates through F-Droid, add the **[Wave repository](https://f-droid.vwave.me/fdroid/repo?fingerprint=47841BF725DA7B106B2D05DF097E4898C7B5576411804A23D6A5505C5AD17933)**. F-Droid offers the current version; use the APK link above for this exact release.

**Updating from 0.1.0?** Install over your existing copy to keep your sign-in and settings. Both releases use the same signing certificate.

<details>
<summary>Installing the APK</summary>

1. Download the APK to your phone.
2. Open the file and, if Android asks, allow your browser or file manager to install apps from this source.
3. Install and open **Wave VPN**. Sign in with your AirVPN account and choose a server.

Release updates install in place when signed with the same certificate. See [installation and support](https://github.com/VesselWave/wave-vpn-mobile/blob/v0.1.1/docs/SUPPORT.md) for device notes and Android VPN settings.

</details>

<details>
<summary>Verify the download</summary>

Download [SHA256SUMS](https://github.com/VesselWave/wave-vpn-mobile/releases/download/v0.1.1/wave-vpn-android-0.1.1-SHA256SUMS) and [its signature](https://github.com/VesselWave/wave-vpn-mobile/releases/download/v0.1.1/wave-vpn-android-0.1.1-SHA256SUMS.asc) alongside the APK. On a computer with GnuPG, `sha256sum` and Android SDK's `apksigner`, run:

```sh
gpg --recv-keys 414018C0BBB8C0A70A80BF570AA32CCB2C0B3951
gpg --verify wave-vpn-android-0.1.1-SHA256SUMS.asc wave-vpn-android-0.1.1-SHA256SUMS
sha256sum --ignore-missing -c wave-vpn-android-0.1.1-SHA256SUMS
apksigner verify --print-certs wave-vpn-android-0.1.1.apk
```

Expected fingerprints:

```text
OpenPGP release key   4140 18C0 BBB8 C0A7 0A80 BF57 0AA3 2CCB 2C0B 3951
APK certificate       29:95:0A:57:56:87:67:59:B2:89:84:A0:E6:B4:4A:27:6E:FD:94:8F:8E:70:08:C3:FF:F4:71:D4:AD:41:88:21
```

Confirm these independently against [SECURITY.md](https://github.com/VesselWave/wave-vpn-mobile/blob/v0.1.1/SECURITY.md) or [vpn.vwave.me](https://vpn.vwave.me/#verify). All commands must succeed and the APK certificate must match.

The [release bundle](https://github.com/VesselWave/wave-vpn-mobile/releases/download/v0.1.1/wave-vpn-android-0.1.1.tar.gz) contains the same APK with notices and build records. A [public signing key](https://github.com/VesselWave/wave-vpn-mobile/releases/download/v0.1.1/wave-vpn-android-0.1.1-signing-key.asc) is also available.

</details>

---

[Installation and support](https://github.com/VesselWave/wave-vpn-mobile/blob/v0.1.1/docs/SUPPORT.md) · [Full changelog](https://github.com/VesselWave/wave-vpn-mobile/blob/v0.1.1/CHANGELOG.md) · [Source archive](https://github.com/VesselWave/wave-vpn-mobile/releases/download/v0.1.1/wave-vpn-android-0.1.1-source.tar.gz) · [Report an issue](https://github.com/VesselWave/wave-vpn-mobile/issues)
