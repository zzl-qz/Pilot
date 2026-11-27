# Getting Started

[English](getting-started.md) | [简体中文](zh-CN/getting-started.md)

> Last updated: 2026-11-27

Pilot runs locally by default: your model endpoint, workspace directory, and runtime data stay under your control. Before your first run, prepare a disposable test repository.

## Prerequisites

- 64-bit Windows, macOS, or Linux (automatic Linux runtime provisioning currently targets glibc distributions; musl is not auto-provisioned).
- Git to clone the repository.
- Internet access on first launch: the launcher provisions Java 25, Node.js 22, and desktop dependencies into Pilot's private cache (no pre-installed Maven required). For self-contained desktop installers without host Java, see [Release Packaging](../pilot-desktop/RELEASE_PACKAGING.md).
- PowerShell or Command Prompt on Windows; Bash on macOS/Linux.

Android SDK, Gradle, and Java 21 are only needed when building or testing the optional Android companion app; see [Android & Mobile Bridge](../pilot-mobile/README.md).

## Launch

```sh
git clone https://github.com/zzl-qz/Pilot.git
cd Pilot
```

macOS / Linux:

```sh
bash start.sh
```

Windows (PowerShell or Command Prompt):

```powershell
.\start.cmd
```

First-time provisioning may take a few minutes. The launcher builds and tests the server before starting and fails fast rather than serving a stale artifact. Once ready, open <http://localhost:9999> (Electron desktop also opens automatically when desktop dependencies are available).

To use a custom port if `9999` is occupied:

```sh
bash start.sh --server.port=10001
```

```powershell
.\start.cmd --server.port=10001
```

## Choose a Workspace & Model

For your first trial, pick a disposable repository and switch the permission mode from `AUTO` to `ASK` or `READ_ONLY` (or press `Shift+Tab` in the composer to toggle message-level read-only `plan` mode; use `/review` for read-only code review or `/browser` for browser tasks). See [Security & Privacy](security.md#tool-permissions--local-execution) for permission rules and hard safety redlines.

Without an API key, select **Mock mode** in model settings to explore the workbench UI. Mock mode does not invoke a real model and cannot validate model accuracy.

With a live model, pass environment variables in the same shell or configure multiple presets under **Settings → Model** (never place API keys inside your workspace or commit them to Git):

macOS / Linux:

```sh
PILOT_MODEL_API_KEY="your-api-key" \
PILOT_MODEL_ENDPOINT="https://api.openai.com/v1/chat/completions" \
PILOT_MODEL_MODEL="gpt-4o-mini" \
PILOT_WORKSPACE="/absolute/path/to/test-repo" \
bash start.sh
```

Windows PowerShell:

```powershell
$env:PILOT_MODEL_API_KEY = "your-api-key"
$env:PILOT_MODEL_ENDPOINT = "https://api.openai.com/v1/chat/completions"
$env:PILOT_MODEL_MODEL = "gpt-4o-mini"
$env:PILOT_WORKSPACE = "C:\work\sample-project"
.\start.cmd
```

Pilot supports **OpenAI Chat Completions**, **OpenAI Responses**, and **Anthropic Messages** (with native SSE streaming, Prompt Caching, usage accounting, and per-endpoint/per-model timeout & pricing configuration), as well as keyless local servers such as Ollama, LM Studio, llama.cpp, and vLLM.

## Local Data & Networking

- The server listens on `http://localhost:9999` (`PILOT_SECURITY_MODE=local`, loopback only) by default.
- SQLite session storage, attachments, snapshots, browser evidence, and traces are written to `.pilot/` under the repository root (overridable via `PILOT_DATA_DIR`). On POSIX filesystems, `.pilot/pilot.db` and request diagnostic dumps are restricted to `0600` (owner read/write only).
- `.pilot/` contains local workspace context and run history. Inspect before backing up or sharing; for troubleshooting, use **Settings → Diagnostics** to export a redacted diagnostic bundle (`sanitized=true`).
- Mobile pairing is optional and off by default. Read [Security & Privacy](security.md#remote-access) before enabling remote access.

For provisioning internals, see [SOURCE_ENVIRONMENT.md](../SOURCE_ENVIRONMENT.md).
