# Verification

What has been tested on Wave VPN for Android, what failed, and what hasn't been covered yet. The dated log below is the evidence; the [qa reports](README.md#how-it-was-tested) have the full detail.

## Summary

**Passes**

- Protocol and security unit tests, lint and builds; live sign-in bootstrap against AirVPN
- Pixel 7 (GrapheneOS, Android 17): connections, dual-stack traffic, network handoffs, recovery after reboot (once unlocked) and after the app's process dies
- Signed, minified release build on the Pixel and on Android 8, 15 (16 KB pages) and 16 emulators
- Released v0.1.0 APK on an Android 17 emulator: install, connect, disconnect, crash recovery with and without Always-on ([report](qa/RELEASE-EMULATOR-SMOKE.md))

**Fails**

- Stock Samsung Android 16: DNS outside the tunnel during crash, Wi-Fi recovery and force-stop ([report](qa/RELEASE-SAMSUNG-STOCK.md))
- Android 16 emulator: app DNS escaped while the VPN process was dead, despite Always-on and blocking
- Android 17 (API 37.0 and 37.2) emulator images: probe DNS during disconnect ([review](qa/RELEASE-ROUND3.md))

**Not yet covered**

- The v0.1.0 APK on a physical device
- Native IPv6 uplinks and captured mobile data
- Boot before first unlock, and overnight or long-running reliability

## Log, newest first

- [Physical release and DNS investigation follow-up — 2026-10-22 UTC](#physical-release-and-dns-investigation-follow-up--2026-10-22-utc)
- [Signed release and captured traffic — 2026-10-22 UTC](#signed-release-and-captured-traffic--2026-10-22-utc)
- [Physical Android verification — 2026-10-22 UTC](#physical-android-verification--2026-10-22-utc)
- [Release identity setup — 2026-10-22 UTC](#release-identity-setup--2026-10-22-utc)
- [Initial replacement check](#initial-replacement-check)

## Initial replacement check

2026-10-21 UTC, macOS development host.

Passed:

- Android: six offline protocol/security tests; APK debug build and lint (warnings remain).
- Android: live bootstrap decrypts AirVPN's explicit refusal of a synthetic invalid account.
- Rust: four offline protocol/security tests, all-target build/test, strict Clippy.
- Rust: separately enabled live invalid-account bootstrap test.
- Tests cover RSA session wrapping, AES/association interoperability, official
  manifest parsing, group inheritance, entry indices, IPv6 endpoints, missing
  devices, account mismatch, XML entity rejection and profile directive injection.
- Android debug APK installed separately as `me.vwave.vpn.dev`, preserving any
  existing production installation.
- GitHub API confirms both legacy repositories remain private and archived.

Not verified at the initial replacement check (Android runtime results below
supersede the corresponding entries):

- Android UI runtime: opening the app returned `DEVICE_IN_USE` because another
  agent-device session owns the emulator. That session was left alone.
- Authenticated account flows and actual WireGuard connections on either platform.
- Linux privileged worker, Hummingbird integration, firewall/DNS cleanup and
  disconnect/crash behavior. Rust compilation on macOS is not Linux runtime testing.
- Leak protection, split-tunneling interactions and feature parity with old apps.

Repeat locally:

```sh
./gradlew :core:test :app:lintDebug :app:assembleDebug
WAVE_LIVE=1 ./gradlew :core:test --rerun-tasks
```

```sh
cargo test --all-targets
cargo clippy --all-targets -- -D warnings
cargo test --test protocol live_bootstrap -- --ignored
```

Live tests use only made-up credentials. No real user credentials are required.
Rust's macOS dependency `block 0.1.6` currently emits a future-compatibility warning.

After the repository split, each app was checked from its own repository root.


## Release identity setup — 2026-10-22 UTC

Maintainer-designated security contact and public keys are recorded in SECURITY.md.
A new encrypted Android RSA-4096 PKCS12 signing key was created outside the repo,
with its passphrase stored in the macOS login keychain. The signed release APK
passes build-tools 37.0.0 `apksigner verify`; its SHA-256 certificate digest matches
the published public certificate. `:core:test`, `:app:lintDebug`,
`:app:assembleDebug` and `:app:assembleRelease` pass. Existing lint/deprecation
warnings remain. Signing builds disable configuration caching. No release was
published and these checks do not replace outstanding VPN/device testing.

The shared OpenPGP key rejects an empty passphrase and successfully signs a
synthetic checksum manifest using its keychain-held passphrase. The desktop's
isolated-keyring verifier accepts the signature with the documented fingerprint.
Only public verification material is included in either repository.

## Physical Android verification — 2026-10-22 UTC

[Device test results](qa/DEVICE-TESTING.md) and
[filtered scenario evidence](qa/device-tests/2026-10-22-pixel7.json) cover an Android
17/API 37 Pixel 7, with additional Android 16 emulator connection checks.
Authenticated WireGuard, IPv4/IPv6, fresh DNS resolution, Wi-Fi/cellular handoffs,
airplane-mode recovery, protected disconnect, server switching, short screen-off
and forced-Doze checks, split-tunnel blocking interactions and automatic VPN
startup after reboot/unlock passed the exercised probes.

Unexpected SIGKILL retained Android's blocking protection but did not automatically
restart Wave or reconnect in the observation windows. Opening Wave restored the
saved account; Connect restored traffic. This remains a release gate. No physical
packet-capture, DNS-leak certification, pre-unlock boot or overnight reliability
pass is claimed. Application code was not changed during this testing task.

`:core:test :app:lintDebug :app:assembleDebug` succeeded. A subsequent
`:core:test --rerun-tasks` freshly passed all six offline tests; the opt-in live
test was skipped. Existing warnings remain. The installed debug APK was tested,
not the signed release APK.

### Process-death recovery follow-up

Added a separate sticky foreground connection service while keeping WireGuard's
VPN service intact. Background SIGKILL now automatically restarts and reconnects
on both the Android 16 emulator and physical Android 17 Pixel 7. With Android
blocking enabled, independent IPv4/IPv6/DNS/direct-IP probes recover without
opening Wave or tapping Connect. Pixel explicit Disconnect still stops recovery
and retains blocking. Timings, interruption counts, APK hash and limitations are
in [the targeted device repeat](qa/DEVICE-TESTING.md#process-death-fix-and-targeted-repeat--2026-10-22-utc)
and [filtered recovery evidence](qa/device-tests/2026-10-22-recovery.json).
Core tests were freshly rerun; debug build and lint pass. Full physical packet
capture, the signed release runtime matrix and broader reliability gates remain
pending. The original failure above is retained as historical evidence.

## Signed release and captured traffic — 2026-10-22 UTC

The [signed-release packet report](qa/RELEASE-DEVICE-TESTING.md) and
[filtered evidence](qa/device-tests/2026-10-22-release-packets.json) record the
production-signed minified APK, its certificate check and 16 KB alignment check.
Runtime connection checks passed on Android 8.0/API 26, Android 15/API 35 with
verified 16 KB pages, and Android 16/API 36. The API 26 image rejected the
Cloudflare direct-IP HTTPS probe's certificate chain; other HTTPS exit checks
and VPN IPv4/IPv6/DNS worked. No TLS verification bypass was added.

A positive control validated capture on the actual emulator Wi-Fi interface.
Eleven signed-release captures showed no marked app-probe escape across the
exercised steady connection, process death, server switch, disconnect/reconnect,
200-second peer outage, force-stop/manual recovery and underlying-network checks.
The release also automatically started its VPN after an emulator reboot.

Some transition captures contained cleartext background DNS for Google push
hostnames. The originating UID and cause remain unconfirmed; this prevents a
device-wide leak-free claim. Physical capture and signed-release Pixel tests
could not proceed because forwarded ADB port 5038 returned connection refused.
Only QA tools and documentation were added; production app code was unchanged.

## Physical release and DNS investigation follow-up — 2026-10-22 UTC

See [the follow-up report](qa/RELEASE-FOLLOWUP.md) and
[filtered evidence](qa/device-tests/2026-10-22-followup.json). The exact original
production-signed APK was installed on the GrapheneOS Android 17 Pixel after
explicit authorization to remove its differently signed legacy production app.
A controlled IPv4 gateway capture passed an unprotected positive control, then
observed protected probe traffic/recovery through crashes, disconnect/reconnect
and a verified 200-second peer outage. Native IPv6 uplink capture was unavailable.
The final signed artifact adds accurate protection/status explanations; protocol
and tunnel behavior were not changed. Core tests, debug lint, debug assembly,
signed minified assembly, signature verification and 16 KB APK alignment pass.

A repeated-kill Android 16 emulator capture exposed five fresh application DNS
queries and four background DNS queries outside WireGuard. Only UID 0 counted
outgoing Wi-Fi DNS in encompassing per-UID counters. A physical outage capture
also contained six non-probe DNS packets with unconfirmed UID/profile; another
profile had an independent active VPN. Private DNS Automatic emitted one
non-probe DoT SYN outside the tunnel. DoH responses and fresh DNS worked in
short Off/Automatic/strict captures without probe escape. These are scoped test
results and unresolved findings, not a device-wide privacy pass.
