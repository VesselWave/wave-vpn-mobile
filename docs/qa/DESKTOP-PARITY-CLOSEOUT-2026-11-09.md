# Desktop parity closeout — 2026-11-09

Scoped follow-up to the Linux distro matrix; no release or version change.

- Country headings already use full names, sort by name and say "1 server".
  Server row and Best subtitles now also use full country names. Location sort
  uses those names instead of country codes.
- Selecting the exact server currently connected updates the target preference
  without restarting the tunnel. Other targets keep Android's existing immediate
  selection behavior; no extra desktop-style Switch button was introduced.
- Failed-state text already appears in Overview; connect and disconnect clear
  the prior interruption/failure state. Android's backend failure text remains
  deliberately generic for unknown exceptions; raw exception strings can contain
  account or configuration data and were not exposed as part of copy alignment.

Validation: core/app unit tests, lintDebug, assembleDebug, assembleQa and
assembleQaAndroidTest passed; all 45 offline forum contracts passed.
On the Android 17 / API 37 emulator, opened the isolated QA app with its
synthetic offline session. Visually verified the All view: Best subtitle and
server row show Netherlands without clipping. The accessibility snapshot agrees.
Countries shows Netherlands and "1 server". No real credentials or VPN used;
live same-server selection was not exercised.

The first device open reported DEVICE_IN_USE; retrying with a one-second wait
succeeded in this thread's own session. No other session was reused or closed.
The preceding isolated instrumentation control failed with UiAutomation
"Not connected!"; that run is not counted as passing. Only me.vwave.vpn.qa and
its test APK were installed; debug and release installs were not updated.
