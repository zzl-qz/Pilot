# Completion Bundles, Policy-as-Code & Task Capsules

[English](completion-capsule.md) | [简体中文](zh-CN/completion-capsule.md)

> Last updated: 2026-11-27

These three runtime primitives turn "the agent says it's done" into an auditable, portable deliverable—leaving verifiable evidence outside the model's prose.

## 1. Completion Bundles

Export verifiable completion artifacts for any session:

- `GET /api/sessions/{id}/completion` — Machine-readable JSON bundle.
- `GET /api/sessions/{id}/completion/markdown` — Paste-ready Markdown summary for PRs or tickets.
- `POST /api/sessions/{id}/completion/verify` — Re-checks whether existing evidence still covers current workspace file fingerprints without silently re-running commands.

A bundle records:
- Original goal, constraints, acceptance criteria, and mid-run corrections.
- Verification verdict (`VERIFIED` / `STALE` / `UNVERIFIED`) with machine-readable reason codes.
- Evidence entries: artifact fingerprints, touched files, commands, exit codes, environment fingerprint, and run ID.
- Host-side browser evidence (`browserEvidence`): verified visited origins, screenshot/replay paths, downloads, blocked actions, and errors.
- Changed files and per-file diffs (when snapshots exist), required vs. missing policy checks, and next-step guidance.

**Critical invariant**: Modifying any tracked file immediately marks prior evidence as **`stale`** instead of reusing outdated sign-offs.

```bash
curl -s http://127.0.0.1:9999/api/sessions/ses_xxx/completion | jq
curl -s http://127.0.0.1:9999/api/sessions/ses_xxx/completion/markdown
curl -s -X POST http://127.0.0.1:9999/api/sessions/ses_xxx/completion/verify | jq
```

## 2. Verification Policy-as-Code (`pilot.policy.yaml`)

Place `pilot.policy.yaml` (or `.pilot/policy.yaml`) at your workspace root:

```yaml
version: 1
checks:
  "src/**":
    - "mvn -q test"
  "docs/**":
    - "node scripts/check-doc-links.mjs"
  "*.sql":
    - "sqlfluff lint"
completion:
  require: all      # all (default) or any
  allowOverride: false
```

Semantics:
- Any edit matching a path pattern requires an exit-code `0` record for the corresponding command(s) during the run.
- `require: all` enforces every matched command; `any` requires at least one.
- `allowOverride: false` (default) blocks completion on missing checks (`VERIFICATION_FAILED`); malformed policy files **fail closed**.
- Policies only trust commands recorded through Pilot's `run_command` tool.

## 3. Task Capsules (Session Handoff)

Hand off an in-progress or completed task to another session, model, or teammate:

```bash
# Export capsule
curl -s http://127.0.0.1:9999/api/sessions/ses_xxx/capsule > capsule.json

# Import into a target project (optional workspace override)
curl -s -X POST "http://127.0.0.1:9999/api/projects/prj_xxx/capsules?workspace=/path/to/repo" \
  -H 'content-type: application/json' --data-binary @capsule.json
```

Importing creates a new session in `WAITING` state with the task contract, evidence, diffs, and next steps injected as context—**it never auto-applies diffs**, ensuring handoffs cannot bypass human approval. Treat capsules with the same confidentiality as source code.

## Current Boundaries

- The Deliverables panel exposes UI actions for Completion Bundles and Capsule Export; Capsule Import is currently API-driven.
- Policies are workspace-scoped (no cross-branch inheritance), and capsule import does not merge Git histories.
