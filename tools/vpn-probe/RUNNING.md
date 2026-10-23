# Repeatable Android device verification

`run.py` replaces the ad-hoc commands in the device-testing, reconnect-fix and
release-follow-up threads. It builds its own independent probe and UI driver;
neither the app build nor this runner depends on the sibling Linux repository,
T3 sessions, `/tmp` helpers, machine keychains or a hard-coded phone serial.
The helpers are GPL-3.0-or-later and are not shipped in Wave.

Source workflows: [device testing](t3-thread://v1/240d3f84-ccf8-4071-b4fd-19554f697ec1),
[process-death recovery](t3-thread://v1/2a7198b7-2664-4843-897e-2349a08a5be7),
and [release follow-up](t3-thread://v1/fd5dd284-969f-435f-9afa-b1814ce8e6ef).

## Quick start

Use Python 3.10+, JDK 17, Android SDK platform 37.0/build-tools 37.0.0, and an
Android 8/API 26 or later device with USB debugging authorized. A rooted,
userdebug ARM64 or x86_64 emulator plus an NDK is required for emulator capture.
The runner never roots a physical phone. Use an English device UI.

From the standalone Android checkout:

```sh
export ANDROID_HOME=/absolute/path/to/android-sdk
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/your-installed-version"
export WAVE_QA_APK=/absolute/private/path/to/signed-wave.apk
export WAVE_QA_CREDENTIALS=/absolute/private/path/to/account.json

python3 tools/vpn-probe/run.py doctor \
  --config tools/vpn-probe/example.json --profile emulator \
  --output /tmp/wave-qa-doctor-001 --allow-root

python3 tools/vpn-probe/run.py run \
  --config tools/vpn-probe/example.json --profile emulator \
  --output /tmp/wave-qa-emulator-001 --allow-root \
  --scenarios steady,crash,disconnect,reconnect
```

Use a **new output directory for every run**. Output must be outside any Git
checkout. `report.md` and `report.json` contain filtered counts, hashes and
explicit pass/fail/skip results. The mode-0700 `private/` directory holds raw
PCAPs, observations, original settings, APK backup, build logs and cleanup state.
Do not publish or commit it. Even the filtered report should be reviewed before
sharing. It contains no account values, hostnames from packet captures or IPs.

The account JSON has `username` and `password` keys and must have mode 0600.
Create it using a private editor or secret manager; do not put account values in
shell history, the example configuration or command-line flags. It is read only
if the app needs sign-in. The driver sends editable values through a private
app command file, deletes that file, and never returns editable field contents.
ADB access and the temporary debuggable helpers must remain trusted.

The configured production signing fingerprint must match the APK. For a locally
signed fork, change `expected_signer_sha256` to your independently verified public
fingerprint. Debug builds need their own `package`, APK and expected fingerprint.
A mismatched existing installation fails before removal. `--allow-replace`
explicitly permits removal of the **owner profile's app data**; it does not
remove applications from other profiles or solve cross-profile signer conflicts.
The default never uninstalls an existing target app. Newly installed target apps
are removed afterward unless `--keep-app` was used. Temporary helpers must not
already be installed; restore their previous run first.

## Reproduce each thread

The baseline connection, lifecycle and reconnect-fix checks are:

```sh
python3 tools/vpn-probe/run.py run \
  --config /private/path/to/config.json --profile emulator \
  --output /tmp/wave-qa-recovery-001 --allow-root \
  --scenarios steady,crash,disconnect,reconnect,force-stop,reboot
```

The root emulator uses SIGKILL. A debuggable physical build uses a same-UID
SIGKILL; a non-debuggable signed release uses `am crash`, which is reported as a
system-induced Java crash. Recovery is checked without opening Wave or tapping
Connect. Force-stop is tested as an intentional stop, followed by explicitly
labelled manual recovery; automatic force-stop recovery is not claimed.

Omit `--scenarios` with the example emulator profile for the longer capture/DNS
matrix: positive control, steady traffic, process death, disconnect/reconnect,
200-second scoped peer outage, repeated kills with per-UID DNS counters,
Private DNS Off/Automatic/strict, force-stop and reboot. `kill_count`,
`kill_interval`, `restart_timeout`, and `peer_outage_seconds` are configurable.
Repeated-kill attempts that find no process or miss the restart window remain in
the report; they are never silently dropped from the stress test.

Other available scenario names are printed by:

```sh
python3 tools/vpn-probe/run.py list
```

`server-switch` requires `switch_server` and `restore_target` in the selected
profile. `restore_target` is either an exact server name or `best`. Verification
requires the new server and Connected status in a fresh Overview screen. Finding
the server name in search results is insufficient. Choose an open server.

`split-tunnel` requires `split_tunnel_from_all_apps: true`; run it only with an
initial All apps configuration. It exercises exclusion and inclusion of the QA
probe with Android blocking enabled, then restores All apps. `screen-off` and
`doze` cover short background/forced-idle windows; physical unlocks require
`--interactive`. `airplane` temporarily changes airplane mode and restores it.
`handoff` needs a physical phone, active SIM and `--allow-cellular`; it can consume
mobile data. A Wi-Fi capture does not establish protection on cellular.
These short scenarios do not establish overnight reliability or natural memory
pressure behavior.

## Physical Pixel and a controlled gateway

The `pixel` example uses a forwarded ADB server on port 5038. Set these variables
without embedding personal device/network details in source:

```sh
export WAVE_QA_PIXEL_SERIAL=your-adb-serial
export WAVE_QA_GATEWAY_SSH=your-ssh-config-alias
export WAVE_QA_PIXEL_IPV4=the-phone-reserved-ipv4
export WAVE_QA_GATEWAY_IPV4=the-controlled-gateway-ipv4

python3 tools/vpn-probe/run.py run \
  --config tools/vpn-probe/example.json --profile pixel \
  --output /tmp/wave-qa-pixel-001 --interactive
```

Use a controlled Linux host with SSH batch authentication, passwordless scoped
sudo, `nft`, `tcpdump`, `timeout`, `sysctl` and systemd. The example's
`capture.manage: true` temporarily enables IPv4 forwarding, adds a phone-scoped
NAT table and disables redirects. It snapshots original values and arms a
90-minute cleanup timer **before changing forwarding/firewall rules**. A
host-wide QA lock refuses overlapping managed-gateway runs. This is temporary
capture infrastructure, not a change to the desktop VPN app.

`configure_wifi: true` configures the already connected Wi-Fi network through the
Android Settings UI, leaving its password unchanged. It supports an original
DHCP configuration and restores DHCP afterward. Set the correct interface and
prefix in your private config. An unsupported Settings layout fails explicitly.
For an already prepared gateway, use `manage: false` and
`configure_wifi: false`; no gateway forwarding/routing configuration is changed.
Peer-outage injection requires managed mode (or rooted emulator mode).

Capture is filtered to `client_ipv4` and optional `client_ipv6` addresses. IPv6
upstream routing must already exist; the script does not provision an IPv6
router. A native IPv6 pass requires a corresponding successful positive control.
A VPN supplying working IPv6 over an IPv4 uplink does not satisfy that gate.
Other profiles' VPN policies are not changed. Background DNS or matching probe
endpoints observed at a whole-phone gateway are reported without assuming the
originating UID/profile. They prevent a blanket privacy pass.

Physical reboot pauses for the operator to unlock without opening Wave. Initial
process presence is checked before launching the independent traffic probe.
This verifies post-unlock recovery, not pre-unlock leak protection. Without
`--interactive`, that scenario is skipped before rebooting the phone.

## Minimum Android version and 16 KB matrix

Prepare the `Wave_QA_API26` and `Wave_QA_API35_16K` AVDs with the Android SDK tools,
or change the names and explicit emulator serials/ports in a private config.
The runner can start those existing AVDs and stops only AVDs it started:

```sh
python3 tools/vpn-probe/run.py run --boot-avd \
  --config tools/vpn-probe/example.json --profile api26 \
  --output /tmp/wave-qa-api26-001

python3 tools/vpn-probe/run.py run --boot-avd \
  --config tools/vpn-probe/example.json --profile api35-16k \
  --output /tmp/wave-qa-16k-001
```

The 16 KB profile verifies `getconf PAGE_SIZE == 16384`; an APK alignment check
alone is not runtime coverage. The API 26 example requires IPv4/IPv6/DNS while
retaining the other probe failures: the old image rejected Cloudflare's direct-IP
TLS chain in the recorded run. TLS verification is never bypassed. These profiles
are connection checks, not the full failure/capture matrix.

`--build-app` additionally runs the repository's core tests, debug lint, debug
assembly and release assembly. Supply the documented signing variables privately
in the environment if a signed release is wanted. The runner does not fetch
signing passwords from a particular machine's keychain. Without signing inputs,
a production-signature check will reject an unsigned or wrong-signer release.

## Cleanup, interruptions and results

The runner restores DNS/radio/idle settings, requested server selection, VPN
policy, temporary capture rules and files, helper packages and emulator root
state. It records state before changes. If ADB drops or a UI is locked, cleanup
remains pending and the exact restore command is printed:

```sh
python3 tools/vpn-probe/run.py restore \
  --state /tmp/wave-qa-emulator-001/private/state.json
```

Unlock/reconnect the same device first. Restore is idempotent. SIGINT/SIGTERM
attempt cleanup; SIGKILL, power loss or host failure require this command on the
next session. The remote timer limits gateway changes but cannot restore the
phone's Wi-Fi UI without a reachable/unlocked phone. Keep the private state until
cleanup is complete. Do not run other UI automation/instrumentation concurrently.

Exit codes are 0 for passed exercised scopes, 1 for functional/privacy findings,
2 for missing prerequisites or skipped required stages, 3 for pending cleanup,
and 130 for interruption. A run without capture is functional coverage only.
Packet-capture positive control is inserted automatically before protected
captures; a failed control skips subsequent capture assertions. Corrupt/truncated
PCAP data is rejected. Fresh application DNS, background DNS and unexplained
DNS transport are retained as findings; no background-service disablement hides
results. DoH checks validate real responses and compare captured connections to
the privately recorded DoH endpoint addresses.

The runner does not claim to fix observed platform DNS behavior or automate
unperformed release gates such as pre-unlock capture, permission revocation,
natural memory pressure, every other profile/browser or overnight reliability.
See [the recorded follow-up](../../docs/RELEASE-FOLLOWUP.md) for those limits.

`traffic-soak` runs a longer active probe/capture window. Set `soak_seconds`
(60–86400, default 3600) and optionally `soak_max_gap_seconds` (5–300, default 30)
in the private profile. Every required probe must keep producing successful
responses within that gap, including the start and end of the window. Earlier
successes cannot hide a later dead probe or prolonged outage. All capture findings
still fail the result. This is active traffic coverage, not natural idle; a short
run does not establish overnight reliability.
Managed-gateway soaks must finish at least five minutes before the existing
90-minute automatic cleanup deadline; longer runs need an independently managed
gateway or emulator capture. The runner refuses a soak that would outlive that lease.

Transport SYNs to the probe's HTTPS/DoT endpoints remain privacy findings, but
their originating UID is not inferred. In particular, `1.1.1.1:853` is shared
with platform resolvers, so matching that destination does not identify a QA
probe. Marked DNS question payloads provide separate workload evidence. Reports
created before this distinction may call a shared-endpoint SYN `probe_escape`;
retain their captures and failure status, but do not treat that label as UID
attribution.

Offline verification of the runner and packet/report handling:

```sh
python3 -m unittest discover -s tools/vpn-probe/tests -v
```

The [runner validation record](../../docs/device-tests/2026-10-22-runner-validation.json)
records the first complete live run: positive control, steady connection,
process kill, disconnect and reconnect on Android 16. Recovery worked without
opening Wave; the privacy result failed on observed DNS and unsupported packet
forms. Cleanup completed. Gateway, lifecycle and compatibility profiles are
available but were not all rerun through this new harness; earlier manual results
are separate evidence. A script cannot recreate a historical packet trace or
server selection exactly; it repeats the procedure against the supplied APK and
current network, retaining its own evidence and findings.

Disconnect scenarios first establish a connected tunnel and enable the test's
Always-on/blocking policy. Completion requires both the idle UI and Android's
VPN manager reporting the target VPN inactive; an idle button alone is insufficient.
Reports include the sampled action-completion clock and saved policy values after
the window as well as before it. These settings snapshots do not prove continuous
kernel enforcement. The completion clock follows the UI/state checks and is not
a precise kernel teardown timestamp.

`system_dns4_out` / `system_dns6_out` retain the aggregate marked resolver-packet
counts. Additive `default_system_dns*` and `bound_system_dns*` counters distinguish
ordinary resolver requests from requests explicitly bound to an underlying
network. They count captured question packets, including A/AAAA and repeated
questions, rather than unique names. Both workloads still produce privacy
findings. Historical reports and failure states are not rewritten when inspecting
old captures with the new counters.
