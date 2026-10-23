# Security status

Wave VPN is an unpublished development application. No version currently has a
production support or security maintenance promise. See
[verification](docs/VERIFICATION.md) for tested behavior and outstanding device tests.

Known limitation: an Android 16 emulator exposed application DNS names on the
underlying network during VPN process death despite Always-on VPN and blocking.
API 37.0 and 37.2 SDK images also exposed fresh probe DNS during disconnect;
see the [expanded evidence review](docs/RELEASE-ROUND3.md).
This application does not promise device-wide leak prevention across Android
versions. See the [release follow-up](docs/RELEASE-FOLLOWUP.md) for evidence,
physical-device results and remaining gates.

Report vulnerabilities privately to **vesselwave@protonmail.com**. Include the
app version, Android version and reproduction steps. Do not post passwords,
private profiles, WireGuard keys, account identifiers or unredacted logs in public
issues. This is the maintainer-designated contact; email delivery has not been
independently tested. No response-time commitment is currently offered.

## Release identity

Release-download OpenPGP fingerprint:

```text
4140 18C0 BBB8 C0A7 0A80 BF57 0AA3 2CCB 2C0B 3951
```

The [public key](docs/keys/wave-release-signing.asc) identifies
`Wave VPN Releases <vesselwave@protonmail.com>` and expires on 2028-10-21.
This key verifies release downloads; it is not a vulnerability-report encryption key.

Android APK signing certificate SHA-256:

```text
29:95:0A:57:56:87:67:59:B2:89:84:A0:E6:B4:4A:27:6E:FD:94:8F:8E:70:08:C3:FF:F4:71:D4:AD:41:88:21
```

The [public APK certificate](docs/keys/wave-android-release.pem) identifies the
APK update signing key. This is distinct from the OpenPGP key. Debug APKs have
a different application ID and certificate. No production APK has been published.
Private keys, keystores and passphrases are never included in source control.
See [release signing](docs/RELEASE-SIGNING.md).
