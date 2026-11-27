# Pilot Documentation

[English](README.md) | [简体中文](zh-CN/README.md)

Browse by topic—you do not need to read the entire repository to get started.

## Using Pilot

| Topic | Document |
| --- | --- |
| First-time setup, choosing a workspace, and configuring models | [Getting Started](getting-started.md) |
| Workbench workflows, browser subagent, schedules, backups, and env vars | [User & Configuration Guide](user-guide.md) |
| System architecture, agent loop, SSE cursor replay, and sandbox layers | [Architecture](architecture.md) |
| Reproducible Multi-SWE-bench A/B results, stability, and crash recovery | [Benchmarks & Verification](benchmarks.md) |
| Permission modes, high-risk guards, OS sandboxes, browser privacy, and remote access | [Security & Privacy](security.md) |
| MCP startup command hashing and tool definition pinning | [MCP Trust Model](design-mcp-trust.md) |
| Explicit non-goals, target audience, and design boundaries | [Scope & Boundaries](scope.md) |
| Contract-based plugin compatibility (skills & hooks) | [Plugin Contracts](plugin-contracts.md) |
| Why runtime-native verification differs from external check wrappers | [Verification Approach](verification-approach.md) |
| Exportable completion bundles, `pilot.policy.yaml`, and task capsules | [Completion Bundles & Handoff](completion-capsule.md) |
| Electron desktop host & self-contained `jlink` packaging | [Desktop Guide](../pilot-desktop/README.md) · [Release Packaging](../pilot-desktop/RELEASE_PACKAGING.md) |
| Android companion app, mobile approval demo, and P2P/relay latency | [Android Guide](../pilot-mobile/README.md) · [Approval Demo](mobile-approval-demo.md) · [Latency Measurement](mobile-latency-measurement.md) |

## Engineering & Contributing

| Document | Purpose |
| --- | --- |
| [PROJECT_TECHNICAL_GUIDE.md](../PROJECT_TECHNICAL_GUIDE.md) | Detailed subsystem walkthrough & source-reading roadmap (Chinese) |
| [PRODUCT_DIRECTION.md](../PRODUCT_DIRECTION.md) | Product goals, phases, and release gates |
| [GENERAL_AGENT_WORKFLOW.md](../GENERAL_AGENT_WORKFLOW.md) | General task workflow, remote workbench, and deliverables |
| [SOURCE_ENVIRONMENT.md](../SOURCE_ENVIRONMENT.md) | Automatic JDK 25 / Node.js 22 runtime provisioning |
| [CONTRIBUTING.md](../CONTRIBUTING.md) | Bug reports, verification checklist, and cross-platform rules |
| [SECURITY.md](../SECURITY.md) | Private vulnerability reporting policy |

## Evaluation Artifacts

`evaluation/` stores automated evaluation harnesses and dated verification records (including the reproducible [Multi-SWE-bench suite](../evaluation/multi-swe-bench/README.md)). Every record applies to a specific commit, model, and environment; always check dates, commit SHAs, and documented boundaries before generalizing.

## Documentation Conventions

Build and test commands follow the repository scripts and [CI workflow](../.github/workflows/ci.yml). UI screenshots in the README are generated from Playwright regression scenarios using mock API fixtures and do not represent live model runs.
