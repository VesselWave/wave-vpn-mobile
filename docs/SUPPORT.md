# Installation and support

Wave is an unpublished development app. The tested configurations and known DNS
failure are recorded in [RELEASE-FOLLOWUP.md](RELEASE-FOLLOWUP.md). A successful
WireGuard connection does not establish whole-device leak protection.

Android 8 or later is the build minimum. Signed runtime evidence covers an
Android 17 GrapheneOS Pixel 7, Android 16 emulator, Android 15 16 KB emulator and
Android 8 emulator; their testing depth differs. Current stock Android, native
IPv6 uplink/cellular capture and longer reliability checks remain release gates.

## Installing a candidate

Obtain the APK and source from the same maintainer release. Verify the APK's
certificate against [SECURITY.md](../SECURITY.md) using `apksigner verify
--verbose --print-certs FILE.apk`, and verify signed checksums as described in
[RELEASE-SIGNING.md](RELEASE-SIGNING.md). Get the signing fingerprint through a
separately trusted channel. A key downloaded beside an APK does not establish
trust by itself.

Production candidates use `me.vwave.vpn`. Debug builds use `me.vwave.vpn.dev`
and can coexist. Updates require the same signing identity and a nondecreasing
Android version code. A differently signed legacy installation cannot be updated
in place: uninstalling it removes its local account/settings. Back up anything
needed before choosing to remove it. Do not uninstall solely to troubleshoot a
routine connection error.

Enable Always-on VPN and Block connections without VPN in Android's VPN settings.
Affected Android versions can still expose DNS during transitions. Test app
exclusions together with blocking: excluded apps may be blocked instead of
receiving direct access. Android force-stop is intentional and does not carry an
automatic restart promise. Open Wave and connect to recover after force-stop.

## Reporting a problem

Report security issues privately to **vesselwave@protonmail.com**; see
[SECURITY.md](../SECURITY.md). For ordinary bugs include app version, Android
version/security patch, device/OS, network type, protection settings, the action
that triggered the issue and recovery steps. Describe whether a failure occurred
before sign-in, during connecting or after a connection was established.

Review screenshots and logs before sharing. Do not publish account values,
profiles, private keys, unredacted packet captures, personal app lists or DNS
hostnames. The development QA runner keeps raw evidence private; even its
filtered reports need review. Android has no reviewed general-purpose in-app
diagnostic export yet; do not treat raw logs as a safe substitute.

WireGuard is the only VPN protocol. OpenVPN, obfuscation and AirVPN account-side
port forwarding/DDNS/blocklist management are outside this release scope.
