# Contributing Guide

Thanks for contributing to copilot_context_management_mcp.

## Workflow

1. Create a branch from main.
2. Keep branch scope small and focused.
3. Run tests locally before opening a PR.
4. Open a Pull Request into main.
5. Wait for required reviews and CI checks.
6. Squash merge after approval.

## Branch Naming

Use one of these prefixes:

- feat/<short-topic>
- fix/<short-topic>
- chore/<short-topic>
- docs/<short-topic>
- test/<short-topic>

Examples:

- feat/project-guidance-hash
- fix/usage-reset-endpoint

## Commit Message Style

Use concise imperative messages.

Examples:

- Add project guidance tool
- Fix usage telemetry persistence
- Update MCP metrics UI export action

## Pull Request Checklist

Before requesting review, verify all items:

- [ ] Branch is up to date with main
- [ ] Build is green
- [ ] Tests pass locally
- [ ] No secrets were committed
- [ ] Documentation updated (if behavior changed)
- [ ] Screenshots attached (for UI changes)
- [ ] Migration notes added (if infra/runtime changed)

## Local Validation

Run tests in a Maven container:

```bash
docker run --rm -v "$PWD":/workspace -w /workspace maven:3.9.9-eclipse-temurin-21 mvn -q test -DskipITs
```

Run runtime smoke checks with Compose:

```bash
docker compose up --build -d
curl -fsS http://localhost:18080/actuator/health/liveness
```

## Security

- Keep local-only secrets in copilot-secrets.md and .env.
- Never commit credentials, keys, or tokens.
- Review diffs for accidental secret exposure before pushing.

## Merge Policy

- Direct pushes to main should be disabled in repository settings.
- Use Pull Requests with required approvals and required status checks.
- Prefer squash merge to keep main history concise.
