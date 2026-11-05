# Best server quality checks — 2026-11-05

`BestQualityTest` runs production ranking against ten invented representative
network scenarios built from the protocol manifest schema, including Chicago,
Amsterdam, Singapore, Tokyo, missing/failed measurements, load and explicit region
constraints. It also preserves an aggregate five-region live sample.

Run `./gradlew :core:test --tests '*BestQualityTest'` (offline).

The separately delegated assessment used bounded live WireGuard paths in a Linux
VM for five regional candidates: all handshook, with five internal ICMP replies
per candidate. The Android scoring function selected the fastest observed server
within that sample. These measurements do not qualify Android transport behavior,
throughput, long-run stability, other access networks or a universal optimum.
Full methodology and the snapshot live in the desktop repository's standalone
`docs/BEST-SERVER-QUALITY.md`; this app's build/test never depends on that repository.

Best combines measured/estimated latency and provider load/capacity factors.
It does not promise the absolute lowest RTT. Location sharing can hide per-server
route differences, unknown locations use a neutral estimate, and bounded sampling
can omit the user's nearest location. Explicit server/country selection stays
available. Real emulator tests separately confirmed marker/connect consistency.

No scoring weights were retuned from this small sample. Android already expires
latency at ranking reads, so the assessment's Linux expiry correction was not
needed here. No account credentials or private profiles are part of these tests.
