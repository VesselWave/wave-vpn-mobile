# Independent recheck: network policy fixes (9320cd6)

This is a fresh offline recheck of 9320cd6 ("Fix Android bootstrap recovery, refresh budgets and
responsive selection"; ancestors f331867 and eca93f0). It checks each finding in
[NETWORK-POLICY-REVIEW.md](NETWORK-POLICY-REVIEW.md) against the code the app actually runs
(`AirRepository`, `TunnelManager`, `AppViewModel`, `MainActivity`, `QuickTile`), not only the core
policy classes.

No production code was changed. No AirVPN servers were contacted, and no device or emulator was
used. Device behaviour and the real-world quality of "Best" are still open (see Limits).

## Results

- Required checks: `./gradlew :core:test :app:lintDebug :app:assembleDebug` passes. Before this
  recheck: 74 tests, 73 passed, 1 skipped (`LiveBootstrapTest`, opt-in). After: 87 tests, 0 failures,
  6 skipped (the live test plus five opt-in recheck repros). Lint reports 17 warnings and no errors.
- The fixer's claims hold as stated. All 11 `ReviewFindingsTest` repros run unconditionally and pass.
  `FixPolicyTest` adds 12 tests. The only change to an existing assertion is in F2b
  (`budget = 1`). That change is needed: with location sampling, a 24-socket sweep reaches every
  fixture country, so the "unsampled country" case only exists under a smaller budget. It does not
  weaken the test. The two edits in `LatencyLoadRegressionTest` follow the move from server
  sampling to location sampling and still check the same rule (nothing measured after the route
  changed gets published).
- New findings: R1 is high severity, R2 medium (depends on the platform), R3 and R4 low. Each fails
  on 9320cd6 in `RecheckFindingsTest`. A1–A5 are app-wiring issues found by reading the code; the app
  module has no JVM tests, so they cannot be reproduced here.

```sh
./gradlew :core:test :app:lintDebug :app:assembleDebug          # green
WAVE_RECHECK=1 ./gradlew :core:test --rerun-tasks --tests '*RecheckFindingsTest'   # 5/5 fail on 9320cd6
```

## F1–F14 in the app wiring

| Finding | Status | Where it is wired / notes |
|---|---|---|
| F1 URLs 4–6 | Fixed | `AirApi` orders URLs by failure count and then by last retry time. The single `AirApi` lives in `AirRepository`. URL 6 alone is reached within 4 minutes (`RecheckPolicyTest`). Endpoint health is per process (documented). |
| F2 partial sampling | Fixed; see R4 | Unmeasured servers score at the median latency. One probe per location, spread across countries. `AppViewModel.refresh` passes favorites, the last server and the target's servers as priority. The Servers screen uses `Scoring.scores`/`rank`. |
| F3 explicit choice | Fixed | `Connection.kt:177` uses `allowFailedFallback = target != Target.Best`. When every Best candidate has failed, the user sees the generic "Check account, server and WireGuard settings" (A5). |
| F4 signed-out refresh | Fixed | Signed-out `update` returns before arming the cooldown. `QuickTile` and `TunnelManager.connect` both check `isLoggedIn`. |
| F5 embedded recovery | Fixed, but introduces R1 | `recovery = Defaults.bootstrap`, `fallback = vault.readBootstrap()`. |
| F6 account changes | Manual refresh fixed; the no-handshake path does not work (A1) | `force` refetches `user` after 15 minutes. |
| F7 typo lockout | Fixed; see R3 | Fixed 5 s wait, and the sign-in screen shows the remaining seconds. |
| F8 cancel demotes endpoint | Fixed when the interrupt flag survives; see R2 | |
| F9 account discarded | Fixed | The account snapshot is saved before the manifest request. |
| F10 slow connect | Fixed; see A2 | A cached connect starts the refresh in the background with a 3 s limit. |
| F11 overlapping sign-in | Fixed | Throws "busy". The sign-in screen shows its generic network message, not "Signed in". |
| F12 sign-out waits | Fixed; see A3 | `logout()` cancels `activeJob`, then `api.cancel()`, then takes the mutex. |
| F13 connect waits for sweep | Fixed | `Connecting` is set before `pinger.stop()`. `cached()` reads a volatile snapshot. |
| F14 rotation wastes sweep | Fixed | `onStop` checks `isChangingConfigurations`. The sweep runs in `viewModelScope`, which survives rotation. |
| Sign-out/sign-in resets budget | Fixed | One `RefreshSession` per process, with the budget kept in `wave-network-budget`. 600 restart+sign-in cycles in an hour send at most 61 `user` requests (`RecheckPolicyTest`). Side effect: signing back in within 60 s of any request shows "Wait N seconds". |

## New findings

### High

**R1: after a short offline period, the latest manifest routing is never tried again in that process.**
- Where: `Protocol.kt` `requestWithFallback`, in the `recover` ordering.
- What happens: offline, every stored URL fails within two attempts, so `recover` becomes true. The
  sort then puts all six embedded routes first. With the 3-attempt cap, and endpoint cooldowns of at
  most 120 s (always shorter than the session interval), every later attempt goes to embedded routes
  only. A stored route is tried only after a stored URL succeeds, and that can no longer happen.
- Effect: if the embedded copy is out of date (rotated key: R1; retired hosts: R1b), refresh and
  sign-in never recover once the network comes back, until the process dies. This is the situation
  Staff's "use the latest manifest" request was meant to fix. Even when the embedded copy works, the
  app keeps preferring it over the latest manifest for the rest of the process.
- Repro: `R1` and `R1b`. Two refreshes offline, then two hours of retries online with the stored
  routing healthy: no success. The last six posts all went to embedded routes.
- Fix: put stored routes first, then embedded. Within each set keep the failure/retry ordering, and
  give embedded routes only the attempts left over after the eligible stored routes. Alternatively,
  alternate: if an attempt used embedded routes and failed, the next attempt starts with stored ones.
  Reset `recover` once any stored URL has been unattempted for longer than its cooldown. Keep the
  3-attempt cap.

### Medium

**R2: cancellation is detected only through the thread's interrupt flag (depends on the platform).**
- Where: `Protocol.kt` (`catch (e: Exception)` checks `isInterrupted`) and `RefreshSession`
  (the same check).
- What happens: if the HTTP stack consumes the interrupt and throws `InterruptedIOException`
  (older Okio's `Timeout.throwIfReached` behaved this way; Android bundles its own OkHttp/Okio), a
  cancelled request is recorded as an endpoint failure. It then sends credentials to two more URLs
  after `cancel()`, because `cancel()` only disconnects the connection active at that moment, and it
  escalates the outage backoff. Logout, the 3 s connect limit and the activity stop all rely on this
  path.
- Repro: `R2` gives 3 posts after cancellation instead of 1. With an interrupt-preserving transport
  the behaviour is correct (`RecheckPolicyTest`).
- Fix: have `AirApi.cancel()` set a volatile cancelled flag (scoped to the request) that the loop
  and the catch block check. Treat `InterruptedIOException` that is not a `SocketTimeoutException`
  as cancellation. Check the real behaviour on a device.

**A1: the no-handshake account refresh goes into the tunnel that is not answering, so it cannot succeed.**
- Where: `Connection.kt:220-224`.
- What happens: the tunnel uses `AllowedIPs = 0.0.0.0/0, ::/0` and the app does not exclude itself.
  After 10 s without a handshake the tunnel stays installed (on purpose), so the `user` POST is
  routed into it and is cancelled at 3 s. This is exactly the deleted-device-key case F6 was about.
  When it is cancelled, the request still uses up the 60 s interval, so a manual refresh in the next
  minute does nothing and shows nothing. If the connect-time background refresh (A2) already started
  a request, this refresh is skipped because of the cooldown.
- Fix: send control-plane requests outside the tunnel (`VpnService.protect`, or the underlying
  `Network.openConnection`; check lockdown behaviour on a device). Or, on the next manual connect
  after a no-handshake result, wait for a time-limited account refresh before `service.block()`.

### Low

**R3: AirVPN's own rejection text is replaced (UX regression from eca93f0).** `RefreshSession`
rethrows every `LoginException`, including "account expired", identity mismatch and
`message_action` texts, as "AirVPN rejected the login. Check your credentials and wait 5 seconds."
Fix: keep the server's message and append the wait. Repro: `R3`.

**R4: one failed representative probe marks every server in its location as failed.** In the
fixture, one failed probe excluded all 72 servers in the largest location from automatic Best for
10 minutes. One lost SYN, or a representative under maintenance, is enough. Fix: share only
successful samples with the other servers in a location. Record a failure only for the probed
server, and probe a different representative next time. Repro: `R4`.

**Auth rejections never escalate.** Every rejection, including any server `error` attribute that is
not about credentials, waits a flat 5 s with `failures = 0`. That is at most 720 `user` POSTs an hour
(one URL each, user-driven; measured in `RecheckPolicyTest`). Consider a longer wait after about 3
rejections in a row (for example 60 s), still shown as remaining seconds.

**A2: the connect-time background refresh runs at the same time as the tunnel is being set up.**
`Connection.kt:165` starts the refresh just before `service.block()` and `setState(UP)`. The
request is cut off by the blocking interface or the route change and cancelled at 3 s. It still
reached AirVPN and uses up the 60 s interval, and its response is thrown away. Fix: start it after
the handshake succeeds (it then goes through the working tunnel), or only when the connection is
already up.

**A3: logout's credential deletion depends on `viewModelScope`.** `AppViewModel.logout` runs
`repository.logout()` in `viewModelScope`. If the activity finishes while logout waits for the
repository mutex, the coroutine is cancelled and `vault.clear()` never runs. There is also a small
window between `mutex.tryLock()` and `activeJob = …` in which logout cannot cancel the request. Fix:
run logout in the application scope and delete under `NonCancellable`. Set `activeJob` before or
atomically with taking the lock.

**A4: refresh feedback.** `AppViewModel.refresh` shows the generic "Unable to refresh…" for a
`CooldownException` (no "wait N s"). It also skips the latency sweep whenever the bootstrap refresh
throws, including every cooldown during an outage, even though probes do not depend on the
bootstrap.

**A5: Best can disagree with the Servers screen, and fail with a vague message.** The Servers screen
uses `pinger.pings`, which applies the TTL only when results are published. Connect uses `cached()`,
which applies the TTL when it reads. So after 10 minutes the "best" marker can differ from what
connect picks. When every Best candidate has a failed probe, `require` gives way to the generic
"Check account, server and WireGuard settings". Something like "No server answered the latency
check; pick a server or retry" would be clearer.

## Test quality

- Targeted mutations of 9320cd6, each run against the full core suite and reverted afterwards.
  Killed: M2 (signed-out refresh arms the cooldown), M3 (no partial account save), M4 (auth
  rejection escalates), M6 (no failed fallback), M8 (persisted budget ignored), M9 (no embedded
  recovery), M10 (cancellation counted as an endpoint failure), M11 (forced refresh skips the
  account), M12 (overlapping sign-in silent), M13 (no location sharing), M14 (no priority), M15
  (sweep not reserved), M16 (logout drops routing), M17 (no TTL in `cached()`). Mutants that
  survived or need a note:
  - M1, removing failure-count ordering: an equivalent mutant. Ordering by `retryAt` already puts
    untried URLs first.
  - M5, all-unmeasured latency neutral = null: this made scoring load-only and survived. No test
    checked that latency still affects ranking. `RecheckPolicyTest` now kills it.
  - M7, removing the transport cancel from `networkWork`: `FixPolicyTest` hangs instead of failing,
    because its blocked worker is only released by `cancel()`. Consider a hard timeout or a
    test-level `@Test(timeout = …)`.
- No skips or assertions were weakened to hide failures (see Results).

## Limits

- Not run on a device or emulator. Still needed on a device: interrupt and `disconnect()` behaviour
  of Android's HTTP stack (R2); app traffic routing during a handshake failure (A1) and during
  connect (A2); lifecycle and process death; Keystore and `SharedPreferences.commit` failures.
- No live AirVPN traffic. Rate limits and error texts the real bootstrap servers send are unknown.
- This says nothing about whether "Best" picks the best server in the real world. TCP 443 location
  estimates and the median neutral are a heuristic. That needs a separate Best-server assessment.
