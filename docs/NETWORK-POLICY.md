# Android bootstrap and latency policy

The load safeguards are application policy, implemented in `core` and used by
Android's repository and pinger. Tests use fake requests, encrypted fake endpoints,
monotonic clocks and coroutine gates. They do not contact AirVPN.

- Ordinary refresh respects the manifest's `next_update` (clamped to 1–1440
  minutes). Account/device data is reused for six hours. Explicit server refresh
  bypasses the manifest TTL, but all refresh/sign-in attempts have a one-minute
  minimum interval. Overlapping refreshes are dropped, not queued.
- A failed refresh retains the previous usable session and increases the retry
  cooldown from one minute to a maximum of fifteen minutes. There are no timers
  or automatic background retry loops. Cancellation also consumes the minimum
  interval. A failed sign-in is not silently reported as successful during cooldown.
- Each document request tries at most three distinct eligible URLs. Failures
  cool down that endpoint exponentially from two seconds to two minutes. The
  last successful endpoint is preferred. Authentication rejection and
  cancellation stop failover. Every transport attempt uses a fresh envelope.
- Validated latest manifest URLs and RSA material are restored from the encrypted
  session. Public manifest data also survives logout in a separate atomic file
  under `noBackupFilesDir`; logout deletes credentials. Embedded routing is the
  first-run or corrupt-cache fallback. Invalid URLs/RSA data cannot replace the
  last usable manifest. HTTP bootstrap remains supported per the existing
  official protocol references; no new protocol implementation was imported.
- Latency uses TCP 443, at most four workers and 24 samples per sweep, with a
  three-minute sweep minimum and ten-minute positive/negative cache. Sweeps
  prefer unmeasured servers. No workers wait in a per-server launch queue.
  Unknown samples display as unavailable. A failed sample cannot win automatic
  selection; unmeasured servers remain available when there is no successful
  sample. A connection reads and prunes the cache without starting probes.
- Activity stop and connection initiation cancel sampling. Each worker checks
  foreground/tunnel eligibility before probing and discards results if eligibility
  changes. No probes start while connected, connecting, stalled or blocked.
  Closed servers and servers without IPv4 entry addresses are not sampled.
- Failed refreshes leave cached accounts/servers usable and show a retry-later
  notice. Ping sorting puts unavailable results last in either direction.

## Review limits and device follow-up

Latency and attempt cooldowns are process-local; process restart resets them.
Successful document timestamps and bootstrap data survive restart. A restarted
process can sample at most another 24 servers, rather than a fleet-wide sweep.
Manual refresh does not force account/device reauthentication inside the six-hour
TTL; sign out and sign back in to pick up account changes sooner.

TCP latency is only a selection hint, not a WireGuard availability guarantee.
Cancellation interrupts blocking work, but Java socket/HTTP calls can take until
their existing timeouts to finish (TCP connect 1.2 seconds; HTTP connect/read
8 seconds each). Already-started operations may finish after a lifecycle change;
workers do not schedule additional probes or publish those route-changed results.

The JVM suite exercises request budgets, cooldowns, failover, RSA rotation,
restoration, atomic in-memory replacement, failures, repeated/concurrent refresh,
cancellation, worker bounds, cache invalidation and unreachable-server scoring.
It does not exercise Android Keystore/filesystem failures, real Compose lifecycle
ordering, or actual socket cancellation. Parent review and emulator validation
should cover rapid refresh/sign-in taps, background/foreground, connect during
sampling, logout during refresh, offline cached launch, and process death/restore.
No live bootstrap test or real AirVPN account was used for this change.
