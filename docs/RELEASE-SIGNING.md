# Android release signing

The production application ID is `me.vwave.vpn`. Debug builds use
`me.vwave.vpn.dev` and the debug signing certificate. Production updates must
preserve the release signing identity; back up the encrypted keystore and its
passphrase separately before publishing the first release. Losing that identity
can prevent updates to existing installations.

Supply these environment variables from private local storage:

- `WAVE_ANDROID_KEYSTORE`: absolute path to the private PKCS12 keystore.
- `WAVE_ANDROID_KEY_ALIAS`: `wave-vpn-release`.
- `WAVE_ANDROID_KEYSTORE_PASSWORD`: keystore password.
- `WAVE_ANDROID_KEY_PASSWORD`: key password.

Run `./gradlew --no-daemon --no-configuration-cache :core:test :app:lintDebug :app:assembleRelease`.
With no signing variables, release builds remain unsigned. Partially configured
signing fails instead of falling back to another key. Do not put passwords in
committed Gradle files, command-line arguments or shell history. Avoid Gradle
configuration caching or build-scan uploads for signing jobs containing secrets.

The signed APK is `app/build/outputs/apk/release/app-release.apk`.
Before distributing it, use the Android SDK build-tools:

```sh
apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
```

Require a valid APK signature and compare the signer certificate SHA-256 digest
with [SECURITY.md](../SECURITY.md). The public certificate is stored at
[keys/wave-android-release.pem](keys/wave-android-release.pem). A matching
certificate does not establish VPN leak protection or completion of device tests.

For separately signed download checksums, use the shared OpenPGP public key at
[keys/wave-release-signing.asc](keys/wave-release-signing.asc), fingerprint
`414018C0BBB8C0A70A80BF570AA32CCB2C0B3951`. Publish the fingerprint through a
separately trusted maintainer channel. The private OpenPGP key and APK keystore
must remain outside the repository. No release is automatically published.
