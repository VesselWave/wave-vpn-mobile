# Public release and forum checklist

Current assessment: 2026-11-02 (Chicago). v0.1.0 is an experimental prerelease.
This is a maintainer checklist, not AirVPN admission criteria. The forum describes
[third-party software](https://airvpn.org/forums/forum/49-third-party-software/)
as community software not developed by AirVPN. No approval or endorsement is claimed.

## Before announcing an experimental build

- [x] Publish v0.1.0 tagged source, notices, binaries and signed checksum manifest;
  hosted checks and downloaded-asset verification passed in the publication record.
- [x] Provide installation, verification, known-limit and private-reporting guidance.
- [ ] Make source and downloads accessible to forum readers. GitHub repository
  visibility was PRIVATE when checked on 2026-11-02. See [visibility preparation](PUBLIC-VISIBILITY-REVIEW.md). Review history, assets,
  licenses, workflows and contact details before changing visibility; then verify
  source/download links without repository membership.
- [x] Confirm private security inbox receipt and recoverable backups of both
  signing identities (maintainer confirmed 2026-11-02: controls and receives the
  security inbox; encrypted backups exist for the OpenPGP and APK signing keys).
- [x] Publish signing fingerprints through an independently trusted channel:
  keys.openpgp.org (email identity verified) and https://vpn.vwave.me/#verify.
- [x] Complete the exact shipped artifact's basic install/connect/disconnect/recovery
  smoke test and record its hash, platform, failures and cleanup. Historical builds
  are not substitutes for this test. Passed on an Android 17 emulator; see
  [emulator smoke test](RELEASE-EMULATOR-SMOKE.md), including its crash-gap caveat.
  A physical-device smoke test is still recommended.
- [ ] Review the forum introduction, support ownership and acceptable experimental
  privacy scope with the maintainer. Post only after explicit authorization;
  request staff guidance if a listing/subforum requires it.

## Evidence and acceptance

Keep packet captures and credentials private. Record artifact hash, source revision,
OS/security patch, scenario, positive controls, expected/observed result and cleanup.
Never convert a failed or unqualified run into a pass by narrowing its label later.
A signed artifact establishes identity, not production qualification.

## Android qualification still open

- [ ] Resolve or explicitly constrain support around the reproduced stock Samsung
  DNS escapes during crash, Wi-Fi recovery and force-stop. Always-on + blocking,
  Private DNS Off and current app mitigations do not establish leak prevention.
- [ ] Retest the exact v0.1.0 APK (versionCode 3) on physical stock Android and
  GrapheneOS using external captures and fresh unprotected controls; distinguish
  application probes, system traffic and unresolved originating UID.
- [ ] Native ISP IPv6, IPv6-only/NAT64 and captured Wi-Fi/cellular transitions.
- [ ] Physical pre-unlock boot, permission revocation, natural memory pressure,
  overnight reliability and additional OEM/OTA coverage.
- [ ] Verify update-in-place with the release certificate and settings retention;
  do not uninstall the user's app to work around signing mismatches.

Existing tools and evidence: [Samsung](RELEASE-SAMSUNG-STOCK.md),
[device testing](RELEASE-DEVICE-TESTING.md), [signing](../RELEASE-SIGNING.md),
[release notes](../RELEASE-v0.1.0.md). A spare stock phone/SIM and externally captured
native IPv6 network are useful; retain the restored Samsung settings until a new
hardware test is arranged. Android emulators alone cannot close physical gates.
