# Security & Privacy

[English](security.md) | [简体中文](zh-CN/security.md)

> Last updated: 2026-11-27

Pilot reads your selected workspace, sends prompts and context to your configured model endpoint, and can modify files, run shell commands, and drive a controlled browser locally. It is built for **supervised local development and verification**—not as a multi-tenant VM sandbox, backup replacement, or public multi-user service.

## Recommended First-Run Habits

1. Start in a disposable repository with an isolated data directory.
2. Switch permission mode from `AUTO` to `ASK` or `READ_ONLY` (or toggle read-only `plan` mode) before your first run.
3. Inspect the target path, inline Diff preview, and command text on every approval card; reject with a reason whenever uncertain.
4. Keep API keys in your shell environment or local git-ignored files—never inside workspace files.
5. Verify your workspace does not contain secrets or regulated data that must not leave your machine before calling external model endpoints.

## Tool Permissions & Local Execution

| Mode | Behavior |
| --- | --- |
| `AUTO` (default) | Routine workspace edits and commands run automatically, **subject to non-bypassable hard redlines** and mandatory confirmation for package publishing, global installs, force-pushing/deleting protected branches, credential exfiltration, and high-risk tool actions. |
| `AUTO_EDIT` | File edits and local loopback browser interactions run automatically; shell commands and external high-risk actions still require approval. |
| `ASK` | Read-only tools run automatically; file writes, interactive actions, and shell commands require per-action approval. |
| `READ_ONLY` | Only read-only tools run; writes, interactive actions, and commands are rejected. |

Additionally, message-level **`plan` mode** and **`/review`** strip all write and side-effect tools (including deferred tools) at tool-assembly time and enforce a `READ_ONLY` permission gate.

- **Hard Command Redlines & Mandatory Confirmation (`CommandRiskGuard`)**: Even under `AUTO` or broad allow rules, Pilot blocks deleting `/`, home directories, system drives, the workspace root, or paths outside the workspace (including nested `sh -c` / `bash -c` / `cmd /c` / `powershell -Command` wrappers), and enforces confirmation on force-pushing/deleting protected branches (`main`, `master`, `dev`, `develop`, `prod`, `release/*`) and credential exfiltration (`.env`, `.ssh`, private keys, or secret env vars via single commands or pipes).
- **OS-Level Filesystem Sandbox (`OsSandbox`)**: Main-session `run_command` runs inside **macOS Seatbelt** or **Linux bubblewrap** by default (default-deny writes except the active workspace, linked project directories, and standard build caches; `.git/config`, `hooks`, and `info` locked read-only; disable command network access with `PILOT_EXECUTION_COMMAND_NETWORK=false`). Windows supports an optional restricted-token runner; when a sandbox tool is unavailable or unconfigured, execution falls back to the host process with explicit unsandboxed labeling (if a configured runner fails probing, startup fails closed). Local OS sandboxes are still **not** equivalent to multi-tenant VM isolation.
- **Path & Checkpoint Boundaries**: File tools validate real ancestor paths via `PathValidator` (`NOFOLLOW_LINKS` + `toRealPath()`) to defeat symlink escapes. Edit approval cards display inline diffs. Checkpoints only cover writes performed through Pilot's file tools—side effects from shell commands or external processes cannot be automatically rolled back. Use Git and independent backups for critical work.

## Browser Privacy & Security Boundaries

- **Origin & Port Isolation**: Built-in browser defaults to loopback addresses with strict per-port isolation across `localhost`, `127.0.0.1`, and `[::1]`, blocking cross-port and unauthorized cross-origin redirects while recording `blockedOrigins`. External hosts are governed by `PILOT_BROWSER_ALLOW_HOSTS` / `PILOT_BROWSER_DENY_HOSTS` (`deny` takes precedence); external Chrome CDP endpoints are untouched unless an explicit allowlist is configured.
- **Sensitive Page Masking & Payment Hard Blocks**: Login/MFA and payment/checkout pages suppress DOM text, snapshots, and element metadata from the model and redact/skip frames in screenshots and `.replay.json` recordings. Automated interaction and scripts on payment/checkout pages are hard-blocked at the tool layer and routed to user `handoff`.
- **Subagent Isolation & Take Control**: `browser_agent` exposes only the `browser` tool with `evaluate` disabled and zero workspace file/shell access. During automated control, a trusted **Stop** control preempts `batch`/`wait` in milliseconds and locks further automation until explicitly resumed.
- **Upload & Download Inspection**: Uploads are restricted to workspace allowlists (≤ 10 files, ≤ 25 MiB per file, ≤ 50 MiB total), highlighting sensitive names such as `.env` or private keys in approvals. Downloads undergo two-stage inspection against 39 dangerous extensions, dangerous MIME types, executable magic headers (`MZ`, `ELF`, `Mach-O`, `#!`, `.lnk`), and size caps (default 50 MiB via `PILOT_BROWSER_MAX_DOWNLOAD_BYTES`), cancelling and deleting blocked files. Console logs, network traces, desktop logs, and URL fragments are uniformly redacted.

## Model Endpoints & Workspace Data

Live model runs send prompts, user instructions, relevant workspace snippets, and tool outputs to your configured endpoint (OpenAI Chat Completions / Responses or Anthropic Messages). Third-party endpoints apply their own retention and training policies; running Pilot locally does **not** make remote API calls local. For strictly offline processing, configure a local model server (e.g., Ollama, llama.cpp, vLLM) and avoid external endpoints.

## Local Storage, Redaction & Retention

Runtime data defaults to `.pilot/` under the repository root (SQLite database, attachments, snapshots, deliverables, browser evidence/replays, command logs, access audits, and traces), configurable via `PILOT_DATA_DIR`.

- **Unified Redaction & SSRF Guards**: Shared `SecretScrubber` masks Bearer/Basic headers, common provider key prefixes (`sk-`, `ghp_`, `AKIA`, `AIza`, `xox`, `glpat`, `hf_`), PEM private keys, and embedded URL credentials across model/search errors, command outputs, diagnostic exports, and request dumps. Settings endpoints never return plaintext keys. `web_fetch` re-validates every hop (up to 5 redirects) against loopback, private RFC1918, link-local, CGNAT, and IPv6 ULA ranges. On POSIX hosts, `.pilot/pilot.db` and request dumps default to `0600`.
- **Retention**: Events, snapshots, and traces are kept indefinitely by default. Setting a positive `PILOT_RETENTION_DAYS` purges aged records (not cryptographic erasure; sessions themselves remain).

## Remote Access

Default `PILOT_SECURITY_MODE=local` binds strictly to loopback. Enabling `token` mode grants access to any client presenting the shared token—there is no per-user, role, or per-session authorization.

Never expose Pilot directly to the public internet. If remote access is required, place Pilot behind TLS, a maintained authenticating reverse proxy, firewall rules, and exact allowed origins. Avoid leaking tokenized URLs in browser history or proxy logs (SSE clients may pass tokens via query parameters due to header limits). For optional Android pairing boundaries, see [Android & Mobile Bridge](../pilot-mobile/README.md).

## Reporting Vulnerabilities

See [SECURITY.md](../SECURITY.md) for private disclosure instructions. Do not post API keys, tokens, personal data, or exploitable details in public issues, logs, or screenshots.
