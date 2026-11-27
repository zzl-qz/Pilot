# Scope & Boundaries

[English](scope.md) | [简体中文](zh-CN/scope.md)

> Last updated: 2026-11-27

This document answers one question directly: **what Pilot intentionally does NOT do.** Every boundary below reflects an architectural trade-off grounded in the codebase rather than generic boilerplate.

## Target Audience

Pilot is built for **solo builders, students, indie developers, and independent engineers** who want an agent that keeps an auditable ledger of every change and check rather than asking them to blindly trust "Done!".

Pilot intentionally excludes:

| Out of Scope | Rationale |
| --- | --- |
| Enterprise teams | Teams already have PR review workflows, RBAC, and procurement compliance; Pilot does not build multi-user collaboration, RBAC, or enterprise audit pipelines. |
| Non-technical zero-code users | Verification requires a real workspace and artifacts; Pilot starts from local repositories and workspaces rather than a hosted zero-setup SaaS. |

## Explicit Non-Guarantees

| Non-Guarantee | Grounding |
| --- | --- |
| **Not smarter than your underlying model** | Reasoning quality comes from your configured model endpoint. Pilot's value is harness reliability, safety boundaries, and turning "done" into verifiable facts. |
| **Not a multi-tenant VM sandbox** | macOS (Seatbelt) and Linux (bubblewrap) enable filesystem sandboxing for `run_command` by default, and hard redlines apply under `AUTO`, but local sandboxes do not replace VMs or dedicated OS accounts. |
| **Windows native sandbox pending full physical verification** | `OsSandbox` defines a restricted-token Windows runner protocol (`scripts/build-windows-sandbox.ps1`), falling back transparently to the host process when unconfigured; full physical Windows validation is still in progress. |
| **Office generation ≠ Microsoft Office layout parity** | Built-in preview & `office` generation handle structured DOCX/XLSX/PPTX/PDF creation and inspection, not complex macro/VBA or pixel-identical Office layouts. |
| **No multi-tenant collaboration or public SaaS hosting** | Remote mode uses a single shared token without user/role/session isolation ([Security & Privacy](security.md)). |
| **No unsupervised irreversible external actions** | Payment pages and high-risk command redlines always block or require explicit human confirmation. |

## Explicit Non-Coverage

- **Verification evidence only covers tracked artifacts**: Edits made outside Pilot's file tools (inside `run_command`, external editors, or manual tweaks) are never silently claimed as verified; prior evidence transitions to `STALE` or unverified.
- **No blind replay of shell commands**: Evidence stores summaries and exit codes rather than blindly re-executing arbitrary past commands; re-verification runs a fresh session turn.
- **Single-machine state**: Leases and schedules rely on local SQLite with no cross-machine cluster coordinator.

## Related Documents

- [Security & Privacy](security.md)
- [User & Configuration Guide](user-guide.md)
- [Verification Approach](verification-approach.md)
- [Benchmarks & Verification](benchmarks.md)
