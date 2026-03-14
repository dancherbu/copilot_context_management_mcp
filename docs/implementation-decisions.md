# Implementation Decisions

This document records the implementation plan and the design decisions made while onboarding and hardening this repository.

## Plan

1. Fix compile and bootstrap blockers first so the app can build and start under Docker Compose.
2. Validate the MCP server, watcher, indexing flow, Redis, Ollama reachability, and proxy routing on the real runtime path.
3. Add regression coverage for the discovered indexing bug.
4. Reduce startup and contributor friction by making the watched source root explicitly user-configurable.
5. Add public-repo safety defaults that reduce accidental local secret ingestion and support optional endpoint protection.
6. Validate the proxied MCP transport path and document the real endpoint behavior.
7. Expand the MCP surface with deterministic context-assembly behavior that still works when the local chat model is unavailable.
8. Expand indexability from a Java-only allowlist to manifest-driven ecosystem detection so mixed-language repos are counted and indexed coherently.
9. Add token-efficient response shaping controls (lean versus verbose) for planning-oriented tools.
10. Include deterministic token-budget guidance in tool responses so clients can trim payloads before downstream LLM calls.
11. Add delta-friendly context reuse metadata to avoid resending unchanged bundles.
12. Add readiness preflight routing defaults so clients can auto-pick a project and call shape with minimal retries.
13. Add a single-call orchestration plan tool that returns argument templates for core MCP tools.
14. Add a super-call bootstrap tool that returns readiness, orchestration templates, and an initial context bundle.
15. Add server-managed orchestration sessions and compact unchanged response mode for bootstrap.
16. Add a project-guidance discovery tool for local instructions, standards, deployment steps, and secret locations.

### Token Efficiency Roadmap Status

1. Completed: `build_context_bundle` supports `responseMode=lean|verbose`.
2. Completed: `ContextBundle` includes `estimatedPromptTokens` and deterministic `suggestedTrimPlan`.
3. Completed: `responseMode` and token-budget guidance now propagate through `trace_change_impact`, `find_test_obligations`, and `assemble_execution_brief` outputs.
4. Completed: all planning tools now support `ifNoneMatchContextHash` and emit stable `contextHash` plus `unchanged` metadata for payload reuse across turns.
5. Completed: planning responses now include cache-trust metadata (`cacheHit`, `cacheAgeSec`, `sourceOfTruth`) to help clients decide reuse versus refresh.

## Decisions

### 1. Use Docker Compose as the source of truth for verification

- Decision: all meaningful verification runs through the Compose stack rather than host-local Java or Maven assumptions.
- Reason: the app depends on Redis, Ollama reachability, mounted source trees, and Nginx Proxy Manager routing.
- Outcome: compile, startup, health, SSE, and indexing validation all ran against the containerized runtime.

### 2. Replace maintainer-specific path defaults with explicit user configuration

- Decision: remove the committed fallback to `/home/dancherbu/Projects` and require `HOST_PROJECTS_DIR` in `.env`.
- Reason: contributors will use their own local paths, and broad home-level mounts make first-run indexing expensive and risky.
- Outcome: `.env.example` now requires an explicit absolute path and the local `.env` points only at this repo.

### 3. Scope `.gitignore` rules to their owning repository subtree

- Decision: a `.gitignore` only applies to files under its own base directory.
- Reason: cross-repo rule leakage caused the initial full reindex to complete with zero indexed files.
- Outcome: indexing now produces vector-store output and the regression is covered by tests.

### 4. Ignore obvious secret-bearing files by default

- Decision: add built-in ignore patterns for `.env*`, `copilot-secrets.md`, key material, and keystore-like files.
- Reason: this repo may become public and contributors may point CCM at local workspaces that contain secrets unrelated to the indexed code.
- Outcome: secret-bearing files are skipped even if a downstream repo forgets to ignore them.

### 5. Add optional app-level API-key protection for MCP transport endpoints

- Decision: if `CCM_API_KEY` is set, `/sse` and `/mcp/**` require a bearer token or `X-CCM-API-Key` header.
- Reason: local proxy exposure can drift beyond a trusted single-user setup, and a simple transport guard materially improves safety without introducing Spring Security.
- Outcome: protection is available without changing the default local-only developer experience.

### 6. Keep proxy validation focused on the actual transport endpoints

- Decision: treat `/sse`, `/mcp/message`, and `/actuator/health/liveness` as the real validation surface instead of `/`.
- Reason: this service is not a browser app and returning `404` at `/` is expected.
- Outcome: the verified proxied endpoints are documented and repeatable.

### 7. Add deterministic MCP context assembly before expanding model-heavy tooling

- Decision: add a `build_context_bundle` tool that assembles repo context from vector hits and project scaffolding without requiring a chat completion.
- Reason: the app's primary value is reducing the context burden on downstream coding models, and that should not fail just because the local chat model is missing or misconfigured.
- Outcome: MCP now exposes a context-pack tool that works against the indexed repo even when chat-based analysis is degraded.

### 10. Prefer deterministic planning tools before deeper LLM orchestration

- Decision: add `trace_change_impact`, `find_test_obligations`, and `assemble_execution_brief` as deterministic MCP tools built on top of repo-local vector hits and file heuristics.
- Reason: enterprise coding models benefit most from bounded execution context, blast-radius visibility, and explicit test obligations, all of which can be prepared locally without another chat round-trip.
- Outcome: the MCP server now produces structured implementation context packs rather than only raw impact guesses.

### 11. Make planning heuristics symbol-aware and reuse-oriented

- Decision: improve test mapping and dependency tracing with indexed symbol/text inspection, and add `find_similar_implementations` for local pattern reuse.
- Reason: filename-only heuristics are not enough for reliable enterprise-code guidance; local models should surface actual code relationships and reuse candidates before delegating implementation.
- Outcome: the planning tools now expose dependency edges, better test linkage, reusable implementation examples, and estimated payload token sizes.

### 12. Replace the hardcoded file allowlist with manifest-driven ecosystem detection

- Decision: derive per-project indexable source extensions from repository manifests such as `package.json`, `pyproject.toml`, `build.gradle(.kts)`, `Cargo.toml`, and `pubspec.yaml`, while keeping a small baseline of config and documentation file types.
- Reason: the previous `.java/.xml/.properties/.yml/.yaml` allowlist made mixed-language repos like `famamemomo` appear artificially tiny in both indexing and coverage views.
- Outcome: the indexer and dashboard now share one project-aware matcher, and non-Java file chunks are truncated before embedding so larger config and source files do not blow past local embedding-model limits.

### 13. Add token-efficient planning response controls

- Decision: introduce `responseMode` to planning-oriented tools, starting with `build_context_bundle`, with defaults that preserve current behavior and an explicit `lean` mode for low-token workflows.
- Reason: downstream coding agents often need deterministic, compact context envelopes to keep prompt budgets stable and reduce repeated context assembly overhead.
- Outcome: `build_context_bundle` now supports `responseMode=lean`, caps payload breadth in lean mode, and emits token-budget trim guidance.

### 14. Expose token-budget guidance directly in bundle responses

- Decision: attach `estimatedPromptTokens` and `suggestedTrimPlan` to `ContextBundle` responses.
- Reason: callers should not need an extra analysis round-trip to decide whether to shrink `topK`/`maxFiles` before forwarding context to another model.
- Outcome: context-bundle responses now include a deterministic token estimate and explicit next-step trim actions when the estimated payload exceeds the caller budget.

### 15. Add readiness preflight routing defaults

- Decision: extend `get_project_readiness` response with routing-focused defaults (`hasReadyProject`, `suggestedProjectName`, `suggestedResponseMode`, `suggestedTopK`, `suggestedMaxFiles`) and `readinessBlockers`.
- Reason: clients should be able to choose an eligible project and a compact first-call shape without extra heuristics or failed tool calls.
- Outcome: preflight responses now support deterministic routing decisions, including a no-project-discovered blocker path.

### 16. Standardize low-token contracts across remaining impact and reuse tools

- Decision: align `analyze_impacted_files` and `find_similar_implementations` with the same `responseMode`, `tokenBudget`, `ifNoneMatchContextHash`, and response metadata conventions used by planning tools.
- Reason: mixed contracts force clients to branch logic and increase request count; a unified contract lets one orchestration path handle all context-oriented tools.
- Outcome: both tools now support hash-based unchanged responses and cache-trust metadata, enabling one-pass routing and repeat-call token minimization.

### 17. Add one-call orchestration templates for SDLC loops

- Decision: add `get_orchestration_plan` that composes readiness defaults and pre-baked argument templates for the full context/planning toolchain.
- Reason: clients should be able to start from one deterministic call instead of manually composing each tool argument shape.
- Outcome: the MCP surface now supports a single-call bootstrap that reduces orchestration requests and improves repeatability for local agent workflows.

### 18. Add a one-call orchestration bootstrap with initial context hydration

- Decision: add `get_orchestration_bootstrap` to combine readiness, orchestration templates, and a first `build_context_bundle` execution in one response when a project is ready.
- Reason: clients should minimize request count for first-turn SDLC setup and immediately receive usable context without stitching multiple MCP calls.
- Outcome: one deterministic super-call now covers preflight + routing templates + initial context bundle, while still returning actionable blockers when no project is ready.

### 19. Add server-managed orchestration session state and compact unchanged responses

- Decision: extend `get_orchestration_bootstrap` with optional `orchestrationSessionId` and `returnPayloadOnUnchanged`, plus server-side session state for remembered defaults and context hashes.
- Reason: clients should avoid resending per-call orchestration state and should be able to request minimal responses on unchanged context loops.
- Outcome: bootstrap responses now return `orchestrationSessionId` and `sessionState`; session state is persisted in Redis with TTL, and unchanged bundle payloads can be omitted deterministically when requested.

### 20. Add local project-guidance discovery for Copilot workflow context

- Decision: add `get_project_guidance` to extract project purpose, best practices, coding standards, deployment guidance, and secret locations from local instruction files.
- Reason: local Copilot workflows already rely on instruction and secrets files directly; surfacing this context via MCP reduces repeated manual context transfer and supports project-specific conventions.
- Outcome: MCP now exposes deterministic guidance discovery with `guidanceHash` and unchanged short-circuit behavior, plus orchestration templates for direct use in planning loops.

### 8. Degrade `analyze_impacted_files` gracefully when the local chat model fails

- Decision: if the Ollama chat model is unavailable or returns invalid JSON, fall back to deterministic vector-match reasoning instead of surfacing a tool error.
- Reason: the MCP client should still get actionable file candidates rather than a hard failure during local development.
- Outcome: the impact-analysis tool remains useful under partial local-model failure.

### 9. Prune stale persisted vector entries on startup

- Decision: rebuild the file-to-chunk index from the persisted `SimpleVectorStore` and remove entries whose indexed files no longer exist.
- Reason: persisted embeddings from an older, broader watch root polluted context results after narrowing the mounted repo scope.
- Outcome: live MCP context results stay scoped to the currently mounted repository instead of leaking other local projects.

## Validation Status

- Build: `docker compose up -d --build` succeeds.
- Tests: `mvn test` passes in the containerized Maven path.
- App health: `http://ccm-mcp.local/actuator/health/liveness` returns `{"status":"UP"}`.
- MCP SSE: `http://ccm-mcp.local/sse` returns `text/event-stream` and advertises `/mcp/message?sessionId=...`.
- MCP message path: direct POST without a session id returns `400`, which confirms the endpoint is live and enforcing session semantics.
- Indexing: the vector store file is created and populated.
- MCP tools: live transport validation confirms `tools/list` exposes both `analyze_impacted_files` and `build_context_bundle`.
- Context assembly: live `build_context_bundle` calls now return repo-local results after startup pruning removes stale persisted entries.
- Planning tools: transport validation now covers the expanded MCP surface, including the execution-brief path that composes context, impact, and test obligations.
- Reuse tools: the MCP surface now also exposes `find_similar_implementations`, and planning responses include `estimatedPayloadTokens` for context-budget decisions.