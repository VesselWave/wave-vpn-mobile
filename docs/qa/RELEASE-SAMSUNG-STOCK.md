# Stock Samsung leak verification — 2026-10-31

> **In short:** External packet capture on a stock Galaxy S24 Ultra (Android 16). DNS escaped outside the VPN during crash, Wi-Fi recovery and force-stop, even with Always-on, blocking and Private DNS off. The app-side mitigations tried didn't fix it.

Latest follow-up (2026-11-02): the blocking handoff is verified by live link
configuration and blocked probes, but **stock Samsung privacy still fails**.
Private DNS Off did not prevent phone-origin DNS outside the VPN during crash,
Wi-Fi recovery or force-stop. The phone's usual network, original VPN/DNS policy
and temporary notification-volume change have been restored. See the final
continuation below for evidence, corrections and limits.

The physical Galaxy S24 Ultra (SM-S928U1), stock Samsung Android 16/API 36,
September 2026 security patch, was tested with the signed Wave 0.2-dev release.
This is an observed Wi-Fi test scope, not a device-wide leak-free certification.

A leased, password-protected, one-station hotspot on lab host's spare radio provided
external Ethernet packet capture before traffic reached the LAN router. The
capture classified station traffic using the dedicated AP MAC, including after
phone MAC changes. Mobile data was disabled. Fresh unprotected IPv4 application,
system-DNS, raw-DNS and connection controls passed before protected tests.
The uplink had no verified IPv6 Internet path. Working IPv6 inside the VPN does
not supply that missing coverage.

Protected windows used Android Always-on VPN and Block connections without VPN;
the report retains policy samples at both ends, not a claim of continuous kernel
policy enforcement. The independent probe uses a separate app UID.

The overall privacy result fails. Cleartext DNS was observed outside the VPN
during failure/recovery windows. The initial steady window passed. Crash recovery
completed without opening Wave, but does not erase the DNS finding. Unmarked
DNS packets cannot be assigned to Wave, the probe, Android, or another app from
this upstream capture alone. Shared destination/name patterns do not establish
process attribution. Unsupported packet forms retain their failed assessments.
See the adjacent filtered JSON for exact scenario results and capture hashes.

Setup attempts are retained privately: wrong ADB endpoint, competing UI automation,
Samsung-specific settings selectors, and an installer security-check prompt.
None is treated as a protection pass. The runner now supports explicit
`vpn_settings_buttons` (for this Samsung: `Wave VPN Settings` and
`Wave VPN Connected Settings`, plus the protected-state
`Wave VPN Connected / Always on Settings` and `Wave VPN Always on Settings`), verifies the installed version before changing
policy, waits for the verified screen, and avoids reinstalling an identical APK.
The default generic Settings selector remains available for other devices.

Unexercised scopes include IPv6 uplink/NAT64, captured cellular, pre-unlock boot,
permission revocation, native memory pressure, overnight behavior and all other
apps/profiles. No unrestricted stock-Android privacy claim is justified.
Raw captures and account material stay outside Git.

Restoration completed after adding Samsung's protected-state button label and
retrying with the QA driver. The original report's cleanup failure is preserved;
the filtered evidence adds a separate successful restoration record. The QA
network was forgotten, Wi-Fi restored off, mobile data restored on, owner
Always-on/blocking disabled, helpers removed, and the AP stopped with its radio
managed/down. Wave remains installed and signed in. Fifty-nine offline QA tests
pass. No production Kotlin or Rust code changed.

## Candidate app mitigation — not yet device validated

The working-tree mitigation replaces the dependency's manifest VPN service with
an authored subclass and binds it for the tunnel lifetime. Before WireGuard
teardown/reconfiguration it establishes an unread blocking TUN with explicit
IPv4/IPv6 default routes and DNS addresses. With Android lockdown enabled,
Disconnect retains this blocking interface and the foreground connection owner.
Without lockdown, explicit Disconnect releases the VPN normally. Cancellation
leaves the current interface installed until the next serialized operation.
Reconnect from the blocking state uses cached account/server information rather
than waiting for bootstrap requests through the sink. The temporary sink covers
all apps, including those excluded from the connected tunnel's split settings.

This does not survive process death or force-stop, override platform DNS bypass,
or establish that Android replaces these interfaces without routing gaps.
The original failed privacy result above remains authoritative until repeated
external controls/captures establish otherwise. No fresh Samsung capture was
collected: the device tool returned DEVICE_IN_USE for another session, including
a retry with a 10-second wait. The release signing helper also failed to access
the macOS Keychain (status -25308). No other device session was taken over and no
phone/network settings were changed in this follow-up.

Samsung access subsequently succeeded after the other session closed. The
existing app was opened and its screen inspected; the candidate has not been
installed. Signing remains blocked by Keychain -25308. A fresh private runner
configuration targets a separate candidate APK path, so the old signed build
cannot accidentally be retested as the mitigation.

The user completed signing in a local Mac Terminal. The resulting release APK
verified with the pinned production signer SHA-256 and was installed using an
in-place update, preserving application data. The temporary external-capture AP
started successfully. Opening Samsung Wi-Fi settings through the device tool
failed (`COMMAND_FAILED: adb exited with code 1`), including the advised debug
retry. Device interaction was stopped and the AP removed before traffic tests;
there is still no post-mitigation privacy result. The candidate remains installed.

## External Samsung retest — blocking candidates

Signing succeeded through the Mac's interactive Terminal session, using the
existing signing helper; both candidates matched the production signer. The
original external AP/capture and independent probe were reused with fresh IPv4
positive controls. At the user's request, the resumed scope covered only the
previously failed scenarios: crash, disconnect, reconnect, Wi-Fi recovery,
Private DNS Automatic/Strict and force-stop. A first run already underway was
interrupted; its pending restoration completed before a fresh targeted run.
Private DNS Off and steady traffic were excluded from that targeted run.

Filtered evidence is in `device-tests/2026-10-31-samsung-blocking-run2.json`.
Crash, Wi-Fi recovery, Strict Private DNS and force-stop still exposed DNS or
related transport outside the VPN. Automatic Private DNS passed that window;
this single pass does not establish a general improvement. Packet origin remains
unassigned. IPv6 uplink and cellular are not covered.

The runner originally rejected any active VPN after Disconnect. It now supports
an explicit `blocking_vpn_session` label, requiring the target owner, lockdown,
the disconnected UI and that exact non-bypassable session; external blocked
probes and packet findings remain required. Ordinary encrypted sessions are
rejected. A focused rerun (`...blocking-run3.json`) still failed to establish the
expected disconnected state. An additional in-process recovery guard prevents
service/Always-on callbacks from reconnecting after explicit Disconnect. That
candidate passed protocol tests, debug lint/build and the signed release build.
Its focused disconnect/reconnect run (`...blocking-run4.json`) also failed the
expected-disconnected-state checks. Thus the blocker handoff is **not validated**
and no app-level privacy fix is established. These are experimental working-tree
changes, not a qualified release. The final guarded candidate remains installed.
The crash and DNS results were collected on the first candidate; only the
focused disconnect/reconnect windows were repeated on the guarded candidate.

Both targeted runs and the final focused run completed runner restoration. The
QA Wi-Fi network was forgotten and the temporary AP stopped. Wi-Fi was already
on and mobile data already off at the start of this follow-up (different from the
original test's starting state); those states were preserved. Sixty-one offline
QA tests pass, including rejection of an ordinary encrypted session as a blocker
and DNS setting restoration when a selected-mode capture fails.

## Initial bounded closeout — 2026-11-02 (superseded below)

Correction from the subsequent investigation: Samsung retained a stale
`TransportInfo.sessionId=wave` while the live link already had the blocking
addresses, DNS and default routes. The session-name mismatch below does not
prove the encrypted tunnel remained active. Preserve the historical failed
reports; use the subsequent live-configuration/capture results for handoff
assessment.

Reviewed the original external test and blocking-candidate retests, including
run4's successful IPv4 controls and failed disconnect/reconnect state checks.
A fresh direct device check on the installed guarded candidate reproduced the
mismatch: tapping Disconnect changed the UI to Connect, but Android still
reported VPN session `wave`, rather than `Wave — blocking traffic`. This is a
functional failure, not evidence of a successful blocking handoff. No new
external capture was taken in this closeout; earlier privacy failures stand.
Reconnect returned to Connected with a fresh connection timer and Android session
`wave`. The 61 offline QA tests pass. No production source was changed here.

As a concrete precaution, enabled owner-profile Always-on VPN and Block
connections without VPN through Samsung Settings; ADB readback confirmed
`always_on_vpn_app=me.vwave.vpn` and `always_on_vpn_lockdown=1`. Left Wave
connected. These two settings were initially disabled and are deliberately left
enabled as mitigation, not restored. Private DNS was unchanged (mode readback
`null`, no explicit global mode). No hotspot, cellular, root or gateway changes
were made in this closeout. The experimental build remains installed.

### Practical precautions and limits on stock firmware

1. Keep both Android VPN switches enabled. Keep the tunnel connected; avoid
   force-stop and unnecessary disconnects, server changes or network changes.
   These reduce exposure opportunities; they do not fix the observed failures.
2. Use All apps routing, without split exclusions, for the broadest intended
   coverage. This closeout did not change or qualify the split-routing setting.
3. Do not prescribe Strict Private DNS as a fix: its captured window failed.
   Automatic passed one candidate window and Off passed one baseline window;
   neither has enough evidence to recommend it as a reliable leak mitigation.
4. If an interruption is necessary and exposure is unacceptable, turn off both
   Wi-Fi and mobile data before stopping/changing the VPN. Re-enable networking
   only when ready to restore it; even reconnection itself remains a known risk.
5. For a stronger independent boundary on Wi-Fi, use a dedicated VPN gateway
   that tunnels all phone Internet traffic and blocks direct WAN egress on both
   IPv4 and IPv6 when its tunnel is down. Test DNS, tunnel failure and IPv6 at
   that gateway. Keep cellular disabled to prevent bypass. This is a proposed
   deployment mitigation, not configured or validated by these Samsung tests;
   it does not protect cellular, other Wi-Fi networks or local-link traffic.

Android documents Always-on/lockdown and the VPN service API at
https://developer.android.com/develop/connectivity/vpn and
https://developer.android.com/reference/android/net/VpnService . Mullvad's
independent Android investigation describes related platform limitations:
https://github.com/mullvad/mullvadvpn-app/blob/main/docs/known-issues.md .
These references do not establish the cause of this Samsung's packets.

The bounded stock-ROM investigation is closed with **unresolved privacy and
Disconnect blockers**. Available app candidates were tried and failed to prove
a fix. We cannot claim every possible mitigation has been exhausted, a precise
OS root cause has been proved, or that the phone is leak-free. Native IPv6 uplink,
captured cellular, pre-unlock and overnight behavior remain unqualified. Do not
ship the experimental blocker as a verified privacy improvement.

## Final app-side continuation — 2026-11-02

Implemented and checked the practical app-side protections investigated here:

- Retain the authored IPv4/IPv6 blocking interface during explicit lockdown
  Disconnect and WireGuard reconfiguration; cancellation preserves the current
  interface until the next serialized operation. Use cached account/server data
  while the blocking interface is installed.
- On Android 10/API 29 and newer, establish the startup blocker in VPN service
  creation when lockdown and consent are present, before refresh/handshake work.
  Always-on callbacks now dispatch through the application's main coroutine scope.
- Preserve the prior state and log a fixed diagnostic if Disconnect throws,
  rather than unconditionally advertising Down; cancellation is propagated.
  A missing service cannot silently satisfy a lockdown blocking request.

The initially suspected Disconnect failure was a **verification error**:
Samsung retained `TransportInfo.sessionId=wave` after replacing the live link
with the blocking addresses, DNS and default routes. The runner now requires the
live sink configuration, correct owner, non-bypassable VPN and lockdown. It
rejects a missing VPN under lockdown and wrong DNS/addresses/routes/owner.
Packet capture and blocked application probes remain separate requirements.
Historical session-name failures have not been relabelled as privacy passes.

### Evidence and qualification

All new APKs used the pinned production signer. The final installed/tested
artifact is the startup-blocking candidate with SHA-256
`9557b238b4368f5dfa55efdc21cc0e531c2ff3838df17772dd217ec2d9145a7b`.
Filtered reports are adjacent in `device-tests/2026-11-02-samsung-run*.json`.
Raw PCAPs, configuration, account files and APKs remain private outside Git.

Run1 retained the session-name rejection. Run2 verified the handoff and sampled
steady/disconnect/reconnect windows without packet findings; crash still failed.
Its Wi-Fi stage referenced an earlier hotspot's password file. The phone could
return to its usual AP, invalidating capture-path assumptions for that stage and
the subsequent DNS-mode/force-stop windows. Those later results are **not**
accepted as valid privacy passes or as proof of an app recovery bug.

Run3's fresh positive control correctly rejected the wrong capture path; it
never qualified protected traffic. Run4 stopped during setup because the QA and
screen tools competed for Android UI automation. Its original cleanup failure
is preserved, with a separate successful restoration record. Releasing only this
thread's screen session allowed the test runner to operate; no device/ADB service
was restarted and no new ADB tunnel was created.

Run5 used the correct hotspot credential file and an explicit Private DNS Off
baseline after fresh IPv4 positive controls. The runner verified the expected
AP before each observation and at each window end; Wi-Fi recovery explicitly
selected the captured AP. These are endpoint samples, not proof of continuous
association or coverage of every possible intermediate network. It used the
same final app code: the speculative network-recovery callback was removed once
the password-file error was identified. Wi-Fi application traffic did recover.

Run5 verified the active blocking link, and no HTTPS probe succeeded after the
Disconnect grace period. Reconnect and steady windows passed. Independent
station-specific review derived the phone's MAC from marked positive-control
queries: cleartext IPv4 DNS from that MAC remained in crash, Wi-Fi recovery and
force-stop windows. The originating process remains unknown. Thus turning Off
Private DNS is not a reliable workaround, and no complete privacy fix is proved.

The small parser still failed windows containing IPv6 extension headers. An
independent tcpdump review classified the flagged packets in the reviewed run5
windows as MLDv2 multicast listener reports. The extra filtered review records
both station-specific DNS counts and decoder results. These local-link reports
are distinct from the observed DNS queries; original failed assessments remain
intact and no whole-device pass is asserted.

### Final restoration and remaining boundary

Used T3's physical test laptop USB device screen for final restoration. The final candidate
remains installed and signed in, but its VPN is stopped. Readback confirmed:

- Usual Wi-Fi connected, Wi-Fi enabled, mobile data off (the pre-follow-up state).
- QA Wi-Fi forgotten; temporary hotspot stopped and radio managed/down.
- Owner Always-on target `null`, blocking `0`, no active VPN: the user's settings
  before this thread enabled mitigation, rather than the temporary test policy.
- Private DNS mode and specifier both `null`, matching their original values.
- Temporary QA helpers absent. Notification stream returned to speaker volume 6
  after the user's temporary mute; ringtone/media/alarm levels were unchanged.

Protocol tests, debug lint/build, production-signed release build and 64 offline
QA tests pass. Source after removal of the speculative callback also passed
protocol tests, lint and debug build. Linux was untouched. No release was
published and the candidate remains unqualified for a leak-free claim.

This completes the practical app-side mitigation attempts investigated here.
An ordinary stock-ROM VPN app cannot keep its process-owned interface alive
while its process is dead, or impose a separate device-wide kernel firewall on
other UIDs. A precise origin/root cause for the unmarked DNS is still unproved;
these constraints do not prove that every imaginable app fix is impossible.
No further verified app-only remedy was found. Use the precautions above; an
independently enforced dual-stack VPN gateway with cellular disabled is the
stronger proposed Wi-Fi boundary, not an app workaround validated by these runs.
Native IPv6 Internet uplink, cellular capture, pre-unlock and overnight scopes
remain unqualified. Avoid shipping this as complete stock-ROM leak protection.
