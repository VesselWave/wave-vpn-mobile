# Emulator lifecycle investigation — 2026-10-23 UTC

> **In short:** A closer look at the emulator DNS-during-disconnect results: the leaks came from ordinary app lookups too, not only an explicit-network probe. No app fix or exact platform cause found.

The release gate remains **blocked**. No production lifecycle fix or exact
platform DNS cause is established. This investigation changes QA evidence and
preconditions only; it leaves the app, signed candidate and user VPN preferences
unchanged. Android HEAD remains `265dd5b` on `release/readiness-20261023`.

## Historical evidence and runner semantics

The original API 37.0 and 37.2 disconnect captures each contain eight marked
resolver question packets: four from ordinary `InetAddress.getByName` and four
from the explicit underlying-network `Network.getAllByName` worker. Each workload
contains two fresh names, with both A and AAAA questions. Thus the historical
finding is not solely an explicit-network probe effect. These are packet counts,
not eight unique names or successful DNS responses. Both workloads reported no
successful resolution during those disconnect windows.

The runner's disconnect action opens Wave and taps Disconnect; it does not
disable Always-on or blocking. Its separate capture positive control deliberately
disables protection, then restores protection before protected scenarios. Existing
reports sampled the saved policy at window start only. An independent disconnect
scenario also previously relied on whatever connection a preceding scenario left
behind, and accepted idle UI state without checking Android's VPN manager.

QA changes now establish a connected start for disconnect, require Android to
report the target VPN inactive after idle UI appears, record the action-completion
clock and saved policy after the observation, and split default/bound resolver
counters while retaining the aggregate and all privacy-failure criteria. No old
report or failure state was rewritten. All 65 original captures and 11 original
report hashes still match the Round 3 record.

## New exercised evidence

The same production-signed candidate was used: APK SHA-256
`3636d6522f23958a511c1b74c00f16cd0c7ac52e7dafb267304f2a3b0f6785b8`.
All new runs exited 1 and retain their failures. The filtered
[evidence manifest](device-tests/2026-10-23-lifecycle.json) includes exact report,
PCAP, raw-log and source-snapshot hashes, packet times and sampled state transitions.
Private evidence is under `/tmp/wave-android-lifecycle-20261023`.

- `api37`: Google APIs API 37.0, 4 KB pages, fingerprint observed as
  `google/sdk_gphone64_arm64/emu64a:17/CE2A.260420.051.A3/16571990:userdebug/dev-keys`.
  Positive control, steady and reconnect passed their exercised checks.
  Disconnect failed on one unassigned cleartext DNS packet; no marked resolver
  or raw-probe DNS was observed outside the tunnel in this run.
- `api37-2`: Google APIs API 37.2, 16 KB pages, recorded platform properties in
  private scratch. Positive control and steady passed. Disconnect failed on one
  unassigned cleartext DNS packet and unassessed packet forms; reconnect failed
  on unassessed forms. No marked probe DNS escape was observed in this run.
- `api36`: existing Android 16 emulator on explicit local server port 5037,
  serial `emulator-5554`. Positive control passed. Root SIGKILL caused process
  replacement without opening Wave, measured by the runner at 1.125 seconds.
  Crash failed on two unassigned DNS packets and unassessed forms. Disconnect
  and reconnect failed on unassessed forms. No marked probe DNS escape was
  observed in these new crash/disconnect samples.

The API 37 runs used dedicated ports 5560 and 5562, without saved-snapshot loading.
The device-panel attempt selected occupied port 5554 and returned: “The emulator
process for \"Wave_QA_Current_API37\" was killed by SIGSEGV before coming online.”
The replacement launch used a separate headless port. Only newly launched
processes were terminated; the existing Android 16 emulator was kept running.

## Correlation and its limits

A private wrapper sampled the device clock, saved policy, Wave PID, `ip link`
and VPN/service dumps alongside epoch logcat and packet capture. Sampling uses
sequential shell commands, so a sample is an interval rather than an atomic
snapshot. A service record does not by itself prove a live service process.

On both API 37 disconnect runs, Wave's PID remained unchanged while `tun0` and
the connection-service record disappeared. Every sampled policy pair during
these windows still identified Wave with blocking enabled. Logcat recorded:

- API 37.0: backend DOWN at `1792720620533` ms; Android VPN disconnected at
  `1792720620548` ms; unassigned DNS at `1792720620621.177` ms.
- API 37.2: backend DOWN at `1792720792116` ms; Android VPN disconnected at
  `1792720792141` ms; unassigned DNS at `1792720792258.267` ms.

Thus these specific DNS packets occurred approximately 73 ms and 117 ms after
Android's logged VPN disconnection. The observations establish actual tunnel
teardown while the saved blocking preference remained enabled. They do **not**
establish continuous kernel rule enforcement, identify the unmarked packets'
UID, or prove an exact resolver/routing mechanism. The API 36 samples separately
recorded the old PID absent, then a new PID and tunnel present after SIGKILL.

The earlier eight marked packets on each API 37 image were not reproduced in
these fresh samples. Cold boot, workload timing and additional sampling differ
from the earlier full matrices. Absence in these samples is not a fix or a
reason to erase the earlier failures. API 37.0 loaded the original runner;
API 37.2 loaded the additive counters/timing and connected-start changes;
API 36 additionally loaded the VPN-inactive completion check. The private source
snapshots are reconstructions from HEAD and known edit order, not source dumps
captured at process launch. The raw logs and captures are original.

## App mitigation decision and next experiment

No production change is justified by these measurements. The app already keeps
an installed tunnel during unanswered handshakes and monitor exceptions. Explicit
disconnect cancels the connection loop, serially brings WireGuard DOWN and stops
its foreground connection lifetime. Reversing foreground-service stop order is
an untested mitigation hypothesis, not a demonstrated fix. Keeping a blocking
TUN while displaying Disconnected would require a deliberate product/lifecycle
design and still cannot retain this process's TUN across SIGKILL. No firewall
rule, resolver disablement or lockdown-preference change was introduced to hide
the findings.

The next actionable limit is a timing-controlled comparison of the existing and
alternative teardown order with in-flight ordinary resolver requests, logging
both service and TUN events. Resolving the platform mechanism additionally needs
source-version-matched resolver/netd and kernel routing/socket-mark evidence at
the actual transition, including effective UID blocking rules and DNS socket
ownership. Saved settings and post-event counters alone cannot prove that chain.
An alternative order must improve repeated captured ordinary-DNS results before
being proposed as a mitigation; it cannot close the process-death gate by itself.

## Validation and cleanup

Six offline Kotlin protocol tests passed; the optional live bootstrap test was
skipped. Debug lint and assembly passed. The final runner suite passed 29 tests,
including ordinary/bound DNS separation, a required connected disconnect start,
and rejection of idle UI while Android still reports an active VPN. The three
release-tool tests also passed. Log hashes are in the evidence manifest.

All three new runs report completed cleanup without errors. The dedicated API 37
emulators were stopped by their owning runs. The existing Android 16 emulator
remains running, ADB UID 2000, original Always-on target `null`, blocking `0`,
Private DNS mode `null`; its original development/remote packages remain and
temporary release/probe/driver packages are absent. No physical phone, gateway,
Linux repo, credentials or signing material was modified. No commit, push, tag,
publication or release was performed. Physical, native IPv6/cellular, LMK,
pre-unlock and overnight gates remain outside this investigation.
