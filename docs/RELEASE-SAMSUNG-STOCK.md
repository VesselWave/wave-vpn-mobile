# Stock Samsung leak verification — 2026-10-31

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
