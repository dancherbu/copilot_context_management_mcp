# copilot_context_management_mcp

Standalone Spring Boot MCP server for local codebase indexing, impact analysis, and MCP exposure over SSE.

## Stack

- Java 21
- Spring Boot 3.5.x
- Spring AI MCP Server WebFlux
- Spring AI Ollama
- Spring Data Redis
- SimpleVectorStore
- directory-watcher
- Tree-sitter Java bindings
- JTokkit
- Docker Compose

## Local topology

- Proxy domain: `ccm-mcp.local`
- Internal app container: `${COMPOSE_PROJECT_NAME:-copilot-context-management-mcp}-app`
- Redis container: `${COMPOSE_PROJECT_NAME:-copilot-context-management-mcp}-redis`
- Watched host source mount: `${HOST_PROJECTS_DIR}` -> `${WORKSPACE_WATCH_ROOT:-/workspace/projects}`

## First run

1. Copy values from `.env.example` into your real `.env` if you want overrides.
2. Set `HOST_PROJECTS_DIR` to the single local repo or workspace root you want indexed. Avoid mounting your entire home directory.
3. Fill local-only credentials into `copilot-secrets.md`.
4. Make sure the external Docker network `app_network` exists.
5. Start the stack with `docker compose up --build -d`.
6. Configure Nginx Proxy Manager to proxy `ccm-mcp.local` to `${COMPOSE_PROJECT_NAME:-copilot-context-management-mcp}-app:8080` on `app_network`.

## Safety defaults

- `HOST_PROJECTS_DIR` is intentionally required rather than hardcoded so each user chooses their own local source root.
- Prefer pointing `HOST_PROJECTS_DIR` at a single repo or a tightly scoped workspace. This reduces first-boot indexing time and limits accidental ingestion of unrelated files.
- The host source mount is read-only by design.
- Before publishing this repo, keep local credentials in `copilot-secrets.md` and `.env`, both of which are local-only files.
- CCM ignores obvious secret-bearing files by default such as `.env*`, `copilot-secrets.md`, `*.pem`, `*.key`, and keystore-style files.
- If you set `CCM_API_KEY`, the MCP transport endpoints require either `Authorization: Bearer <key>` or `X-CCM-API-Key: <key>`.
- For local Copilot agent discovery, this workspace also includes a local [mcp.json](.vscode/mcp.json) entry that points VS Code at `http://127.0.0.1:18080/sse`. With `CCM_API_KEY` left blank in local `.env`, the local MCP server can be discovered and used without manual header configuration.

## Notes

- The app assumes Ollama is already running on the host and reachable at `host.docker.internal:11434`.
- Set `OLLAMA_CHAT_MODEL` to a model that is actually installed in your local Ollama instance. The impact-analysis tool now falls back to deterministic vector-based results if the configured chat model is unavailable, but a valid local chat model still gives better reasoning.
- The mounted projects directory is read-only by design.
- Logs are written to `/app/logs/application.log` inside the container and surfaced via the MCP resource.
- The proxied MCP SSE endpoint is `/sse`, which advertises the message endpoint `/mcp/message`.
- A `404` at `/` is expected for this service; validate the server with `/actuator/health/liveness` or the MCP endpoints instead.
- A built-in metrics bench is available at `/mcp-metrics.html` to run real MCP tool calls, inspect `estimatedPayloadTokens`, persist benchmark history locally, visualize trend charts, and inspect embedding coverage across the watched projects.

## MCP Surface

- Tool: `analyze_impacted_files` for change-impact estimation. If the local chat model fails, it returns deterministic vector-match fallback results instead of failing hard.
- Tool: `build_context_bundle` for assembling a compact implementation brief with likely entry points, supporting files, and indexed file matches.
- Tool: `trace_change_impact` for blast-radius tracing across configs, tests, operational touchpoints, and risk areas.
- Tool: `find_test_obligations` for existing test mapping, missing test pairings, and deterministic validation scenarios.
- Tool: `assemble_execution_brief` for a downstream coding-model handoff with files to inspect, edit, test, and validate.
- Tool: `find_similar_implementations` for locating local implementation patterns that should be reused instead of reinvented.
- Tool: `get_project_readiness` for preflight gating decisions before invoking analysis tools.
- Tool: `get_project_guidance` for discovering project standards, deployment instructions, and secret locations from local guidance files.
- `get_project_guidance` prioritizes secret-file references declared in `copilot-instructions.md` (for example custom secret file paths) before fallback guidance-file patterns.
- `get_project_guidance` also supports explicit instruction keys such as `secret_file: path/to/secrets.yml` (plus `secrets_file`, `secret_path`, `secrets_path`) for deterministic secret-location discovery.
- Tool: `get_orchestration_plan` for a single-call package of readiness defaults plus argument templates for all core MCP context/planning tools.
- Tool: `get_orchestration_bootstrap` for a super-call that returns readiness, orchestration templates, and the first lean context bundle when a project is ready.
- `get_orchestration_bootstrap` also supports server-managed session state (`orchestrationSessionId`) persisted in Redis (with TTL) and can omit unchanged context payloads with `returnPayloadOnUnchanged=false`.
- Prompt: `code-review`
- Resource: `logs://application`

### Readiness-Gated Client Flow

1. Call `get_project_readiness` to inspect projects and pick one that is `ready=true`.
2. Pass `projectName` on every analysis tool call (`analyze_impacted_files`, `build_context_bundle`, `trace_change_impact`, `find_test_obligations`, `assemble_execution_brief`, `find_similar_implementations`).
3. If `projectName` is omitted, the server rejects the call for gated tools.
4. If project readiness is below threshold, the server blocks analysis calls and returns a deterministic error explaining file/chunk/semantic shortfall.
5. Use preflight defaults from `get_project_readiness` (`hasReadyProject`, `suggestedProjectName`, `suggestedResponseMode`, `suggestedTopK`, `suggestedMaxFiles`, `readinessBlockers`) to route calls with minimal retry overhead.
6. For a one-call startup path, use `get_orchestration_plan` and consume `toolArgumentTemplates` directly.
7. For a one-call startup plus first context payload, call `get_orchestration_bootstrap` with your task query.
8. Reuse `orchestrationSessionId` returned by bootstrap so later calls can inherit prior context hashes and defaults.
9. For instruction and operations context, call `get_project_guidance` before planning tools and reuse `ifNoneMatchGuidanceHash` on iterative calls.

## Tool Metrics

- The planning-oriented MCP tools now include `estimatedPayloadTokens` in their structured responses so callers can decide whether to trim or expand the handoff context.
- `build_context_bundle` now accepts optional `responseMode` (`verbose` default, `lean` for compact payloads) and `tokenBudget` inputs.
- `build_context_bundle` responses now include `responseMode`, `estimatedPromptTokens`, and `suggestedTrimPlan` to support deterministic payload trimming before downstream model calls.
- `trace_change_impact`, `find_test_obligations`, and `assemble_execution_brief` now also accept optional `responseMode` and `tokenBudget` inputs.
- `analyze_impacted_files` and `find_similar_implementations` now also accept optional `responseMode` and `tokenBudget` inputs.
- The above planning tools now return `responseMode`, `estimatedPromptTokens`, and `suggestedTrimPlan` in addition to existing `estimatedPayloadTokens`.
- Planning and impact tools now accept optional `ifNoneMatchContextHash` and return `contextHash` + `unchanged` so clients can skip re-sending unchanged payloads across iterative calls.
- Planning and impact responses now include cache-trust metadata: `cacheHit`, `cacheAgeSec`, and `sourceOfTruth`.
- Roundtrip timing can be measured at the transport layer with `curl -w '%{time_total}'` against the proxied `/sse` and `/mcp/message` flow.
- Metrics history is stored locally at `${CCM_METRICS_HISTORY_FILE:-.vector-store/metrics-history.json}` and trimmed to `${CCM_METRICS_HISTORY_LIMIT:-20}` runs.
- MCP usage telemetry is persisted at `${CCM_USAGE_METRICS_FILE:-/app/data/usage-metrics.json}` and only resets when the user triggers a manual reset in `/mcp-metrics.html` or calls `POST /api/metrics/usage/reset`.
- The metrics API also exposes `/api/metrics/history` for persisted runs, `/api/metrics/index-overview` for file and semantic AST coverage, and `/api/metrics/usage` for persisted MCP transport utilization.

## Decision Log

- See `docs/implementation-decisions.md` for the implementation plan, each design decision, the reason behind it, and the validation status.

## Collaboration

- Contribution workflow and PR checklist: `CONTRIBUTING.md`
- Branch protection setup guidance: `docs/branch-protection.md`
- Default reviewer ownership: `.github/CODEOWNERS`
- CI cost policy: GitHub Actions is intentionally disabled by default; use local containerized validation before merge.
