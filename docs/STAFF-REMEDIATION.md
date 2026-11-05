# Staff infrastructure remediation

[AirVPN Staff feedback](https://airvpn.org/forums/topic/82017-wave-vpn-open-source-wireguard-client-for-android-and-linux-unofficial-v011/?do=findComment&comment=268064), 2026-11-05.
Independent test authors and implementation delegates used separate worktrees
(Opus5.5 and6.1Sol); failing reproductions were promoted into ordinary offline
regressions after repair. Historical review documents retain their original
findings; the final resolution and runtime scope below supersede open-status text.

## Implemented and tested

- Bounded TCP latency sampling, four workers, location coverage, cached results,
  persistent request/sweep reservations, no connected/connecting/background probes.
  Unknown samples are neutral; explicit choices survive failed TCP samples.
- Latest manifest bootstrap URLs/RSA retained, bounded fair failover including
  embedded recovery, outage backoff, short typo correction with repeated-rejection
  escalation, account and manifest TTLs, cancellation and separate document saves.
- Responsive cached connect, timely manual account refresh, no-handshake recovery
  over a validated physical network, app-lifetime logout/credential deletion,
  consistent marker/connection expiry and explicit cooldown feedback.

## Reproduce

```
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Core and app tests use synthetic encrypted endpoints, fake clocks and bounded
controller fixtures. Only the existing live bootstrap test is opt-in.
[Network policy](NETWORK-POLICY.md), [runtime evidence](qa/STAFF-INFRA-EMULATOR-2026-11-05.md),
and [Best quality](BEST-SERVER-QUALITY.md) record budgets, field checks and limits.
The Mac API37 emulator covered actual refresh/cancellation/lifecycle/connection
behavior. Its recovery-routing defect was repaired and rerun; test packet rules
were removed and the app disconnected. Opus rate-limited before a final report;
parent recovered its evidence and completed the targeted repair/runtime rerun.

## Limits

TCP443 location estimates do not prove UDP reachability or universal Best quality.
No throughput/long-term reliability, physical/OEM/native cellular/lockdown or
Keystore power-loss qualification is claimed. The existing Android DNS caveat
remains. No release/tag or forum reply was made by this work.
