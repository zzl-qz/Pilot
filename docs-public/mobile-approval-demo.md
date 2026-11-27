# Mobile Approval End-to-End Demo

[English](mobile-approval-demo.md) | [简体中文](zh-CN/mobile-approval-demo.md)

> Last updated: 2026-11-27

## What This Flow Proves

Remote mobile approval must guarantee four invariants simultaneously:

1. Host approval requests reach the paired phone reliably.
2. Approving on mobile unblocks the exact waiting run on the host.
3. **Disconnecting mid-approval never blindly replays**—reconnecting asks the user to reconcile whether the host already received the decision.
4. Strict session attribution prevents approving Task A from advancing Task B.

## Setup

1. Launch Pilot on desktop and switch permissions to **`ASK`** (`AUTO` skips routine approvals).
2. Pair the Android client (**Workbench → Connect Mobile → Scan QR**).
3. Open the mobile **Inbox** tab.

## Verification Scenarios

### Happy Path
1. On desktop, prompt: `Create hello.txt in the workspace containing ok`.
2. Confirm the approval card appears in the mobile Inbox with the matching session/run label.
3. Tap **Approve** on mobile; verify `hello.txt` is created once on desktop and both surfaces display the same validation fingerprint prefix.

### Disconnect & Reconcile Path (Critical)
1. Prompt: `Create second.txt`.
2. Enable **Airplane Mode** on mobile, then tap **Approve**—the UI must not falsely claim success.
3. Wait 10 seconds and restore connectivity: mobile must show a **reconcile status** prompt rather than auto-replaying.
4. Reconcile; verify `second.txt` was created **at most once** (idempotent receipt on retry if already executed).

### Attribution & Deduplication
- **Multi-task isolation**: Trigger two approvals across two tasks; approving Task A never advances Task B.
- **Double-tap idempotency**: Rapidly tapping Approve executes once and returns the same receipt.
- **Stale approval**: Rejecting on desktop removes the pending card on mobile without re-executing.

## Current Status

Backend attribution, idempotent receipts, non-replaying disconnect reconciliation, and cross-surface validation status are implemented with automated tests. Physical-device demo captures are tracked separately prior to final release.
