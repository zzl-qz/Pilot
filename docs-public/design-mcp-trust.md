# MCP Trust Model

[English](design-mcp-trust.md) | [简体中文](zh-CN/design-mcp-trust.md)

This document specifies how Pilot decides whether launching or invoking an MCP server requires explicit user approval (`McpTrustStore`, `McpTrustPolicy`, and Settings/Approval UI).

## What Is Trusted

Pilot trusts **the exact normalized startup configuration** (`command + args + env` for `stdio`, or transport + URL + headers for Streamable HTTP/SSE)—never a server's self-declared `readOnlyHint` or display name. The MCP specification explicitly notes that server annotations are untrusted hints, not security boundaries.

The server computes `configHash = SHA-256(normalized configuration)`. Upon user approval, `configHash`, workspace path, and server name are stored in `mcp-trust.json`. Any modification transitions the record to `STALE`, forcing re-approval before the next launch.

## Tool Definition Pinning (Anti-Rug-Pull)

Once a trusted server starts and exposes its tools, Pilot hashes every tool definition (`name + description + inputSchema + annotations`) into a `toolHash` pinned to that trust record:

- **Read-only tool with unchanged hash**: Runs without re-prompting.
- **Explicitly "Always allow" tool with unchanged hash**: Runs without re-prompting.
- **Definition drift, new tool, rename, or config change**: Immediately reverts to per-call approval.
- `readOnlyHint` is only consulted for pinned tools inside a valid trust record.

Missing, corrupted, or unrecognized `mcp-trust.json` files **fail closed** (all servers treated as untrusted). Deleting a server configuration automatically revokes trust; `mcp-trust.json` is included in backups.

## Trust States & Anti-TOCTOU Approval

- `UNTRUSTED` — No record; initial launch requires approval.
- `TRUSTED_UNPINNED` — Config approved, awaiting initial tool observation to pin `toolHash`.
- `TRUSTED` — Both config hash and observed tool definitions match the pinned record.
- `STALE` — Configuration changed since approval; requires re-approval.

Approving and trusting a server submits the exact `configHash` (and `toolHash` for tool-level actions) displayed on the approval card. The server rejects persistence if `.mcp.json` or observed definitions mutated in between, preventing TOCTOU attacks.

## `READ_ONLY` Mode & Config Import

- Under `READ_ONLY`, ordinary shell commands are blocked; **already-trusted** MCP servers may still launch because their command was previously approved, whereas untrusted servers remain blocked.
- Settings → MCP JSON paste import enforces a strict stdio whitelist (`command`, `args`, `env`, `type`), rejecting local privilege fields (`pilotPermissions`, `url`, `disabled`) atomically. Corrupted or > 1 MiB user config files refuse blind overwrites.
