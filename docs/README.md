# Documentation

## Using Wave VPN

- [Installation and support](SUPPORT.md): devices, installing and updating, reporting problems
- [v0.1.3 release notes](RELEASE-v0.1.3.md): infrastructure load, recovery and server selection
- [v0.1.1 release notes](RELEASE-v0.1.1.md): previous release
- [v0.1.0 release notes](RELEASE-v0.1.0.md): the first release and its known limits
- [Release signing](RELEASE-SIGNING.md): how to verify downloads and the APK certificate
- [Security policy](../SECURITY.md): private reporting and the signing fingerprints

## How it was tested

Wave VPN publishes its test evidence, including failures. [Verification](VERIFICATION.md) summarizes what has been tested and what hasn't; the reports behind it, newest first:

| Report | What it covers |
| --- | --- |
| [Emulator smoke test](qa/RELEASE-EMULATOR-SMOKE.md) | Released v0.1.0 APK: install, connect, disconnect, crash recovery, Always-on |
| [Stock Samsung](qa/RELEASE-SAMSUNG-STOCK.md) | External packet capture on a Galaxy S24 Ultra; the open DNS finding |
| [VPN comparison](qa/RELEASE-VPN-COMPARISON.md) | Other Android VPN apps under the same lifecycle tests |
| [External emulator comparison](qa/RELEASE-EXTERNAL-EMULATOR.md) | Same comparison on emulators |
| [Hotspot and IPv6](qa/RELEASE-HOTSPOT-IPV6.md) | Tethering and tunneled IPv6 |
| [Lifecycle investigation](qa/RELEASE-LIFECYCLE-INVESTIGATION.md) | Process death and recovery on emulators |
| [Teardown-order experiment](qa/RELEASE-TEARDOWN-ORDER-EXPERIMENT.md) | Whether interface teardown order changes leaks |
| [Round 3 review](qa/RELEASE-ROUND3.md) | Independent review of earlier release evidence |
| [Platform constraints](qa/RELEASE-PLATFORM-CONSTRAINTS.md) | What an Android VPN app can and can't control |
| [Physical follow-up](qa/RELEASE-FOLLOWUP.md) | Pixel capture and DNS follow-up |
| [Signed release checks](qa/RELEASE-DEVICE-TESTING.md) | First signed-build packet checks |
| [Device testing](qa/DEVICE-TESTING.md) | Initial device verification |

Filtered machine-readable results are in [qa/device-tests](qa/device-tests). Raw captures and account data are never published.

## Project

- [Contributing](../CONTRIBUTING.md) and the [device test runner](../tools/vpn-probe/RUNNING.md)
- [Provenance](PROVENANCE.md): which official references the protocol follows, and reused assets
- Release preparation: [public-release checklist](qa/PUBLIC-RELEASE-CHECKLIST.md) and [visibility review](qa/PUBLIC-VISIBILITY-REVIEW.md)
