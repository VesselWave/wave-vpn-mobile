# Release signing

Release builds use the package `me.vwave.vpn` and the production certificate. Debug builds use `me.vwave.vpn.dev` and the debug certificate, so the two can be installed side by side.

## Verifying a download

You need the APK, both archives, the `SHA256SUMS` manifest, its `.asc` signature and the key, all from the same release. Then:

```sh
bash scripts/verify-release.sh wave-vpn-android-VERSION-signing-key.asc \
  wave-vpn-android-VERSION-SHA256SUMS wave-vpn-android-VERSION-SHA256SUMS.asc \
  414018C0BBB8C0A70A80BF570AA32CCB2C0B3951
apksigner verify --verbose --print-certs wave-vpn-android-VERSION.apk
```

The script checks the key's identity, the manifest signature and every file's hash. `apksigner` must report a valid signature whose SHA-256 matches the certificate in [SECURITY.md](../SECURITY.md) (also in [keys/wave-android-release.pem](keys/wave-android-release.pem)). The script ships in this repository; no other checkout is needed.

Get the fingerprint from a source other than the download itself: [SECURITY.md](../SECURITY.md), [keys.openpgp.org](https://keys.openpgp.org/search?q=414018C0BBB8C0A70A80BF570AA32CCB2C0B3951) or [vpn.vwave.me](https://vpn.vwave.me/#verify). A matching certificate proves who built the APK; it doesn't prove leak protection or that device tests are complete.

## Building a signed release (maintainers)

Updates only install over a release signed with the same key, so **losing the keystore means existing users can't update**. Keep an encrypted backup of the keystore and its passphrase, separately.

Provide the signing details from private local storage, never from committed files, command-line arguments or shell history:

| Variable | Value |
| --- | --- |
| `WAVE_ANDROID_KEYSTORE` | Absolute path to the PKCS12 keystore |
| `WAVE_ANDROID_KEY_ALIAS` | `wave-vpn-release` |
| `WAVE_ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `WAVE_ANDROID_KEY_PASSWORD` | Key password |

Without these, release builds stay unsigned; a partial set fails rather than falling back to another key. Turn off Gradle configuration caching and build-scan uploads for signing jobs.

To package a tagged candidate: pick the version (`X.Y.Z` or `X.Y.Z-rc.N`), bump the version code, commit, tag `v<VERSION>`, then from that clean checkout run:

```sh
python3 scripts/release.py --sign-key 414018C0BBB8C0A70A80BF570AA32CCB2C0B3951
```

It needs Python 3.10+, JDK 17, SDK platform 37 with build-tools 37.0.0, OpenSSL and GnuPG. It runs the checks; builds the signed, minified APK; verifies its certificate, package, version and 16 KB alignment; and writes the APK, notices, source archive, provenance and toolchain records and checksums to `app/build/distribution/<revision>/`. It refuses an existing output directory and never publishes anything. Without `--sign-key`, the APK is still production-signed but the checksum manifest is left unsigned.

The private OpenPGP key and the keystore never go in the repository.
