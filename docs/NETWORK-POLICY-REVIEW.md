> Implementation follow-up: the findings below describe eca93f0, not current behavior.
> All 11 `ReviewFindingsTest` reproductions now run unconditionally. F2b uses a
> one-location budget to retain an unsampled country under representative sampling.
> Additional `FixPolicyTest` coverage checks persisted budgets, fixed auth delay,
> overlapping login, nonblocking cache reads, priority sampling and transport
> cancellation. See NETWORK-POLICY.md for the current policy and device limits.

# Independent review: bootstrap and latency load policy (eca93f0)

This is an independent test review of eca93f0 against AirVPN Staff's forum comment 268064
(topic 82017). The comment raised these points for Android:

- TCP 443 "ping" to every server at every launch, login and refresh, with no rate limit on manual refresh.
- Probes running through the active tunnel.
- Unreachable servers winning "Best". The comment raised this for desktop; it is checked here as an invariant.
- Bootstrap URLs and RSA key should come from the latest manifest, with the embedded copy used only as a first-run fallback.
- `user` and `manifest` sent at every refresh, always to the first URL, with no backoff. The fix should try the other URLs, with increasing delays.

No production code was changed. All tests are offline. They use encrypted fake bootstrap
servers (`FakeAirNetwork`, which decrypts with its own RSA key), the recorded 259-server Eddie
manifest with documentation addresses, fake probes and virtual clocks.

## Suites

| File | State | Purpose |
|---|---|---|
| `BootstrapLoadRegressionTest` | passes | Real `RefreshSession` + `AirApi` + envelope end-to-end: account sent once per six hours, `next_update` respected, refresh spam held to 1/min, outage backoff 0/60/180/420/900/1800/2700 s with ≤3 sockets per attempt (≤21/h), failover within a refresh, preferred survivor, malformed/truncated documents, RSA/URL rotation through restore, 8-thread concurrency, interrupt stops failover, URL-list cap, offline relaunch, RSA/`next_update` validation. |
| `LatencyLoadRegressionTest` | passes | Hour of refresh spam: ≤4 concurrent sockets, ≤24 per sweep, ≤480/h, no server re-probed within 10 min; launch+login+refresh share one sweep; tunnel-up mid-sweep starts nothing new and publishes nothing; stop with uncancellable sockets discards late results; connect path never probes; sign-out isolation; 0/negative/failed results are unreachable; closed/IPv6-only never probed. |
| `ReviewFindingsTest` | **fails on eca93f0**, skipped unless `WAVE_FINDINGS=1` | One repro per finding below, written as the behavior a fix should produce. |

```sh
./gradlew :core:test                                                    # required suite, green
WAVE_FINDINGS=1 ./gradlew :core:test --rerun-tasks --tests '*ReviewFindingsTest'   # 11/11 fail today
```

### Fail-before / pass-after evidence

- **Parent 53bb07d** (temporary worktree, equivalent tests using APIs that exist there):
  - URL-list cap: 20 posts instead of 3.
  - Repeated requests during an outage: 60 posts instead of ≤6.
  - A weak 1024-bit RSA key and an `ftp://` URL were both accepted.
  - A `-1` sample won "Best". 53bb07d never stored failures, so this is a latent check.
  All four pass on eca93f0.
- **Targeted mutations of eca93f0.** Each one brings back an old behavior or removes a safeguard, and each was killed by the passing suites:
  - no per-document attempt cap
  - account sent every refresh
  - no minimum interval
  - flat backoff
  - embedded bootstrap ignoring the manifest
  - fleet-wide sweep
  - no sweep interval
  - probing after the tunnel comes up (both in-worker checks removed)
  - publishing route-changed results
  - old scoring where `-1` counts as fast
  - no `-1` filter
  - no preferred endpoint
  - ignoring interrupts between endpoints
  - no cancellation discard
  - weak RSA accepted
  - no URL validation

  Equivalent mutants are not detectable by tests. Removing just one of the two in-worker `allowed()` checks, or the entry `allowed()` gate, starts no probe, because the remaining checks still stop the worker.

## Findings (eca93f0)

Lines refer to eca93f0.

### High

**F1: failover never reaches bootstrap URLs 4–6.**
- Where: `core/.../Protocol.kt:123-131,141-143` together with `RefreshSession.kt:41,63-64`.
- What happens: each document stops after 3 attempts, always in the same order (IPv4 first). An endpoint's cooldown (2 s doubling, at most 120 s) has always expired before the session's next attempt (60 s, then 120, 240, … 900 s). So when the first three URLs are blocked, every attempt goes to the same three, and `bootstrap.example`/IPv6 are never tried. This is the "try the other URLs" request from Staff, and it is a regression: 53bb07d tried every URL.
- Repro: `F1`.
- Fix: order eligible URLs by (consecutive failures, last failure time), so untried or least recently failed URLs come first. Alternatively, keep a rotating cursor across requests. Keep the cap of 3.

**F2: one sample anywhere turns every unsampled server into +∞, and the 24-sample budget follows manifest (alphabetical) order.**
- Where: `Documents.kt:123` and `LatencyCache.kt:65`.
- What happens:
  - "Best" is picked only among the alphabetically first 24 servers, Achernar through Alshat. Repro `F2c`: the full-information pick is Crater, the budgeted pick is Alphirk.
  - A Country target whose servers were not sampled ignores load and picks by name. Repro `F2b`: SG picks Auriga instead of the least loaded server, Circinus.
  - An unsampled idle server ranks below an unsampled busy one. Repro `F2a`.
  - The Servers screen's "best" marker and Score sort show the same distortion.
- How long it lasts: with a 10-minute TTL and a 3-minute interval, at most 96 of 259 servers can ever hold a valid sample, and only if the user refreshes every 3 minutes. Usually only the 24 from launch do.
- Fix:
  - Score unsampled servers neutrally. For example, use the 53bb07d penalty of 2000, or the median of the successful samples, never +∞.
  - Sample by location: one entry IP per location (39 in the fixture), then apply that latency to the location's other servers. Rotate locations across sweeps, starting with the selected country, favorites and the last-connected location.

**F3: one failed TCP 443 probe makes an explicitly chosen server impossible to connect to for 10 minutes.**
- Where: `Documents.kt:128` (the `pings[it.name] != -1` filter) and `Connection.kt:160-165`.
- What happens: rank returns an empty list, so the connection ends as `Failed("Check account, server and WireGuard settings")`. A TCP 443 failure does not mean WireGuard/UDP will fail. Captive portals, network changes during a sweep, and port-443 filtering all cause it. The same thing happens to a Country target when all of its sampled servers failed.
- Repro: `F3`.
- Fix: keep the exclusion only for automatic choice. For `Target.Server`, and as a fallback when the filtered list is empty, rank the failed servers last instead of dropping them.

### Medium

**F4: a refresh while signed out arms the one-minute cooldown, and the next sign-in fails without any network call.**
- Where: `RefreshSession.kt:36-44`. `nextAttempt` is set before `old?.login ?: return`.
- How it is reached: the user signs out with VPN permission still granted, then taps the Quick Settings tile (`QuickTile.onClick` → `TunnelManager.connect` → `connection()` → `repository.refresh()`). If they open the app and sign in within 60 s, they see "Sign-in failed…".
- Repro: `F4`.
- Fix: return before arming the cooldown when there are no credentials. Also make `QuickTile`/`connect()` do nothing while signed out.

**F5: once a manifest is stored, the embedded bootstrap is never used again.**
- Where: `RefreshSession.kt:45` and `Session.kt:28-31,89`.
- What happens: if every stored URL dies, or the stored key is rotated beyond the stored copy (for example after a long time offline), refresh and sign-in can never recover. Even an app update that ships new embedded routing does not help, because the public `wave-bootstrap-v1` copy also outlives sign-out.
- Repro: `F5`.
- Fix: when every manifest URL is failing or cooling down, try `Defaults.bootstrap` with its own key as a last resort. Prefer the newer of the stored and embedded copies by manifest time.

**F6 (UX): manual refresh cannot pick up account changes for up to six hours.**
- Where: `RefreshSession.kt:33,47`. Covers devices added or removed on the website, renewed subscriptions and the expiry date.
- What happens: a deleted device key keeps being used, so connections fail until the user signs out and back in. The documentation mentions this, but users won't find it.
- Repro: `F6`.
- Fix: let an explicit user refresh also fetch `user` when it was last fetched more than about 15 minutes ago. Also re-fetch `user` once after a connection fails with no handshake.

**F7 (UX): a password typo locks sign-in, escalating to 15 minutes.**
- Where: `RefreshSession.kt:37-41,63-64`. `LoginException` goes into the same backoff as infrastructure failures.
- What happens: after one typo, retrying with the correct password is refused locally for 60 s, and the message reads like a credentials error. Further typos give 2, 4, 8 and then 15 minutes.
- Repro: `F7`.
- Fix: give authentication rejections a short fixed delay (5–10 s) that does not escalate the infrastructure backoff. Show "wait N s" instead of the generic failure message.

**F10 (timely connect, report-only): connecting waits for a due refresh.**
- Where: `Connection.kt:153`.
- What happens: when the manifest or account is due and the bootstrap servers are blackholed, the WireGuard start is delayed by up to 3 attempts × (8 s connect + 8 s read) = 48 s per document. Repeat connects inside the cooldown fail fast.
- Fix: connect from the cached manifest straight away and refresh in the background. Block only when there is no manifest at all, or time-box the wait to about 3 s.

### Low

**F8: cancelling a request puts the healthy endpoint into backoff.**
- Where: `Protocol.kt:141-145`. `InterruptedIOException`, or any I/O error raised while interrupted, counts as an endpoint failure, and the next request skips the preferred URL.
- Repro: `F8`.
- Fix: when the thread is interrupted, rethrow without recording the failure.

**F9: a fresh account fetch is thrown away when the manifest request fails, and the next attempt sends it again.**
- Where: `RefreshSession.kt:47-58`. Both documents become due together once the six-hour account TTL expires.
- Repro: `F9`.
- Fix: keep each successful document, with its own timestamp, separately.

**F11: an overlapping sign-in is dropped silently.**
- Where: `Session.kt:93`. `tryLock()` returns `Unit`, and `AppViewModel.login` then logs "Signed in" even though no account was set.
- Fix: wait for the lock in `login`, or throw a busy error.

**F12 (pre-existing): sign-out waits for a refresh in flight.**
- Where: `Session.kt:107` `logout()` uses `withLock`. The wait can be up to about 48 s per document while the UI stays signed in and credentials stay on disk.
- Fix: cancel the refresh job first.

**F13: connecting waits for the latency cache's sweep lock.**
- Where: `Connection.kt:104-105` and `LatencyCache.kt` `cached()`.
- What happens: `pinger.stop()` runs before the state changes to Connecting, and `cached()` takes the lock the sweep holds. Connecting therefore waits for any probe in flight, and `Socket.connect` cannot be interrupted, so the wait can last up to 1.2 s.
- Fix: set the state first, and read the cache without the lock.

**F14: a rotation during launch wastes the sweep.**
- Where: `MainActivity.onStop` and `LatencyCache.kt:60-61`.
- What happens: a configuration change calls `onStop`, which cancels the sweep. The 3-minute interval has already been used, so a rotation during launch leaves few or no samples.
- Fix: skip `stop()` when `isChangingConfigurations`, or give back the unused budget when a sweep is cancelled.

**Also noted:** sign-out followed by sign-in starts a new `RefreshSession`, which resets its interval and backoff. Each cycle sends `user` + `manifest`, limited only by how fast someone can tap. Probing is still TCP 443 on the first entry IPv4; Staff suggested ICMP, and that is not a defect.

## Not covered

- Android Keystore, `AtomicFile` failures and legacy `fetchedAt` migration in `Session.kt`: the app module has no JVM tests.
- Compose/lifecycle ordering, ViewModel rapid taps, process death, Quick Settings tile, `ConnectionService` restarts.
- Real cancellation of `Socket`/`HttpURLConnection`, and real timeouts.
- What the bootstrap servers actually do (no live test, no account), and whether the Eddie-based score picks the truly best server. Those belong to separate device validation and best-server assessment.
