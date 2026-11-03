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

For a Proton emulator account, run this in your own interactive terminal:

```sh
python3 tools/vpn-probe/credentials.py
```

Username and password prompts are hidden. The default destination is
`/tmp/wave-proton-login/account.json`, inside a mode-0700 directory; the file is
mode 0600. Existing files, Git checkouts and symbolic-link destinations are
refused. `--output /another/private-directory/account.json` changes the location.
The helper prints only the saved path. It does not sign in or store a 2FA code.
Remove this temporary file when the emulator sign-in tests are finished.

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

## Comparing installed VPN applications

`compare.py` uses the same independent workloads, positive controls, capture
assertions and restoration as `run.py`. It archives and hashes the installed
base/split APKs without replacing the target or clearing its account. Supply
the installed base APK as `apk`, its verified signer and package, and a private
`comparison_ui` configuration. Sign in beforehand, or configure the optional
authentication selectors below. UI selectors are version
specific and must be validated on the installed app:

```json
"comparison_ui": {
  "overview": [],
  "connect": [{"text": "Connect"}],
  "connected": {"prefix": "Connected"},
  "disconnect": [{"text": "Disconnect"}]
}
```

The installed-app adapter supports positive control, steady, disconnect,
reconnect, crash and force-stop. Other scenarios are explicitly skipped rather
than applying Wave-specific server/settings/status controls to a vendor app.
The Wave runner retains its broader scenario set.

Steps are accessibility selectors, not shell commands. Include confirmation
buttons in the disconnect sequence where needed. A connected label and Android's
active-VPN state are both required. A retained blocking tunnel fails the teardown
precondition rather than being counted as a clean disconnect. Restore an
interrupted comparison with `compare.py restore --state ...`.

For an app that loses its in-memory account when Android revokes its VPN,
`comparison_ui.authentication` can define `needed` (a selector identifying the
signed-out screen), `open` (a sequence of selectors opening sign-in), `username`
and `password` (editable-field selectors), `submit`, and `ready` (a selector only
present after sign-in). Supply the same mode-0600 `credentials_file` format as
the Wave runner. Values travel through private stdin command files, never through
selectors, command arguments or reports. This is used only during explicit
setup/manual connection; crash recovery still must happen without opening the app.
After the unprotected positive control, the installed-app adapter explicitly
restarts the target before establishing the protected baseline. This clears
service sessions revoked by Android when disabling Always-on. That preparation
restart is recorded in the positive-control result and is never used to pass
process-death recovery. Connected-start checks apply independently to stop,
outage, DNS-mode and lifecycle scenarios; reconnect verifies an inactive start.

`matrix.py` runs profiles sequentially, rotates their order between repetitions,
and gives each run a separate output directory so no failed capture is replaced:

```sh
python3 tools/vpn-probe/matrix.py --config /private/path/comparison.json \
  --profiles wave,eddie,proton --repetitions 3 \
  --scenarios steady,disconnect,reconnect,crash,force-stop \
  --output /tmp/vpn-comparison-001
```

[`comparison.example.json`](comparison.example.json) supplies the exercised
English UI selectors for Eddie 4.1.0 and Proton 5.20.98.0. It uses the same
controlled-gateway variables as the Pixel example above. Also set
`WAVE_QA_EDDIE_BASE_APK`, `WAVE_QA_EDDIE_SIGNER_SHA256`,
`WAVE_QA_PROTON_BASE_APK` and `WAVE_QA_PROTON_SIGNER_SHA256` to the retained
installed base APKs and verified certificate digests. Proton must already be
signed in and on Home; dismiss its first-connection notification suggestion
before testing. Eddie needs notifications enabled to avoid its launch modal.
Record and restore permission changes separately. The runner never signs out,
clears data or replaces a comparison target. App updates may require new selectors
and new base APKs; a mismatched installed base is rejected.

Profiles with `comparison_ui` use the installed-app adapter; other profiles use
the Wave runner. Capture is required. Missing prerequisites, incomplete stages
or pending cleanup stop the matrix before another target starts. Privacy
failures remain failures while subsequent completed comparisons may proceed.
Multi-app matrices require starting with no owner-profile Always-on VPN. Save
and disable the initial policy in Android Settings first, and restore it after
the matrix. This prerequisite is checked before any run: each child restores
its starting policy, so an existing Always-on owner would otherwise prevent
the following app from connecting. Other profiles' policies remain untouched.
An app lacking IPv6 must retain that functional failure or explicitly declare a
separate IPv4-only scope; never silently loosen required probes for a comparison.
These tools do not turn unexercised pre-unlock, memory-pressure, native IPv6 or
cellular scenarios into passes.
The matrix copies and hashes its runner before launching any child. It executes
that snapshot for every run. Ctrl-C/termination is forwarded once to the active
child and waits for its cleanup instead of immediately killing it; abrupt host
failure still requires restoring the child's saved state.

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

## Temporary lab host hotspot

`hotspot.py` creates a leased WPA2 AP on an **unused, down, unnumbered** Linux
radio and bridges it to an existing LAN bridge. It never changes the wired
uplink, persistent network files, DHCP, firewall or forwarding settings. The
host needs `hostapd`, `iw`, `ip`, systemd and an AP-capable radio; root SSH works
without sudo, or use `--sudo` for a sudo-enabled account. Inspect the live host
and confirm these interface names before use:

```sh
python3 tools/vpn-probe/hotspot.py start --ssh root@SIGMA \
  --interface wlp0s20f3 --bridge vmbr0 --minutes 90 \
  --output /tmp/wave-qa-ap-private
python3 tools/vpn-probe/hotspot.py status --ssh root@SIGMA
python3 tools/vpn-probe/hotspot.py stop --ssh root@SIGMA
```

The new output directory is private. `account.json` contains the generated Wi-Fi
password; keep it outside Git and do not paste it into chat or shell history.
Connect the test phone to `Wave-QA-Sigma` using those credentials. Only one
station is allowed. Save its original Wi-Fi network first; after testing, reconnect
to that network and forget the QA network. AP expiry restores the radio but cannot
restore a disconnected phone or remove the private local credentials for you.
A failed start preserves private state; run `stop` before retrying. Expiry leaves
its runtime directory until `stop`, preventing accidental reuse of stale state.

For a capture profile use `kind: "gateway"`, `ssh: "root@SIGMA"`, `sudo: false`,
`interface: "wlp0s20f3"`, `manage: false`, `configure_wifi: false`, the DHCP-assigned
`client_ipv4`, and `gateway_ipv4` equal to the LAN router. Add `client_mac` with
the phone's **current per-network randomized Wi-Fi MAC**. This captures Ethernet
traffic for that station and attributes outgoing IPv4/IPv6 by MAC, including
unknown/changing IPv6 privacy addresses. Without it, all `client_ipv6` addresses
must be explicitly supplied; IPv4-only capture cannot establish IPv6 protection.
The parser still reports unsupported fragments/extension headers as findings.
A MAC change requires a new validated profile and positive control.

A bridged hotspot inherits the LAN's IPv6 capability. Link-local addresses,
Tailscale ULA addresses, and IPv6 success inside a VPN do not prove an IPv6
Internet uplink. Native dual-stack, IPv6-only/NAT64 and local hostile RA/DNS tests
need separately provisioned networks and working unprotected controls. Do not
inject experimental RA/DHCP into the household bridge. Wi-Fi reconnect/outage,
MTU/fragmentation and overnight tests can use this capture point, but each needs
its own exercised scenario and evidence. Cellular traffic is invisible here.

`wifi-reconnect` works for Wave and installed-app comparison profiles. It requires
mobile data disabled, verifies Wi-Fi really disabled, holds it off for ten
seconds, then requires reconnection to the same BSSID. The independent probes
and external capture continue throughout the 60-second window; required traffic
must recover without opening/reconnecting the VPN app. Saved protection settings
and usual packet findings still apply. This exercises phone Wi-Fi radio recovery,
not AP power failure, roaming, cellular capture or DHCP/IPv6-renumbering coverage.

## IPv6 uplink through your VPS

`dualstack_hotspot.py` is an alternative to the bridged AP. It creates a private
network namespace on lab host, moves only the unused Wi-Fi radio into it, and
routes IPv4/IPv6 through a temporary WireGuard interface whose transport socket
stays on lab host's ordinary uplink. An IPv6-capable VPS supplies the public exit.
The phone receives DHCP IPv4 and an IPv6 SLAAC ULA prefix; the VPS applies NAT44
and NAT66. This is a **tunneled dual-stack uplink with NAT66**, not native ISP
IPv6 or a public IPv6 prefix delegated to the phone. Tor cannot substitute for
this because its SOCKS proxy does not support general UDP forwarding.

Before use, verify the VPS's IPv6 Internet access, uplink interface, SSH host key,
sudo access and firewall conventions. The implementation currently targets an
iptables-nft/ufw VPS with an existing IPv4 forwarding setup, and uses temporary
narrow INPUT/FORWARD rules, its own NAT table, and saved/restored IPv6 forwarding.
It never flushes a shared ruleset. Both hosts receive independent 90-minute
cleanup timers. The VPS must have `wg`, `ip`, `iptables`, `ip6tables`, `nft`, `ss`
and systemd; lab host additionally needs `hostapd`, `dnsmasq`, `iw` and `tcpdump`.
Stop the bridged hotspot first, confirming the radio is down/unbridged/unnumbered.
Do not run two QA hotspot owners concurrently.

```sh
python3 tools/vpn-probe/dualstack_hotspot.py prepare \
  --sigma-ssh root@SIGMA --vps-ssh user@VPS --vps-ipv4 VPS_IPV4 \
  --vps-ssh-port 22 --known-hosts /path/to/pinned_known_hosts \
  --output /tmp/wave-qa-dualstack-private
python3 tools/vpn-probe/dualstack_hotspot.py start-ap \
  --output /tmp/wave-qa-dualstack-private
python3 tools/vpn-probe/dualstack_hotspot.py status \
  --output /tmp/wave-qa-dualstack-private
# After the matrix (also required after a partial setup):
python3 tools/vpn-probe/dualstack_hotspot.py stop \
  --output /tmp/wave-qa-dualstack-private
```

The defaults use `wlp0s20f3` / `phy0` on lab host, `eth0` on the VPS, namespace and
interface `waveqa6`, UDP port 51876, IPv4 `10.77.24.0/24`, and IPv6
`fd42:77:24:2::/64`. Supply `--radio`, `--phy` and `--vps-interface` as necessary;
check for subnet/port conflicts before setup. `prepare` requires a fresh private
output directory. `start-ap` writes `dualstack-account.json`; join the phone
with those credentials. If replacing a QA network with the same SSID, forget the
old QA configuration first so an old saved password is not reused. Preserve the
phone's original household Wi-Fi configuration.

Use `namespace: "waveqa6"` in the gateway capture configuration, `sudo: false`
for root SSH, `manage: false`, `configure_wifi: false`, `gateway_ipv4:
"10.77.24.1"`, and the phone's actual DHCP IPv4/MAC. Add
`required_capture_families: ["ipv4", "ipv6"]` at the profile level. This fails
setup unless the unprotected capture proves both families (including marked
raw DNS and DoT plus successful IPv6 application traffic). An IPv6 failure is
retained instead of silently degrading to an IPv4-only comparison. Capture on
the Wi-Fi interface **inside the namespace**, before the outer VPN, so inner
VPN leaks cannot be hidden by the test uplink tunnel. The regular packet
assertions and unsupported-fragment findings remain unchanged.

`stop` independently attempts both hosts, restores the VPS forwarding value,
removes only the QA firewall rules/interfaces, returns the radio to managed/down,
and deletes local tunnel keys and AP credentials after successful cleanup. A
cleanup failure retains the private state for retry. Expiry leaves runtime
journals for inspection; invoke `stop` before another setup. Reconnect the phone
to its original network and forget the QA network. Local private error logs can
contain configuration; never publish them.

For this tool's exclusive, password-protected one-station AP, prefer
`ap_mac: "AP_RADIO_MAC"` instead of `client_mac`. Capture then retains all frames
on the dedicated Wi-Fi interface and classifies outgoing station traffic by
excluding the AP's own source MAC. This survives per-connection randomized phone
MACs and DHCP/IPv6 address changes. **Never use this mode on a shared uplink or
bridge, or an AP with other clients**: its ownership assumption is exactly one
station. Fixed `client_mac` remains appropriate elsewhere, but Wi-Fi recovery
must retain that identity or fail. Save the AP's actual radio MAC in the private
profile; do not substitute the wired bridge MAC.

The isolated AP advertises a 600-second IPv6 router lifetime (the tested phone
rejects lifetimes below its configured 180-second minimum), uses MTU 1380, and
clamps forwarded TCP MSS to the tunnel path MTU inside its own namespace. These
are test-network settings; no phone security sysctl or VPN implementation is
changed. See the [WireGuard namespace explanation](https://www.wireguard.com/netns/)
and [Tor's SOCKS protocol limitations](https://spec.torproject.org/socks-extensions).

If another saved Wi-Fi network wins selection after a radio cycle, set
`wifi_reconnect_credentials_file` to the private hotspot account JSON. The runner
then explicitly reselects that saved test network after enabling Wi-Fi; it does
not open or reconnect the VPN app. The result records
`explicit_wifi_network_selection: true`, so it cannot be mistaken for unattended
Wi-Fi auto-selection. The password travels over private ADB stdin, not host
command arguments. Without this option the phone must return to the captured
BSSID on its own. A different BSSID or a changed fixed capture MAC fails the test.

## External emulator Ethernet capture

For an independent capture outside Android, the emulator console can write a
host-side PCAP of its virtual Ethernet LAN. **Do not assume this captures the
emulator's separate Wi-Fi path.** The 2026-10-24 control showed Wi-Fi probe
traffic absent from this host capture. For the exercised setup, save the Wi-Fi
state, disable Wi-Fi, use `capture.interface: "eth0"` in private runner profiles,
and require fresh unprotected controls at both capture points before testing.
Keep the ordinary Android-interface capture as a second observation.

Use the profile's explicit ADB host, server port and serial on every command.
For example (replace the serial if needed):

```sh
adb -H 127.0.0.1 -P 5037 -s emulator-5554 shell settings get global wifi_on
adb -H 127.0.0.1 -P 5037 -s emulator-5554 shell svc wifi disable
adb -H 127.0.0.1 -P 5037 -s emulator-5554 emu network capture start comparison-001.pcap
# Run the comparison matrix with capture.interface set to eth0.
adb -H 127.0.0.1 -P 5037 -s emulator-5554 emu network capture stop
# Restore the saved Wi-Fi state after all tests and runner cleanup.
```

The current console requires a **bare filename**, and returns the host path
under the AVD's `console_out` directory. A capture file may remain buffered until
stopped. Move it into private storage, restrict its permissions, and retain its
hash. Raw captures may contain account/device/network data and must stay out of
Git. Do not stop someone else's capture or overwrite an existing artifact.

Retain both directions without a DNS-only filter. Slice the completed host
capture by each report's `window_start_unix_ms` / `window_end_unix_ms`; verify
clock alignment using the positive control. Classify outgoing frames using the
actual guest Ethernet MAC, retaining incoming frames separately. The host
capture starts earlier than each Android-interface capture, so packet totals
need not be identical. Check that fresh marked ordinary resolver and raw DNS
controls appear externally, and that replies and successful application probes
prove reachability. IPv6 attempts on the LAN, or IPv6 working inside the VPN,
do not establish an unprotected IPv6 Internet path.

Finish/restore interrupted runner state before starting another app. A device
panel snapshot helper using UiAutomation can conflict with the QA driver;
finish its automation session before running the matrix. The live device stream
can remain open. Dismiss vendor first-connection prompts during preparation,
then retry into a fresh output directory; a setup failure is never a protection
pass. Restore any temporarily granted permissions separately.

For a targeted rerun of failed Private DNS modes, set `private_dns_modes` in the
private profile to a nonempty list drawn from `off`, `automatic`, and `strict`.
The default still exercises all three. For example, `["automatic", "strict"]`
skips a previously passing Off window while retaining setting restoration on
errors. External captures still require a fresh positive control.

For an app that intentionally retains a blocking VPN on lockdown Disconnect,
set `blocking_vpn_session` to its exact Android VPN session label. The driver
still requires the disconnected UI, lockdown enabled, the target owner package,
and that exact non-bypassable session. An ordinary active encrypted VPN is not
accepted. This only validates the action; the independent blocked probes and
external packet findings still decide privacy results. Omit this setting for
apps that tear down their VPN on Disconnect.

### Samsung replacement verification and DNS baseline

Some Samsung builds retain the old VPN `TransportInfo.sessionId` after an
in-place replacement. With `blocking_vpn_session` enabled, the runner verifies
the active owner, non-bypassable policy, lockdown and the live blocking link's
IPv4/IPv6 addresses, DNS and default routes. The session name alone is no longer
accepted as evidence. External capture and blocked application probes remain
required; a matching link does not itself prove privacy.

For a controlled follow-up, `private_dns_baseline: "off"` or `"automatic"` sets
that mode after fresh unprotected positive controls and before the protected
scenarios. Omit it to preserve the original baseline. Cleanup restores the saved
mode/specifier even after failure; the selected baseline is recorded in the
filtered report. This option tests an OS setting, not an app-enforced workaround.

`expected_wifi_bssid` pins observations to the controlled AP: the runner checks
association before capture and at the window end and fails if it changed.
These are samples, not continuous coverage. Set `wifi_reconnect_credentials_file`
to the **current** hotspot's private account file; an old file may reconnect the
phone to a different saved network and invalidate gateway capture assumptions.
Do not run the QA UI driver concurrently with an active screen-automation
session. Release only your own session while the driver runs, then reopen it
for restoration; restarting ADB or device services is unnecessary.
