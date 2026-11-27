# User & Configuration Guide

[English](user-guide.md) | [简体中文](zh-CN/user-guide.md)

> Last updated: 2026-11-27

This guide covers core workbench workflows, browser automation, model presets, scheduled tasks, backups, and environment variables. Start with [Getting Started](getting-started.md) for installation and read [Security & Privacy](security.md) before enabling remote access or external browser origins.

## Projects, Sessions & Notes

- **Projects & Multi-Directory Workspaces**: Organize sessions under projects (up to 12 bound directories per project, selectable via native OS folder pickers or built-in server directory browser fallback). Each project tracks its initial Git baseline. Deleting a session or project cleans up its internal documents, exports, and managed browser evidence while leaving workspace source files untouched.
- **Session Filtering & Coordination**: Filter sidebar sessions (`All / Active / Action Needed / Unread`), receive cross-session pending-approval banners, monitor concurrent sessions via the workspace coordination board (with lease handoff and deadlock/stall watchdogs), and open grouped shortcuts anytime via `Cmd/Ctrl + /`.
- **Scratchpad Ideas**: Jot down session-scoped notes next to the composer while a run is active (never auto-sent); click any note to insert it back into the composer when ready.

## Active Runs & Execution Controls

- **`plan` vs. `act` Modes (`Shift+Tab`)**: Toggle read-only planning (`plan`, stripping all write/side-effect tools at tool-assembly time and enforcing `READ_ONLY`) vs. execution (`act`), propagated per message including queued turns and resumes.
- **Slash Commands, Built-In Skills & Subagents**: Type `/` for `/test`, `/fix`, `/run`, `/review` (read-only code review), `/browser`, `/explain`, and built-in skills (`/review-code`, `/triage`, `/refactor`, `/security`, `/tests`; overridable at project > user > built-in scope). Built-in multi-agent roles include `explorer` (read-only research), `reviewer` (read-only audit), and `verifier` (build/test verification), overridable via `.pilot/agents/`.
- **Message Queueing & Attachments**: Queue follow-up tasks (carrying per-message `plan`/`act`, skills, attachments, and constraints/acceptance criteria) with independent queue pause/resume. Drag-and-drop or paste files (default 32 MiB/file, 64 MiB/message; configurable via `pilot.attachments.max-file-bytes` / `max-total-bytes`). Only PNG/JPEG/WebP/GIF are forwarded as vision images.
- **Approvals, Corrections & Resilience**: File-edit approvals show inline diffs and support rejection reasons, batch low-risk approval, prefix allow rules, keyboard shortcuts (`Alt+1..9`, `Alt+Y`, `Alt+N`), and Electron always-on-top popout alerts. Mid-run **Correct Now** steers the active run without clobbering queued tasks. Built-in resilience handles HTTP 429/5xx tiered backoff (`Retry-After`), sleep/wake budget compensation, and bounded auto-reconnect on transient network drops.
- **Context Compaction, Rules & Long-Term Memory**: Manual or automatic two-stage summarization compaction preserves warm Prompt Caching prefixes (optional `focus` hint). Project rules load `AGENTS.md` and `.pilot/rules` glob patterns. External memory indexes formal entries in `MEMORY.md`—the agent's `remember` tool writes reviewable drafts only, while `read_memory` performs keyless local multi-term weighted retrieval.

## Code Review, Diffs, Checkpoints & Universal Preview

- **Code Intelligence Tools**: `code_symbol` (outline & cross-file definition search), `code_diagnostics` (Maven, Gradle, TypeScript/monorepo local `tsc`, Go, Python `pyright`/`ruff`, Rust `cargo check`), and parallel read-only Git tools (`git_status`, `git_diff`, `git_log`).
- **Resilient File Edits & Checkpoints**: `edit_file` performs exact matching with unique trailing-whitespace/relative-indent fallback (rejecting ambiguous matches); `delete_file` and `move_file` integrate with pre-write snapshots. Checkpoints rewind **code only**, **conversation only**, or **both**, validating snapshot fingerprint chains first and refusing to overwrite conflicting manual edits.
- **Changes Panel & Universal Preview**: Stage selected files and commit with UTF-8 safe `git commit --only -F -` (plus keyless deterministic commit-message drafting). Built-in zero-dependency preview renders syntax-highlighted code, PDF/images/audio/video, structured DOCX/XLSX/PPTX (with evaluated formula values), and `.zip`/`.jar`/`.war`/`.epub` manifests; the `office` tool creates and verifies XLSX/PPTX/DOCX/PDF deliverables.

## Built-In Browser, Web Search & MCP

- **Browser Subagent (`browser_agent`)**: Isolated Playwright subagent (no `evaluate`, file, or shell access) supporting nested same-origin `iframe` and open Shadow DOM traversal, occlusion checks, HTML5/coordinate `drag`, visual `(x, y)` fallback, native dialog handling, dynamic `filechooser` uploads, and up to 50-step `batch` runs.
- **Take Control & Interactive Replays**: Permanent blue control border during automation with instant **Stop** preemption, human `handoff` for login/MFA/CAPTCHA/payment gates (payment automation is hard-blocked), and zero-dependency `.replay.json` recordings featuring `0.5x/1x/2x` playback, step timelines, and drag-box coordinate annotations sent back to the composer.
- **Keyless-First Web Search & Fetch**: `web_search` runs out of the box via managed SearXNG (v4 keyless pool) with automatic fallback to public read-only APIs (Wikipedia, GitHub, StackOverflow, Hacker News, arXiv, OpenAlex), or optional API keys for Bocha, Tavily, Brave, Serper, or Exa. `web_fetch` decodes UTF-8/GBK/GB18030, extracts `<article>`/`<main>`, and re-validates SSRF boundaries on every redirect hop (max 5).
- **MCP (`stdio` + Streamable HTTP / SSE)**: Supports `Mcp-Session-Id`, JSON and SSE responses, legacy SSE 405 fallback, one-click keyless templates (Filesystem, Fetch, Git, Memory, Sequential Thinking, Context7), and SHA-256 config + tool-definition pinning ([MCP Trust Model](design-mcp-trust.md)).

## Model Presets, Failover & Voice Input

- **Keyless Local Models & Presets**: Empty API keys omit auth headers for native compatibility with Ollama, LM Studio, llama.cpp, and vLLM. Save and switch presets via Settings or the composer `ModelPicker`.
- **Automatic Backup Failover**: Mark any preset as a fallback (e.g., cloud API → local Ollama) to seamlessly fail over on exhausted 429/401/402/403 or disconnects without altering global defaults (never blindly failing over on 400 bad requests, context overflow, or user cancel).
- **Voice Input**: Supports `Auto`, keyless browser Web Speech API, or OpenAI-compatible / local `whisper.cpp` / `faster-whisper` transcription endpoints.

## Scheduled Tasks & Notifications

Schedules execute while the Pilot server is online, using each schedule's saved timezone with deterministic DST handling (repeated wall-clock times run once; skipped spring-forward times are skipped). Notifications support desktop alerts and `Idempotency-Key` webhooks (up to 6 delivery retries without re-running tasks).

## Key Environment Variables

Defaults live in [application.yaml](../pilot-server/src/main/resources/application.yaml); git-ignored local overrides belong in `pilot-server/src/main/resources/application-local.yaml`.

| Variable | Purpose | Default |
| --- | --- | --- |
| `PILOT_MODEL_API_KEY` | Model API key | Empty (Mock mode or keyless local server) |
| `PILOT_MODEL_ENDPOINT` | Model endpoint (OpenAI Chat / Responses, Anthropic Messages) | OpenAI Chat Completions URL |
| `PILOT_MODEL_MODEL` | Provider model ID | `gpt-4o-mini` |
| `PILOT_MODEL_PROMPT_CACHING` | Emit explicit `cache_control` breakpoints on compatible gateways | `false` (auto-managed on official Anthropic) |
| `PILOT_MODEL_TIMEOUT_SECONDS` | Per-request total timeout (1–86400s; overridable per model in UI) | `900` |
| `PILOT_MODEL_IDLE_TIMEOUT_SECONDS` | Stream idle timeout including TTFT (`0` = follow total) | `600` |
| `PILOT_WORKSPACE` | Default workspace directory | Launch directory |
| `PILOT_PERMISSION_MODE` | Permission tier (`AUTO` / `AUTO_EDIT` / `ASK` / `READ_ONLY`) | `AUTO` |
| `PILOT_EXECUTION_COMMAND_NETWORK` | Allow outbound network inside macOS/Linux command sandbox | `true` |
| `PILOT_BROWSER_ALLOW_HOSTS` / `PILOT_BROWSER_DENY_HOSTS` | Browser external host allowlist / denylist (`deny` wins) | Empty |
| `PILOT_BROWSER_SENSITIVE_HOSTS` / `PILOT_BROWSER_SENSITIVE_ALLOW_HOSTS` | Extra sensitive auth domains & allowlist exceptions (payments non-overridable) | Empty |
| `PILOT_BROWSER_MAX_DOWNLOAD_BYTES` | Browser single-file download limit in bytes | `0` (50 MiB default) |
| `PILOT_BROWSER_CDP` | Loopback Chrome CDP debug endpoint | Empty (built-in Chromium) |
| `PILOT_WEB_SEARCH_PROVIDER` | Search provider (`auto`/`bocha`/`tavily`/`brave`/`serper`/`exa`/`searxng`/`public`) | `auto` |
| `PILOT_WEB_SEARCH_PROXY` | Outbound proxy for search & managed SearXNG (loopback always direct) | Empty |
| `PILOT_DATA_DIR` | SQLite, snapshots, browser evidence, and trace directory | `.pilot/` |
| `PILOT_RETENTION_DAYS` | Retention window for aged events/snapshots/traces | `0` (keep all) |
| `PILOT_SECURITY_MODE` | `local` (loopback only) or `token` (shared bearer token) | `local` |

Response size caps: 32 MiB JSON, 64 MiB cumulative stream, 1,048,576 chars per SSE line, 64 KiB error body, 4 MiB model list/search response, 2 MiB `web_fetch`.

## Backups, Diagnostics & Offline Restore

Use **Settings → Diagnostics** to trigger verified backups or export a redacted diagnostic bundle (`GET /api/diagnostics/export`, `sanitized=true`). For offline restore after stopping Pilot:

```text
java --enable-native-access=ALL-UNNAMED -jar pilot-server.jar maintenance restore "--data-dir=<target>" "--backup=<backup>"
java --enable-native-access=ALL-UNNAMED -jar pilot-server.jar maintenance restore "--data-dir=<target>" "--backup=<backup>" --apply
```

The first command runs a preflight check; `--apply` switches cleanly while archiving prior data/WAL files for `maintenance rollback`. See the Chinese guide ([zh-CN/user-guide.md](zh-CN/user-guide.md)) for full workspace migration flags (`--workspace`, `--workspace-map`).
