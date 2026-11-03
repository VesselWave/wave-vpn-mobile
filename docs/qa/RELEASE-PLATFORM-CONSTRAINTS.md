# Platform investigation review — 2026-10-22 UTC

> **In short:** What an Android VPN app can and can't control, from device configuration and earlier captures. No new captures; the release gates stay open.

The delegated investigation confirmed device access, inspected current VPN/profile
configuration and reran offline checks. It did not produce new packet captures,
profile-attributed traffic, permission-revocation runs, memory-pressure evidence
or overnight results. The release gates in [RELEASE-FOLLOWUP.md](RELEASE-FOLLOWUP.md)
remain open.

## Accepted observations

- The physical Pixel 7 is reachable through the forwarded ADB server on port
  5038; the Android 16 emulator is reachable through the local server on 5037.
- The Pixel has separate owner and work-profile VPN configurations. The work
  profile permits traffic outside its private-subnet tunnel. This is a possible
  source of physical-uplink DNS, not attribution of the earlier six packets.
- The previously recorded emulator captures contain fresh application DNS outside
  the tunnel during process death; temporary counters matched UID 0 socket traffic.
  These facts do not identify the exact resolver/routing race or prove that every
  current stock Android version has the same defect.

## Rejected conclusions

Current configuration inspection cannot retrospectively attribute captured packets
without correlated UID/profile evidence. The earlier physical DNS and DoT findings
remain unassigned. No general owner-profile or whole-device leak-free result is
established. A single TCP SYN has no DNS question payload, but that does not
establish its originating service or prove that a mode change eliminates it.

The proposed detailed TUN teardown/resolver/eBPF mechanism remains a hypothesis,
not a root cause verified by this investigation. Upstream references can provide
context but do not replace source-version analysis or measured evidence. No new
supported-platform restriction or exemption from the privacy gate has been accepted.

No permission-revocation or natural-memory-pressure pass was demonstrated. A
foreground service and post-unlock recovery are existing mitigations, not results
for those missing scenarios. Pre-unlock protection, current stock-platform/module
coverage, native IPv6/cellular captures and overnight reliability remain unverified.

The parent restored the historical report's original remaining-gates paragraph
and rejected the unsupported resolutions. No application code was changed by
this delegated round.
