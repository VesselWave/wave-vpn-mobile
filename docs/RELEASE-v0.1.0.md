# v0.1.0 — experimental prerelease

Unofficial GPL-3.0-or-later AirVPN WireGuard client for Android 8/API 26 and newer.
This is an experimental release, not production-qualified VPN protection.

**Known privacy failure:** stock Samsung Android 16 captures show cleartext DNS
outside the VPN during crash, Wi-Fi recovery and force-stop despite Always-on
and blocking, including with Private DNS Off. Do not rely on this build where
such exposure is unacceptable. Packet origin remains unassigned to a process.
See [the Samsung evidence](qa/RELEASE-SAMSUNG-STOCK.md) and the broader verification
reports. Blocking TUN handoff, early lockdown startup blocking and recovery
improvements do not eliminate the failure.

Includes encrypted account storage, server search/favorites, split application
routing, traffic status, process recovery and standalone verification tooling.
Enable Android Always-on VPN and Block connections without VPN; these settings
reduce exposure opportunities but do not resolve the documented leaks.

Downloads include a production-signed minified APK, corresponding tagged source,
notices/toolchain records, SHA-256 checksums and an OpenPGP manifest signature.
The APK is also supplied directly for installation. Its version code is 3, so it
updates the earlier locally signed development installations in place. Historical
0.2-dev device evidence is identified separately from this versioned artifact;
no new physical-device qualification of the v0.1.0 APK is claimed.

Verify checksums with the trusted fingerprint in SECURITY.md and verify the APK
certificate using apksigner. Native IPv6 Internet uplink, captured cellular,
pre-unlock boot and overnight qualification remain open. Raw captures, account
material and signing secrets are not distributed. No security response-time or
production support commitment is made. A separate VPN gateway with independently
enforced IPv4/IPv6 egress blocking and cellular disabled is the stronger proposed
Wi-Fi boundary; it is not configured by this app.
