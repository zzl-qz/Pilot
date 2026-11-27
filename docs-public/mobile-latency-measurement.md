# Mobile-to-Host Latency Measurement

[English](mobile-latency-measurement.md) | [简体中文](zh-CN/mobile-latency-measurement.md)

> Last updated: 2026-11-27

Claims like "P2P direct preferred for lower latency" require comparable measurements across three network topologies:

| Scenario | Network Condition | Expected Behavior |
| --- | --- | --- |
| **A. Same LAN direct** | Phone and host on the same Wi-Fi | Fastest (~tens of ms connection setup) |
| **B. Cross-network direct** | Cellular + remote host with hole-punching | Slower than LAN, still direct (no relay) |
| **C. Relay fallback** | Symmetric NAT or strict firewall | Slowest; depends on relay geography |

## Live Path Transparency

The mobile task header and Devices view render the actual active transport and RTT from `RemoteClient.pathLabel()` (e.g., `Direct · 12 ms` vs. `Relay · 68 ms`)—relayed traffic is never disguised as direct.

## Running `--measure`

`pilot-mobile/bridge` provides a repeatable `--measure` harness that establishes fresh connections `N` times against a host pairing ticket and reports exact nearest-rank percentiles (`p50`, `p95` using `ceil(0.95n)` without interpolation; missing RTTs remain `null`, never `0`):

```bash
cd pilot-mobile
./gradlew :bridge:installDist
build/install/bridge/bin/bridge --measure <ticket> --samples 20 --label "A-LAN"
```

Example JSON output:

```json
{
  "label": "A-LAN",
  "path": "direct",
  "samples": 20,
  "connectMs": { "p50": 38, "p95": 61 },
  "firstByteMs": { "p50": 21, "p95": 44 },
  "rttMs": { "p50": 9, "p95": 14, "observed": 20 }
}
```

- Never benchmark loopback endpoints on the same machine as "direct P2P".
- Always record device model, OS version, Wi-Fi/cellular band, geographic distance, and background traffic alongside the 20-sample JSON reports (`evaluation/MOBILE_LATENCY_<date>.md`).

## Current Status

Measurement harness and percentile logic are implemented and unit-tested (`LatencyMeasureTest`, 5 tests). Physical three-network field captures remain pending real-device runs.
