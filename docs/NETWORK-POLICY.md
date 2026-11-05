# Android bootstrap and latency policy

The load safeguards are application policy, implemented in `core` and used by
Android's repository and pinger. Tests use fake requests, encrypted fake endpoints,
monotonic clocks and coroutine gates. They do not contact AirVPN.

- Ordinary refresh respects the manifest's `next_update` (clamped to 1–1440
  minutes). Account/device data is reused for six hours. Explicit server refresh
  bypasses the manifest TTL and refreshes account/device data after 15 minutes.
  Infrastructure attempts have a one-minute minimum interval. Password rejection
  instead uses a fixed five-second wait with a remaining-seconds message.
  Signed-out refresh is a no-op. Overlapping refreshes are dropped; overlapping
  sign-ins report busy rather than success. A no-handshake connection requests
  one account refresh, subject to the same cooldown (no retry loop).
- A failed refresh retains the previous usable session and increases the retry
  cooldown from one minute to a maximum of fifteen minutes. There are no timers
  or automatic background retry loops. Cancellation also consumes the minimum
  interval. A failed sign-in is not silently reported as successful during cooldown.
- Each document request tries at most three distinct eligible URLs. Failures
  cool down that endpoint exponentially from two seconds to two minutes. The
  last successful endpoint is preferred among endpoints with equal failure counts.
  Untried/less-failed endpoints go first, so bounded attempts eventually visit
  URLs beyond the first three. Authentication rejection and
  cancellation stop failover. Every transport attempt uses a fresh envelope.
- Validated latest manifest URLs and RSA material are restored from the encrypted
  session. Public manifest data also survives logout in a separate atomic file
  under `noBackupFilesDir`; logout deletes credentials. Embedded routing is the
  first-run or corrupt-cache fallback and recovery routing after all stored
  endpoints have failed. Recovery uses the embedded copy's own RSA key and shares
  the three-attempt document budget; a new app's embedded copy remains available
  even when public routing from an older installation is cached. Invalid URLs/RSA data cannot replace the
  last usable manifest. HTTP bootstrap remains supported per the existing
  official protocol references; no new protocol implementation was imported.
- Latency uses TCP 443, at most four workers and 24 samples per sweep, with a
  three-minute sweep minimum and ten-minute positive/negative cache. Sweeps
  select one representative per location, distributing slots across countries
  before visiting additional locations in a country. Selected country/server,
  favorites and the last attempted server's location come first; locations with
  recent samples are skipped. A location estimate is shared with its peers. No workers wait in a per-server launch queue.
  Unknown samples display as unavailable. A failed sample cannot win automatic
  selection. Unmeasured servers use the median successful latency (or load-only
  ranking if no latency succeeded), preserving load ordering without privileging
  the sampled alphabetical prefix. Explicit server/country targets remain usable
  when all their TCP probes failed. A connection reads an immutable cache snapshot
  without waiting for the sweep lock or starting probes.
- Activity stop and connection initiation cancel sampling. Configuration changes
  preserve the sweep; Connecting is published before cancellation. Each worker checks
  foreground/tunnel eligibility before probing and discards results if eligibility
  changes. No probes start while connected, connecting, stalled or blocked.
  Closed servers and servers without IPv4 entry addresses are not sampled.
- Failed refreshes leave cached accounts/servers usable and show a retry-later
  notice. Ping sorting puts unavailable results last in either direction.

## Review limits and device follow-up

Request cooldown deadlines and outage escalation are persisted separately from
credentials and retained across logout and restart. Sweep reservations also
survive restart, preventing launch from spending another budget within three
minutes. Deadlines restored from wall time are bounded to their maximum interval;
clock changes, clearing app data or reinstalling can reset these safeguards.
Latency results remain process-local: a relaunch after the reserved three-minute
interval can sample again before the former process's ten-minute TTL.
Endpoint health and preferred-URL state also remain process-local; fair endpoint
rotation is guaranteed across attempts in one process, rather than across repeated
process termination. Last attempted-server sampling priority is process-local;
explicit selection and favorites are restored from settings.
Successful account data is saved even if a subsequent manifest fetch fails.
The no-handshake account refresh is best effort and does not automatically restart
the existing tunnel; a manual retry uses newly fetched keys if the refresh succeeded.

TCP latency is only a selection hint, not a WireGuard availability guarantee.
Cached connection setup does not await bootstrap; it starts a background refresh
with a three-second cancellation deadline. With no manifest, the same deadline
applies to the required fetch. Cancellation interrupts the request worker and
closes the active HTTP connection before waiting for cleanup; logout cancels work
before deleting credentials. Platform-level disconnect timing still needs device
verification. TCP connect can take its existing 1.2-second timeout; HTTP retains
8-second connect/read timeouts as a final platform bound. Already-started operations may finish after a lifecycle change;
workers do not schedule additional probes or publish those route-changed results.

The JVM suite exercises request budgets, cooldowns, failover, RSA rotation,
restoration, atomic in-memory replacement, failures, repeated/concurrent refresh,
cancellation, worker bounds, cache invalidation and unreachable-server scoring.
It does not exercise Android Keystore/filesystem failures, real Compose lifecycle
ordering, or actual socket cancellation. Parent review and emulator validation
should cover rapid refresh/sign-in taps, background/foreground, connect during
sampling, logout during refresh, offline cached launch, and process death/restore.
No live bootstrap test or real AirVPN account was used for this change.
