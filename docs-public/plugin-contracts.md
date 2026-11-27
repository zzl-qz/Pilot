# Plugin Contracts

[English](plugin-contracts.md) | [简体中文](zh-CN/plugin-contracts.md)

> Last updated: 2026-11-27

## Why Contracts Instead of Core Version Pinning

Plugins typically break during upgrades because they bind to internal implementation details or lock onto exact core version ranges. Pilot decouples extensions from core version bumps by letting skills and hooks declare **versioned capability contracts**.

## Contract Catalog

| Contract | Semantics |
| --- | --- |
| `tool.read_file@1` | Read workspace text or image files |
| `tool.edit_file@1` | Unique-match snippet replacement |
| `tool.create_file@1` | Create new workspace files |
| `tool.run_command@1` | Execute commands and return exit code + output |
| `hook.pre_tool_use@1` | Deterministic hook before tool execution |
| `hook.post_tool_use@1` | Deterministic hook after tool execution |
| `event.run_end@1` | Run termination event |
| `event.tool_result@1` | Tool completion event |
| `skill.frontmatter@1` | `SKILL.md` frontmatter schema and semantics |

Query the live registry via `GET /api/maintenance/contracts` (defined in `pilot-core/src/main/java/com/pilot/core/contract/Contracts.java`).

## Versioning Rules

1. **Additive fields and optional capabilities are non-breaking** (same major version).
2. **Removed fields or semantic/default behavior changes bump the major version** (fail-closed incompatibility).

## Declaring Dependencies

### Skills (`SKILL.md` frontmatter)

```yaml
---
description: Summarize findings and deliver report
requires: tool.read_file@1, event.run_end@1
requires-optional: tool.edit_file@1
---
```

- Missing `requires` → skill is **not loaded** and clearly flagged in capabilities & pre-upgrade checks.
- Missing `requires-optional` → skill loads marked as degraded.

### Hooks (`settings.json`)

```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "edit_file",
        "requires": "hook.post_tool_use@1",
        "hooks": [{ "type": "command", "command": "mvn -q spotless:apply" }]
      }
    ]
  }
}
```

Hook groups missing a required contract fail closed (unloaded and reported) rather than silently skipping.

## Compatibility Inspection & Boundaries

- Check compatibility via **Settings → Diagnostics → Pre-Upgrade Check** (`GET /api/maintenance/plugin-compat`) or `GET /api/sessions/{id}/capabilities`.
- External MCP servers are governed by config/tool-hash pinning ([MCP Trust Model](design-mcp-trust.md)), not plugin contracts.
- Declaring a contract never grants permission—execution still obeys tool permissions and user approvals.
