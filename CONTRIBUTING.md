# Contributing

Bug reports and pull requests are welcome. For security issues, email **vesselwave@protonmail.com** instead of opening an issue ([SECURITY.md](SECURITY.md)).

## Build and check

Needs JDK 17 and Android SDK 37. Set `ANDROID_HOME`, or put `sdk.dir=/your/android/sdk` in `local.properties`.

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk` and installs as `me.vwave.vpn.dev`, next to a release install. Optional live bootstrap test, using synthetic invalid credentials only:

```sh
WAVE_LIVE=1 ./gradlew :core:test --rerun-tasks
```

Offline recovery and bootstrap fault-injection checks:

```sh
python3 scripts/check-forum.py
```

This command currently exits 1 because it detects known defects. It writes a JSON report and distinguishes failed contracts from an incomplete run. See [the regression report](docs/qa/FORUM-REGRESSIONS-2026-11-07.md). Ordinary checks skip these opt-in contracts until the fixes reconcile the older policy tests.

Emulator UI checks run in the isolated `me.vwave.vpn.qa` build with a synthetic offline session; they never touch a signed-in install. Use an emulator without another active VPN:

```sh
python3 scripts/forum-ui-emulator.py --serial emulator-5556              # positive controls, must pass
python3 scripts/forum-ui-emulator.py --serial emulator-5556 --contracts  # plus desired-behavior contracts
```

See [docs/qa/FORUM-UI-2026-11-07.md](docs/qa/FORUM-UI-2026-11-07.md).

## Layout

- `core/`: the AirVPN protocol (sign-in, manifest, WireGuard profile) as plain Kotlin with JVM tests, following the pinned Eddie sources in `protocol-data/`.
- `app/`: the Jetpack Compose UI, the `VpnService` that owns the tunnel and the blocking interface, and the connection service.
- `tools/vpn-probe/`: the [device test runner](tools/vpn-probe/RUNNING.md) for recovery and leak scenarios, with positive-control packet capture and resumable cleanup.

## Where code may come from

- Protocol behavior follows the pinned official Eddie sources and other explicitly licensed references. Record any new reference or reuse in [docs/PROVENANCE.md](docs/PROVENANCE.md).
- Never commit credentials, private profiles, signing keys, keystores, `local.properties` or build outputs.
- The project is GPL-3.0-or-later; keep dependency notices intact.

Release builds and signing are described in [docs/RELEASE-SIGNING.md](docs/RELEASE-SIGNING.md).
