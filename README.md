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
Keystore and excluded from cloud backup. Protocol tests and builds pass. Live
VPN connections, dual-stack traffic, network handoffs and post-unlock reboot
recovery were tested on a Pixel 7. A subsequent process-death recovery fix passed
targeted checks on both the emulator and physical Pixel without tapping Connect.
The signed minified release also has runtime coverage on the Pixel and on
Android 8.0, Android 15 with 16 KB pages, and Android 16 emulators.

Physical IPv4 upstream capture and the release-test findings are recorded in the
[follow-up report](docs/RELEASE-FOLLOWUP.md) and [expanded review](docs/RELEASE-ROUND3.md).
The older Android 16 emulator exposed fresh application DNS during process death;
newer API 37.0 and 37.2 SDK images also exposed fresh probe DNS during disconnect.
Physical capture contains additional DNS/transport observations without originating
UID/profile attribution. A general leak-free claim is not supported. Native IPv6
uplink/cellular capture and longer reliability tests remain gates.
See also the [initial signed-release report](docs/RELEASE-DEVICE-TESTING.md).

The [repeatable device-test runner](tools/vpn-probe/RUNNING.md) automates the
recorded recovery and traffic-probe scenarios, checks capture positive controls,
and saves resumable cleanup state. Its [validation record](docs/device-tests/2026-10-22-runner-validation.json)
distinguishes exercised scenarios from the broader available matrix.

Enable **Always-on VPN** and **Block connections without VPN** in Android settings.
Protection depends on platform behavior; affected Android versions can expose DNS
during tunnel changes. Verify application exclusions together with those settings
before relying on split tunneling.

[Verification record](docs/VERIFICATION.md). This is an unpublished development build.

Security reports: **vesselwave@protonmail.com**. See [security policy](SECURITY.md)
and [release signing](docs/RELEASE-SIGNING.md) for public verification keys and
Android release-build configuration.

## Published history privacy

The repository history and release packages were sanitized on 2026-11-03.
Lab records use example identities and addresses; private management details
are omitted. Release archives and signed checksums were repackaged while keeping
the APK and executable bytes unchanged. Existing clones may retain old history.
