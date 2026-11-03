# Controlled emulator teardown comparison — 2026-10-23 UTC

The release remains **blocked**. Reversing foreground-service/tunnel teardown
order did not eliminate marked ordinary DNS outside the VPN. No production app
change is justified by this bounded experiment. Candidate source remains
`3ea82a6da625c01f44988338f2da9abcb45e81e2`; no commit, push, tag or release was
performed. Previous reports and failed captures remain intact.

## Design and safety assessment

The current Disconnect requests `ConnectionService` stop, cancels the connection
job, then acquires the backend mutex for explicit DOWN. Cancellation of the
existing connection also performs DOWN in a noncancellable IO context. The
alternative delays the service-stop request until cancellation cleanup and
explicit DOWN have returned, before updating UI state.

That alternative can be compared for isolated serial Disconnects, but simply
moving the call is not safe for production by inspection. A new Connect starts
the foreground service before acquiring the backend mutex; an older delayed
Disconnect could stop that newly requested service. Cancellation before the
Disconnect acquires the mutex can also skip the delayed stop. Neither order can
execute after SIGKILL. No persistent blocking TUN or changed Disconnected
semantics was introduced.

A private source copy, archived from the candidate before modification, added
debug-only trigger instrumentation and event logging. Both modes used the same
APK: SHA-256
`0eacf40e886fa08b923a39a62b920acf154cf7b26d04aeea3d34b3fa286d973f`,
package `me.vwave.vpn.dev`. It was not the production-signed release APK. The
mode was selected using a private app file. No production source was edited.

The fixed sequence was three current-order samples followed by three alternative
samples, on one cold-booted API 37.0 ARM64 emulator, 4096-byte pages, port 5560.
Fingerprint:
`google/sdk_gphone64_arm64/emu64a:17/CE2A.260420.051.A3/16571990:userdebug/dev-keys`.
No API 37.2 or physical-device comparison was added.

Each sample required Connected, Always-on/blocking enabled, a two-second settling
delay and a 25-second capture. After capture started, the runner armed the
ordinary DNS worker. That worker recorded a fresh lookup beginning and sent an
explicit debug Disconnect broadcast ten milliseconds later. Receiver and lookup
timestamps establish that the request was pending when Disconnect arrived in
all six samples; three lookup ends were right-censored by observation/process
lifetime. Other original probe workloads, including bound DNS, remained active.
The capture positive control passed before protected samples. Protected windows
did not disable blocking; its saved value remained enabled in every sample.

## Results

Packet counts below are marked resolver question packets, including A/AAAA,
not unique names or successful answers:

- Current order: ordinary DNS counts **2, 0, 2**; bound DNS **2, 0, 0**.
- Alternative order: ordinary DNS counts **2, 0, 0**; bound DNS **2, 0, 0**.
- The first alternative sample also contained one unassigned cleartext DNS
  packet. Its UID is not assigned.
- All six protected reports failed. Each retained a nonprobe DoT-transport
  finding; three additionally failed on marked DNS escape. Endpoint transport
  alone does not establish a workload or UID. The runner exited 1.

The lower observed count in the alternative group is not evidence of a reliable
improvement: leaks varied within the baseline, the small groups were sequential,
and the alternative itself reproduced the failure. No additional rounds were run.

The first alternative sample provides a concrete ordering counterexample:
Android logged VPN disconnection at `1792721660229` ms; marked ordinary DNS
appeared at `1792721660234.646` and `1792721660236.873` ms. The alternative
requested service stop at `1792721660262` ms and logged service destruction at
`1792721660266` ms. Thus those marked packets preceded even the service-stop
request in the alternative. Retaining that foreground service until backend DOWN
returned did not prevent this escape.

## Correlation and limits

Epoch logcat, sequential device-clock/state/socket/routing samples, and start/end
iptables, ip6tables, netd and VPN-manager dumps accompany every observation.
Every sampled saved-policy pair remained protected; every sampled `ip rule`
output included prohibit rules. These interval observations do not establish
continuous kernel enforcement at every packet.

For all ten marked question packets across the three leaking samples, subsequent
`ss` samples matched both local and remote UDP address/port tuples to sockets
under UID **10232**, independently identified as the probe package. The sampled
marks were `0xe0067`, `0xe0069` and `0xe006a`, respectively. First matching
observations followed the packets by approximately 56–83 ms and matching sockets
remained observable for seconds. The sampled prohibit rules select an unset
`0x20000` mark bit, while these sampled socket marks have that bit set. Routing
output also retained rules referring to the former VPN network identifier.

This is stronger socket/routing correlation than saved policy alone. It does
not read the mark from the captured packet, prove socket state at the exact send,
or establish which platform operation created or retained that mark/routing state.
No kernel trace or source-version-matched resolver/netd causal audit was added.
It does not revise the older API 36 UID0 observation into a proved mechanism.

Debug logging, an explicit broadcast and the private ordinary-DNS trigger alter
scheduling relative to release UI taps. Serial readiness and timing controls do
not establish safety under rapid Connect/Disconnect, process death, API 37.2,
physical/mobile networking, pre-unlock, memory pressure or overnight operation.
The experiment ends here with those scopes unresolved.

## Evidence, validation and cleanup

The [filtered manifest](device-tests/2026-10-23-teardown-order.json) records
per-sample events, packets, socket correlations, policy checks and exact hashes
for the runner, source manifest, candidate archive, APKs, captures and raw logs.
Private evidence is `/tmp/wave-android-order-20261023`. Source bytes were captured
before launch and their hashes rechecked afterward; these are not reconstructed
snapshots. The private wrapper is `compare.py`; it imported only its disposable
source copy. Its invocation used `ADB_SERVER_SOCKET=tcp:127.0.0.1:5037`, profile
`current`, `--allow-root --boot-avd --scenarios disconnect` and the private config.

The disposable build passed `:core:test`, `:app:lintDebug` and
`:app:assembleDebug`; the optional live bootstrap test was skipped. The unchanged
repository runner suite passed all 29 tests. No production Kotlin change or new
dependency/reference reuse requires a production mitigation validation claim.

The Device-panel attempt failed with “Device boot failed: Could not communicate
with device support. Try refreshing devices.” Inspection showed it had launched
the requested AVD on occupied port 5554. Only that newly launched process was
stopped; the existing emulator was untouched. Dedicated port 5560 was then used.
The experiment runner reported complete cleanup with no errors, removed the
newly installed target and helpers, restored settings/root state, and stopped
its emulator. There was no preexisting target APK. An initial inspection
misread the runner's reset `target_created=false` as a preexisting app; cleanup-only
boots then verified that the target, helpers and its private mode marker were
already absent. The final check recorded ADB UID 2000, Always-on target `null`,
blocking `0`, and Private DNS mode `null`. All owned emulator processes were
stopped; the existing API 36 emulator remained online. No physical
phone, gateway, host networking, Linux files or signing material was changed.
