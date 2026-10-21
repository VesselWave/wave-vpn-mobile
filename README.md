# Wave VPN for Android

Unofficial WireGuard client for AirVPN, built with Kotlin and Jetpack Compose.
Licensed under GPLv3 or later; not affiliated with AirVPN.

This repository builds independently. See [provenance](docs/PROVENANCE.md) for
pinned Eddie references and the authored UI retained during the backend rewrite.

## Build

Requires JDK 17 and Android SDK 37. Set `ANDROID_HOME`, or put your SDK path in
`local.properties` (`sdk.dir=/your/android/sdk`).

```sh
./gradlew :core:test :app:lintDebug :app:assembleDebug
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk` and installs as
`me.vwave.vpn.dev`, separately from the production app.

For the optional live bootstrap test (synthetic invalid credentials only):

```sh
WAVE_LIVE=1 ./gradlew :core:test --rerun-tasks
```

## Status

The authored screens use a new Eddie-referenced protocol implementation and new
storage/tunnel adapters. Credentials and account keys are encrypted with Android
Keystore and excluded from cloud backup. Protocol tests and builds pass; actual
VPN connections, lifecycle behavior and leak protection still need device testing.

Enable **Always-on VPN** and **Block connections without VPN** in Android settings
for protection while the tunnel is absent or being replaced. Verify application
exclusions together with those settings before relying on split tunneling.

[Verification record](docs/VERIFICATION.md). This is an unpublished development build.

## Published history privacy

The repository history and release packages were sanitized on 2026-11-03.
Lab records use example identities and addresses; private management details
are omitted. Release archives and signed checksums were repackaged while keeping
the APK and executable bytes unchanged. Existing clones may retain old history.
