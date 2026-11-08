# Staff comment 268266: automated regression checks

Source: [AirVPN Staff report, 7 November 2026](https://airvpn.org/forums/topic/82017-wave-vpn-open-source-wireguard-client-for-android-and-linux-unofficial-v011/?do=findComment&comment=268266), referring to Android v0.1.3 (`2a406b6`). Checked against v0.1.4 (`72ac16a`) plus the test additions in this change. These are detection tests; this change does not repair the reported behavior. No account or private profile is included.

## Repeat the offline checks

From the repository root with the ordinary Android build prerequisites:

```sh
python3 scripts/check-forum.py
```

Exit 0 means the requested contracts pass; exit 1 means detected defects; exit 2 means incomplete/build/skipped qualification. JSON and the Gradle log go to `build/reports/forum/contracts.json` and `contracts.log`. The runner removes its previous suite XML before starting, runs both modules even if one fails, and reports source revision and working-tree status. It does not call AirVPN. Gradle may download dependencies.

These opt-in tests describe desired behavior and intentionally fail on the current code. Ordinary unit checks skip them unless `WAVE_FORUM_CONTRACTS=1`. Do not convert a failed contract to an expected-broken passing assertion. Once the bugs are repaired, reconcile the old contradictory policy tests, remove the opt-in guards, and make the contracts part of the ordinary release gate.

## Protocol and recovery evidence

41 checks ran: **34 failed, 7 positive controls passed, none skipped**. This is 34 failing scenarios, not 34 distinct bugs.

- Recovery: an unavailable account service, a local coroutine timeout and session cooldown all escape the production `HandshakeRefresh.recover` policy and prevent progression to the cached connection path (3 failures). Successful handshake suppression and actual user cancellation pass (2 controls).
- Endpoint timeout: a real interrupted worker inside production `networkWork`/`AirApi`, with an injected blackholed transport, selects the same URL on its next request (1 failure). The test synchronizes on entry to the transport, then uses a 25 ms deadline rather than spending 3 seconds. Ordinary socket timeouts already fail over, and explicit cancellation does not launch another endpoint in that request (2 controls).
- Manifest: ten malformed bootstrap variants each fail parsing, initial sign-in, and refresh/restore contracts (30 failures). Variants cover missing modulus/exponent/URLs, empty URLs, invalid base64, short RSA modulus, invalid exponent, malformed URL, file URL and URL credentials. The desired behavior is to accept usable server data while retaining safe prior routing. The refresh test changes locations to distinguish new data from an unchanged cache. A valid rotated key/URL, malformed XML rejection, and wrong-document/entity rejection pass (3 controls).

The recovery tests exercise the actual policy called by `TunnelManager`, not a second model of it. They do **not** independently execute Android service binding, backend startup, or the manager's status catch branches. The endpoint test proves loss of URL failure accounting on worker cancellation; deciding how to distinguish user cancellation from a local deadline remains implementation work. The refresh/restore contracts specify durability but currently fail before reaching restore; no successful persistence qualification is claimed.

UI and device evidence, including the retained-block and empty-selection cases, is recorded separately in [the UI report](FORUM-UI-2026-11-07.md).

## Why our checks missed this

The evidence supports specific gaps rather than a lack of testing generally:

1. **Some tests asserted the wrong policy.** `SessionWorkTest` explicitly requires successful recovery before a new tunnel attempt. `RefreshSessionTest` requires whole-manifest rejection for invalid bootstrap fields/URLs. Those tests protect the implementation's decisions rather than independently stating the user outcome: cached connection should remain usable during service trouble; optional routing metadata should not discard valid servers.
2. **The layers were checked separately.** Transport tests cover socket timeout and cancellation; they did not combine a coroutine deadline, endpoint accounting, session cooldown, cached keys and a second Connect. A cancellation type that is correct for logout is wrong when an internal timeout leaves the UI claiming work is still running.
3. **CI omitted app JVM tests.** The checked-in Checks command ran `:core:test`, lint and assembly, but not `:app:testDebugUnitTest`. Local app tests existed, yet CI did not enforce them. Building and linting cannot establish a recovery state transition.
4. **Runtime evidence had an explicit gap.** `STAFF-INFRA-EMULATOR-2026-11-05.md` says the exact failed-recovery error branch was not separately fault-injected after the patch. Successful recovery after restoring network access did not close that branch.
5. **UI smoke tests lacked traffic-state assertions.** A readable “Disconnected” screen is insufficient when a blocking descriptor still exists. Likewise, checking split tunneling with selected apps misses the empty set. Screen wording, offered actions and actual profile semantics must be checked together.

These are conclusions from the repository and recorded evidence; they do not establish what every prior agent privately considered. A second model alone will not close these gaps if it shares the same assumptions and checks only the existing tests.

## Agent loops to adopt

### Per change: requirements first, adversarial sequence second

Write a short externally observable contract before implementation: every connection attempt ends in a live monitor or an actionable terminal state; a retained traffic block is visible and releasable; optional metadata cannot replace safe routing with invalid material; split-mode wording matches emitted routing. Have a separate review pass challenge the contract before reading implementation tests. Require an executable reproduction for each finding, and preserve it when fixed.

For each changed state transition, test success, exception, local deadline, user cancellation, retry during cooldown, retry after cooldown, and process restore. Keep synthetic transports and clocks so the common failures run without paid accounts, remote availability or real-time sleeps. Assert both network side effects and UI actions, including absence of extra requests.

### Device loop: short stateful journeys with fault injection

Use an isolated QA application ID and synthetic fixtures, then run launch → connect → handshake failure → retry → local deadline → retry → release block, plus split-mode × zero/one/many selected apps. Add backend-UP failure, revocation, rotation and process restart. Each step should compare the rendered status and available recovery action with the VPN/service state. Store seed, command, artifact hash, sanitized state trace and cleanup outcome. Reset injected rules in `finally`; mark cleanup failures as failures.

Keep a small mandatory matrix for each release; rotate OS level, theme and font scale in longer runs. An emulator establishes Android service/UI behavior; physical lockdown, OEM DNS, real Wi-Fi/cellular transitions and external packet captures remain separate qualification gates.

### Periodic exploration: find sequences our examples omit

An agent chooses bounded actions from a state model, mutates network timing and responses, and checks invariants after every action. Start with enumerated short sequences; add seeded random sequences only with a reproducible seed and automatic shrinking. Include empty selections, missing/stale metadata, repeated taps and interruption at every suspension point. Use an independent reviewer for the oracle and minimized trace, not just another approval of passing tests.

The output should be a concrete issue and failing regression, or an evidence-backed report stating exact coverage and gaps. Do not accept “all green” if scenarios were skipped, the artifact changed, the expected behavior was weakened, or the test never reached its target fault. Keep these jobs out of the release's live-provider request budget; use a few separately authorized live controls when needed.

## Final integration verification (8 November)

The parent reran the offline runner after integrating the QA build: 41 cases,
34 failed, 7 passed, no skipped or missing suites, exit 1. The final API 37
emulator run with strict cleanup and complete-result validation repeated all
four passing UI controls and four failing UI contracts (items 3/4 plus the
accessibility finding), exit 1. JSON and screenshots remain generated artifacts
under `build/`; they are not checked into source.

Standard core/app unit tests, lint and debug assembly pass with the defect
contracts opted out. The script-reporting/release tests and independent device
runner tests also pass. CI now includes the app JVM test task. Existing
contradictory tests have been preserved to make the policy disagreement explicit;
the eventual fix must reconcile them and promote the desired contracts into the
regular checks. No functional fixes or Linux changes are included here.
