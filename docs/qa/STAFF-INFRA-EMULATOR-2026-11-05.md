# Staff infrastructure Android runtime checks — 2026-11-05

Scope: API37 Mac emulator, debug application, source6c678f7 plus the recovery-network
fix described below. No physical/OEM/lockdown qualification is claimed.

The independent Opus acceptance run passed the focused R1–R4/A1–A5 review and
core/app tests, lint and assembly. It then exercised the actual emulator using
app-UID packet logging and emulator-only controlled failure rules. The provider
rate limit stopped it before a final report/commit. Parent recovered its thread
records and continued; unfinished reviewer output is not a clean final review.

## Recovered runtime evidence

- Five rapid refresh taps produced no additional bootstrap connections and showed
  remaining cooldown seconds. Offline refresh made three attempts and retained
  cached servers. Relaunch retained cooldown with zero new connections. Network
  restoration recovered with one successful bootstrap connection.
- Connected refresh used the tunnel; no latency probes appeared on any interface.
  Rotation preserved the sweep, background stopped it.
- Explicit connection to a server whose TCP sample had failed still handshook in
  approximately one second, without new probes/bootstrap requests.
- Best marker and actual connection selected the same server for the checked
  snapshot. This is consistency evidence, not optimality evidence.
- With WireGuard UDP deliberately dropped, no-handshake recovery chose an unusable
  cellular network despite validated Wi-Fi. Android bindSocket failed with EPERM
  before packets, consuming outage budget. Disabling cellular allowed the recovery
  request over Wi-Fi while the dead tunnel remained installed. This is a real
  runtime failure, not a passing scenario.
- Logout during hung bootstrap deleted encrypted credentials in about0.2s,
  showed sign-in, sent no later failover attempt, and stayed signed out on relaunch.
  The test's encrypted sandbox backup was restored afterwards.
- Debugger-induced suspension was test contamination and is excluded from findings.

## Parent repair and verification

Recovery now accepts only validated physical networks, prefers the active physical
network then Wi-Fi, and never chooses the VPN itself. Added three JVM regression
cases including unusable cellular before usable Wi-Fi. A failed recovery with an
existing WireGuard tunnel retains Stalled status and its Disconnect action instead
of falsely showing a fully disconnected state.

Parent checks:87 core tests (one live-only skipped),8 app tests,zero failures;
lint and debug assembly pass. APK SHA256: `522c5d29a07f5f001664798fa5c39ef526cdaaff1abb7fab2f73199bbfbfa553`.

The emulator-only failure/logging chains must be removed after runtime completion.
Final recovery rerun: with cellular present and UDP denied, selecting another server
from the stalled session triggered one bootstrap connection on wlan0, followed by
new WireGuard attempts. Removing the UDP denial produced a successful handshake
and Connected status. No cellular binding failure was observed with the fix.
Both emulator-only WAVEQA/WAVEFAIL chains and their OUTPUT jumps were removed.
The final patched app was disconnected afterwards; signed-in account data retained.

Limitations: no physical device, OEM DNS leak qualification, native cellular or
lockdown recovery claim. The retained-tunnel error-state fix is reviewed and built;
its exact failed-recovery branch was not separately fault-injected after this patch.
No full-fleet or throughput measurement was used by the parent runtime rerun.
