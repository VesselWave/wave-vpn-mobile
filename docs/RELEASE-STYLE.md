# Release notes style

Use the GitHub title `Wave VPN <version> for Android`. Write the body in
`docs/RELEASE-v<version>.md`; CI publishes it verbatim. Use
[0.1.1](RELEASE-v0.1.1.md) as the example.

## Writing a release

- Open with one or two sentences explaining the release's main benefit, then
  one line stating early-release status and the minimum Android version.
  Don't repeat the title or imply that device coverage is complete.
- Use a short “What's new” section. Describe user-visible features and fixes;
  keep build records and implementation detail out of the highlights. State
  when an update changes only the interface.
- Keep known protection issues, required migrations and repackaging notices
  visible. A GitHub warning or note should explain the consequence and link
  to the relevant report.
- Give the APK one prominent download link with a rounded size checked against
  the published asset. Follow with the fingerprint-pinned F-Droid repository
  link for ongoing updates. Make clear that F-Droid offers the current version,
  while the APK is the exact release. A package table isn't needed for one APK.
- State upgrade compatibility briefly when relevant. Put phone installation
  steps in one disclosure and computer verification steps in another, with
  blank lines around Markdown inside `<details>`.
- Verification must link to the release's checksum manifest and signature,
  show both OpenPGP and APK certificate fingerprints, and point to an
  independent reference for confirming them. Explain the required tools.
  Link the extra bundle and public key there rather than crowding downloads.
- Finish with support, version-pinned changelog, source archive and issue links.
  Check paths in each tag: older tags predate the `docs/qa/` reorganization.

Keep wording original and concise. Avoid decorative badges, duplicate status
warnings and long filenames as link labels. Preserve version-specific facts
and important published notices when editing historical releases. Keep the
matching notes file and published body in sync; editorial changes don't move
tags or replace binaries.

## References

The presentation draws on these projects, reviewed on 2026-11-05:

- [Zed](https://github.com/zed-industries/zed/releases): lead with the benefit.
- [Mullvad](https://github.com/mullvad/mullvadvpn-app/releases): concrete change
  bullets and visible notices for consequential issues.
- [IVPN](https://github.com/ivpn/desktop-app/releases): keep compatibility and
  required upgrade information visible.
- [LocalSend](https://github.com/localsend/localsend/releases): summarize the
  purpose of a small update in a sentence.

Adapt these patterns to Android: one APK, F-Droid updates, phone installation
steps and two separate signing identities.
