# Verification Approach

[English](verification-approach.md) | [简体中文](zh-CN/verification-approach.md)

> Last updated: 2026-11-27

Many tools make AI completion claims checkable (external check scripts like `donecheck`, CLI/editor hooks like `agent-done-or-not`, review plugins like `Crucible`, or verification prompt snippets). They share a common pattern: **they wrap the agent from the outside** and focus on a single code diff.

## Why Pilot Builds Verification Into the Runtime

| Dimension | External Verification Wrappers | Pilot (Runtime-Native) |
| --- | --- | --- |
| Evidence source | Separate user-wired external script | Generated natively alongside tool runs and summaries |
| Coverage | Usually a single code diff | Unified across code edits, deliverables, browser actions, and command tasks |
| Artifact binding | Tied to a commit or single check run | Bound to workspace/file fingerprints—any subsequent edit marks evidence `STALE` (`ValidationReason.STALE_CANDIDATE`) |
| Cross-surface consistency | Tied to one terminal or editor | Web, Electron desktop, and Android read the exact same ledger and 8-char fingerprint prefix |
| Staleness | Rarely modeled as a first-class state | Distinct first-class status separated from "never verified" and "failed" |

**In one sentence**: external wrappers answer *"was there ever evidence?"*; Pilot also answers ***"is that evidence still valid right now?"***

## Core Implementation Locations

| Capability | Source File |
| --- | --- |
| Evidence ledger (by session & candidate fingerprint) | `pilot-server/src/main/java/com/pilot/server/validation/ValidationLedger.java` |
| Verdict evaluation (current files vs. latest evidence) | `pilot-server/src/main/java/com/pilot/server/validation/ValidationStatusService.java` |
| Tri-state verdicts & reason codes | `ValidationSnapshot.java`, `ValidationReason.java` |
| Artifact fingerprinting | `WorkspaceFingerprint.java` |
| Work product verification binding | `pilot-server/src/main/java/com/pilot/server/work/WorkProductService.java` |
| Automatic revocation of human sign-off on stale edits | `pilot-server/src/main/java/com/pilot/server/run/TaskControlService.java` |

## Boundaries

- This comparison is architectural, not a feature-count claim—external scripts can be lighter in single-tool workflows.
- Pilot's ledger only covers files and commands recorded through its runtime; see [Scope & Boundaries](scope.md).
